#include "relay_manager.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/timers.h"
#include "driver/gpio.h"
#include "custom_logging.h"
#include "esp_timer.h"
#include "esp_system.h"
#include "config_manager.h"
#include "time_manager.h"

#define TAG "RELAY_MGR"
#define RELAY_MANAGER_MIN_REPORT_INTERVAL_MS 750
#define RELAY_CONFIG_SAVE_DELAY_MS 8000

static volatile uint32_t s_total_interrupts_received = 0;
static volatile uint32_t s_interrupts_processed = 0;
static volatile int64_t s_last_interrupt_time = 0;
static volatile bool s_interrupt_system_verified = false;
static volatile bool s_verification_in_progress = false;
static SemaphoreHandle_t s_verification_mutex = NULL;

static const gpio_num_t DEFAULT_RELAY_PINS[RELAY_MANAGER_MAX_RELAYS] = {
    GPIO_NUM_32,
    GPIO_NUM_33,
    GPIO_NUM_25,
    GPIO_NUM_26,
    GPIO_NUM_27,
    GPIO_NUM_14
};

static const char* DEFAULT_RELAY_NAMES[RELAY_MANAGER_MAX_RELAYS] = {
    "Relay 1",
    "Relay 2",
    "Relay 3",
    "Relay 4",
    "Relay 5",
    "Relay 6"
};

static const bool DEFAULT_ACTIVE_STATES[RELAY_MANAGER_MAX_RELAYS] = {
    false,
    false,
    false,
    false,
    false,
    false
};

typedef struct {
    gpio_num_t gpio_pin;
} gpio_event_t;

typedef struct {
    relay_config_t relays[RELAY_MANAGER_MAX_RELAYS];
    volatile relay_mgr_state_t state;
    volatile bool initialized;
    
    QueueHandle_t gpio_event_queue;
    SemaphoreHandle_t config_mutex;
    TaskHandle_t event_task_handle;
    
    relay_state_change_callback_t state_callback;
    void *state_callback_user_data;
    relay_mqtt_command_callback_t mqtt_callback;
    void *mqtt_callback_user_data;
    
    uint32_t total_events_processed;
    uint32_t debounce_filtered_events;
    uint32_t mqtt_commands_processed;
    
    char message_pool[3][256];
    bool pool_in_use[3];
    SemaphoreHandle_t pool_mutex;
    
    TimerHandle_t config_save_timer;
    bool config_save_pending;
} relay_manager_context_t;

static relay_manager_context_t s_relay_ctx = {0};

static char* get_message_buffer(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    if (xSemaphoreTake(ctx->pool_mutex, pdMS_TO_TICKS(50)) == pdTRUE) {
        for (int i = 0; i < 3; i++) {
            if (!ctx->pool_in_use[i]) {
                ctx->pool_in_use[i] = true;
                memset(ctx->message_pool[i], 0, 256);
                xSemaphoreGive(ctx->pool_mutex);
                return ctx->message_pool[i];
            }
        }
        xSemaphoreGive(ctx->pool_mutex);
        
        size_t free_heap = esp_get_free_heap_size();
        if (free_heap < 40000) {
            LOG_W(TAG, "Low memory during buffer allocation: %zu bytes", free_heap);
        }
    }
    return NULL;
}

static void release_message_buffer(char *buffer) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    if (!buffer) return;
    
    if (xSemaphoreTake(ctx->pool_mutex, pdMS_TO_TICKS(50)) == pdTRUE) {
        for (int i = 0; i < 3; i++) {
            if (ctx->message_pool[i] == buffer) {
                ctx->pool_in_use[i] = false;
                break;
            }
        }
        xSemaphoreGive(ctx->pool_mutex);
    }
}

static esp_err_t load_relay_config(void);
static esp_err_t save_relay_config_immediate(void);
static void config_save_timer_callback(TimerHandle_t xTimer);
static void schedule_config_save(void);
static void gpio_isr_handler(void *arg);
static void relay_event_task(void *pvParameters);
static relay_state_t gpio_to_logical_state(int gpio_level, relay_contact_type_t contact_type);
static const char* relay_state_to_string(relay_state_t state);
static const char* contact_type_to_string(relay_contact_type_t type);
static relay_contact_type_t string_to_contact_type(const char* str);
static int find_relay_index_by_id(const char *relay_id);
static int find_relay_index_by_gpio(gpio_num_t gpio_pin);

static void debug_relay_states(const char* context) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    LOG_I(TAG, "=== RELAY DEBUG [%s] ===", context);
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        LOG_I(TAG, "Relay %s: active=%s, gpio=%d, state=%s", 
              relay->relay_id, 
              relay->is_active ? "YES" : "NO",
              relay->gpio_pin,
              relay_state_to_string(relay->current_state));
    }
    LOG_I(TAG, "=== END RELAY DEBUG ===");
}

static void config_save_timer_callback(TimerHandle_t xTimer) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    bool should_save = false;
    
    LOG_I(TAG, "Config save timer callback triggered");
    
    if (xSemaphoreTake(ctx->config_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        should_save = ctx->config_save_pending;
        if (should_save) {
            ctx->config_save_pending = false;
        }
        xSemaphoreGive(ctx->config_mutex);
    }
    
    if (should_save) {
        esp_err_t ret = save_relay_config_immediate();
        if (ret == ESP_OK) {
            LOG_I(TAG, "Deferred relay configuration saved to NVS");
        } else {
            LOG_E(TAG, "Failed to save deferred relay configuration: %s", esp_err_to_name(ret));
        }
    } else {
        LOG_W(TAG, "Config save timer triggered but no save pending");
    }
}

static void schedule_config_save(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    if (!ctx->config_save_timer || !ctx->initialized) {
        return;
    }
    
    ctx->config_save_pending = true;
    xTimerReset(ctx->config_save_timer, pdMS_TO_TICKS(200));
}

relay_mgr_state_t relay_manager_get_mgr_state(void) {
    return s_relay_ctx.state;
}

int relay_manager_read_stable_gpio(gpio_num_t gpio_pin) {
    if (!GPIO_IS_VALID_GPIO(gpio_pin)) {
        return -1;
    }
    
    int fast_sum = 0;
    const int FAST_READINGS = 4;
    const int FAST_DELAY_MS = 5;
    
    for (int i = 0; i < FAST_READINGS; i++) {
        fast_sum += gpio_get_level(gpio_pin);
        vTaskDelay(pdMS_TO_TICKS(FAST_DELAY_MS));
    }
    
    if (fast_sum == 0 || fast_sum == FAST_READINGS) {
        return (fast_sum > 0) ? 1 : 0;
    }
    
    int total_sum = fast_sum;
    const int TOTAL_READINGS = 8;
    
    for (int i = FAST_READINGS; i < TOTAL_READINGS; i++) {
        total_sum += gpio_get_level(gpio_pin);
        vTaskDelay(pdMS_TO_TICKS(FAST_DELAY_MS));
    }
    
    return (total_sum >= (TOTAL_READINGS / 2 + 1)) ? 1 : 0;
}

static esp_err_t save_relay_states_to_nvs(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    relay_state_t current_states[RELAY_MANAGER_MAX_RELAYS];
    int64_t state_timestamps[RELAY_MANAGER_MAX_RELAYS];
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        current_states[i] = ctx->relays[i].current_state;
        state_timestamps[i] = ctx->relays[i].last_change_time;
    }
    
    esp_err_t ret = config_manager_set_blob("relay_states", current_states, sizeof(current_states));
    if (ret != ESP_OK) {
        LOG_E(TAG, "Failed to save relay states: %s", esp_err_to_name(ret));
        return ret;
    }
    
    ret = config_manager_set_blob("relay_timestamps", state_timestamps, sizeof(state_timestamps));
    if (ret != ESP_OK) {
        LOG_E(TAG, "Failed to save relay timestamps: %s", esp_err_to_name(ret));
        return ret;
    }
    
    int64_t system_time = esp_timer_get_time() / 1000;
    ret = config_manager_set_blob("system_shutdown_time", &system_time, sizeof(system_time));
    if (ret != ESP_OK) {
        LOG_W(TAG, "Failed to save system shutdown time: %s", esp_err_to_name(ret));
    }
    
    LOG_I(TAG, "Relay states saved to NVS successfully");
    return ESP_OK;
}

esp_err_t relay_manager_detect_post_restart_changes(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    if (!ctx->initialized || ctx->state != RELAY_MGR_STATE_RUNNING) {
        return ESP_ERR_INVALID_STATE;
    }
    
    LOG_I(TAG, "Detecting post-restart relay changes");
    
    relay_state_t previous_states[RELAY_MANAGER_MAX_RELAYS];
    size_t states_size = sizeof(previous_states);
    
    esp_err_t ret = config_manager_get_blob("relay_states", previous_states, &states_size);
    if (ret != ESP_OK || states_size != sizeof(previous_states)) {
        LOG_W(TAG, "No previous relay states found, assuming first boot");
        return relay_manager_report_initial_states();
    }
    
    int64_t previous_timestamps[RELAY_MANAGER_MAX_RELAYS];
    size_t timestamps_size = sizeof(previous_timestamps);
    
    ret = config_manager_get_blob("relay_timestamps", previous_timestamps, &timestamps_size);
    if (ret != ESP_OK) {
        LOG_W(TAG, "No previous timestamps found");
        memset(previous_timestamps, 0, sizeof(previous_timestamps));
    }
    
    int64_t current_time_ms;
    if (time_manager_is_synchronized()) {
        current_time_ms = (int64_t)time_manager_get_time() * 1000;
    } else {
        current_time_ms = esp_timer_get_time() / 1000;
    }
    
    int changes_detected = 0;
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        if (!relay->is_active || !GPIO_IS_VALID_GPIO(relay->gpio_pin)) {
            continue;
        }
        
        int stable_reading = relay_manager_read_stable_gpio(relay->gpio_pin);
        if (stable_reading < 0) {
            LOG_W(TAG, "Failed to read GPIO %d during post-restart check", relay->gpio_pin);
            continue;
        }
        
        relay_state_t current_physical_state = gpio_to_logical_state(stable_reading, relay->contact_type);
        relay_state_t previous_state = previous_states[i];
        
        if (current_physical_state != previous_state) {
            changes_detected++;
            
            LOG_I(TAG, "POST-RESTART CHANGE DETECTED - Relay %s: %s -> %s (GPIO: %d)", 
                    relay->relay_id,
                    relay_state_to_string(previous_state),
                    relay_state_to_string(current_physical_state),
                    stable_reading);
            
            relay->current_state = current_physical_state;
            relay->last_change_time = current_time_ms;
            relay->last_report_time = current_time_ms;
            
            if (ctx->state_callback) {
                relay_event_t event = {
                    .gpio_pin = relay->gpio_pin,
                    .old_state = previous_state,
                    .new_state = current_physical_state,
                    .timestamp = current_time_ms,
                    .contact_type = relay->contact_type
                };
                
                strncpy(event.relay_id, relay->relay_id, sizeof(event.relay_id) - 1);
                event.relay_id[sizeof(event.relay_id) - 1] = '\0';
                
                strncpy(event.name, relay->name, sizeof(event.name) - 1);
                event.name[sizeof(event.name) - 1] = '\0';
                
                ctx->state_callback(&event, ctx->state_callback_user_data);
                
                vTaskDelay(pdMS_TO_TICKS(100));
            }
        } else {
            relay->current_state = current_physical_state;
            relay->last_change_time = current_time_ms;
        }
    }
    
    if (changes_detected > 0) {
        LOG_I(TAG, "POST-RESTART: Detected %d relay state changes during system downtime", changes_detected);
    } else {
        LOG_I(TAG, "POST-RESTART: No relay state changes detected");
    }
    
    save_relay_states_to_nvs();
    
    return ESP_OK;
}

esp_err_t relay_manager_save_current_states(void) {
    return save_relay_states_to_nvs();
}

esp_err_t relay_manager_load_and_compare_states(void) {
    return relay_manager_detect_post_restart_changes();
}

static esp_err_t init_interrupt_diagnostics(void) {
    if (s_verification_mutex == NULL) {
        s_verification_mutex = xSemaphoreCreateMutex();
        if (s_verification_mutex == NULL) {
            LOG_E(TAG, "Failed to create verification mutex");
            return ESP_ERR_NO_MEM;
        }
    }
    
    s_total_interrupts_received = 0;
    s_interrupts_processed = 0;
    s_last_interrupt_time = 0;
    s_interrupt_system_verified = false;
    s_verification_in_progress = false;
    
    return ESP_OK;
}

static void cleanup_interrupt_diagnostics(void) {
    if (s_verification_mutex != NULL) {
        vSemaphoreDelete(s_verification_mutex);
        s_verification_mutex = NULL;
    }
    
    s_verification_in_progress = false;
}

static esp_err_t verify_interrupt_system_safe(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    LOG_I(TAG, "Verifying GPIO interrupt system (quick check)");
    
    int64_t current_time = esp_timer_get_time() / 1000;
    int64_t time_since_last = current_time - s_last_interrupt_time;
    
    if (s_last_interrupt_time > 0 && time_since_last < 30000) {
        s_interrupt_system_verified = true;
        return ESP_OK;
    }
    
    bool any_gpio_functional = false;
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS && i < 3; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        if (!relay->is_active || !GPIO_IS_VALID_GPIO(relay->gpio_pin)) {
            continue;
        }
        
        int level1 = gpio_get_level(relay->gpio_pin);
        vTaskDelay(pdMS_TO_TICKS(5));
        int level2 = gpio_get_level(relay->gpio_pin);
        
        if (level1 >= 0 && level2 >= 0) {
            any_gpio_functional = true;
            LOG_D(TAG, "GPIO %d functional: level=%d", relay->gpio_pin, level1);
        }
    }
    
    if (any_gpio_functional) {
        s_interrupt_system_verified = true;
        return ESP_OK;
    }
    
    LOG_W(TAG, "GPIO verification failed - may need reinitialization");
    return ESP_FAIL;
}

static esp_err_t reinitialize_gpio_interrupts_safe(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    esp_err_t ret = ESP_OK;
    
    LOG_I(TAG, "Performing safe GPIO interrupt reinitialization");
    debug_relay_states("BEFORE_REPAIR");
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        if (!GPIO_IS_VALID_GPIO(relay->gpio_pin)) {
            continue;
        }
        
        gpio_intr_disable(relay->gpio_pin);
        gpio_isr_handler_remove(relay->gpio_pin);
    }
    
    gpio_uninstall_isr_service();
    
    vTaskDelay(pdMS_TO_TICKS(50));
    
    ret = gpio_install_isr_service(0);
    if (ret != ESP_OK && ret != ESP_ERR_INVALID_STATE) {
        LOG_E(TAG, "Failed to reinstall ISR service: %s", esp_err_to_name(ret));
        return ret;
    }
    
    int configured_count = 0;
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        if (!GPIO_IS_VALID_GPIO(relay->gpio_pin)) {
            continue;
        }
        
        if (relay->is_active) {
            ret = gpio_set_intr_type(relay->gpio_pin, GPIO_INTR_ANYEDGE);
            if (ret != ESP_OK) {
                LOG_W(TAG, "Failed to set interrupt type for GPIO %d", relay->gpio_pin);
                continue;
            }
            
            ret = gpio_isr_handler_add(relay->gpio_pin, gpio_isr_handler, (void*)(uintptr_t)relay->gpio_pin);
            if (ret != ESP_OK) {
                LOG_W(TAG, "Failed to add ISR handler for GPIO %d", relay->gpio_pin);
                continue;
            }
            
            ret = gpio_intr_enable(relay->gpio_pin);
            if (ret != ESP_OK) {
                LOG_W(TAG, "Failed to enable interrupt for GPIO %d", relay->gpio_pin);
                gpio_isr_handler_remove(relay->gpio_pin);
                continue;
            }
            
            configured_count++;
            LOG_I(TAG, "Reconfigured interrupt for active relay %s (GPIO %d)", relay->relay_id, relay->gpio_pin);
        } else {
            ret = gpio_set_intr_type(relay->gpio_pin, GPIO_INTR_DISABLE);
            if (ret == ESP_OK) {
                LOG_D(TAG, "Disabled interrupt for inactive relay %s (GPIO %d)", relay->relay_id, relay->gpio_pin);
            }
        }
        
        if (i < RELAY_MANAGER_MAX_RELAYS - 1) {
            vTaskDelay(pdMS_TO_TICKS(10));
        }
    }
    
    LOG_I(TAG, "Reinitialized %d GPIO interrupts for active relays", configured_count);
    debug_relay_states("AFTER_REPAIR");
    
    if (configured_count > 0) {
        return ESP_OK;
    } else {
        LOG_W(TAG, "No active relays found for interrupt configuration");
        return ESP_FAIL;
    }
}

esp_err_t relay_manager_verify_and_repair_interrupts(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    if (!ctx->initialized || ctx->state != RELAY_MGR_STATE_RUNNING) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (s_verification_mutex == NULL) {
        esp_err_t init_ret = init_interrupt_diagnostics();
        if (init_ret != ESP_OK) {
            return init_ret;
        }
    }
    
    if (xSemaphoreTake(s_verification_mutex, pdMS_TO_TICKS(100)) != pdTRUE) {
        LOG_W(TAG, "Verification already in progress, skipping");
        return ESP_ERR_TIMEOUT;
    }
    
    if (s_verification_in_progress) {
        xSemaphoreGive(s_verification_mutex);
        return ESP_ERR_INVALID_STATE;
    }
    
    s_verification_in_progress = true;
    xSemaphoreGive(s_verification_mutex);
    
    esp_err_t result = ESP_OK;
    
    esp_err_t verify_result = verify_interrupt_system_safe();
    
    if (verify_result == ESP_OK && s_interrupt_system_verified) {
        LOG_D(TAG, "Interrupt system verification passed");
        result = ESP_OK;
    } else {
        LOG_W(TAG, "Interrupt system needs repair");
        
        esp_err_t repair_result = reinitialize_gpio_interrupts_safe();
        
        if (repair_result == ESP_OK) {
            LOG_I(TAG, "Interrupt system repaired successfully");
            s_interrupt_system_verified = true;
            result = ESP_OK;
        } else {
            LOG_E(TAG, "Interrupt system repair failed");
            result = ESP_FAIL;
        }
    }
    
    if (xSemaphoreTake(s_verification_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        s_verification_in_progress = false;
        xSemaphoreGive(s_verification_mutex);
    }
    
    return result;
}

esp_err_t relay_manager_get_interrupt_stats(uint32_t *total_interrupts, uint32_t *processed_interrupts, int64_t *last_interrupt_time) {
    if (total_interrupts) {
        *total_interrupts = s_total_interrupts_received;
    }
    
    if (processed_interrupts) {
        *processed_interrupts = s_interrupts_processed;
    }
    
    if (last_interrupt_time) {
        *last_interrupt_time = s_last_interrupt_time;
    }
    
    return ESP_OK;
}

esp_err_t relay_manager_cleanup_interrupt_diagnostics(void) {
    cleanup_interrupt_diagnostics();
    return ESP_OK;
}

esp_err_t relay_manager_init(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    if (ctx->state != RELAY_MGR_STATE_UNINITIALIZED) {
        LOG_W(TAG, "Already initialized or initializing");
        return ESP_ERR_INVALID_STATE;
    }
    
    ctx->state = RELAY_MGR_STATE_INITIALIZING;
    LOG_I(TAG, "Initializing Relay Manager with %d relays", RELAY_MANAGER_MAX_RELAYS);
    
    memset(ctx->relays, 0, sizeof(ctx->relays));
    ctx->total_events_processed = 0;
    ctx->debounce_filtered_events = 0;
    ctx->mqtt_commands_processed = 0;
    ctx->config_save_pending = false;
    
    ctx->config_mutex = xSemaphoreCreateMutex();
    if (ctx->config_mutex == NULL) {
        LOG_E(TAG, "Failed to create config mutex");
        ctx->state = RELAY_MGR_STATE_UNINITIALIZED;
        return ESP_ERR_NO_MEM;
    }
    
    ctx->pool_mutex = xSemaphoreCreateMutex();
    if (ctx->pool_mutex == NULL) {
        vSemaphoreDelete(ctx->config_mutex);
        ctx->config_mutex = NULL;
        LOG_E(TAG, "Failed to create pool mutex");
        ctx->state = RELAY_MGR_STATE_UNINITIALIZED;
        return ESP_ERR_NO_MEM;
    }
    
    ctx->config_save_timer = xTimerCreate(
        "relay_cfg_save",
        pdMS_TO_TICKS(RELAY_CONFIG_SAVE_DELAY_MS),
        pdFALSE,
        NULL,
        config_save_timer_callback
    );
    
    if (ctx->config_save_timer == NULL) {
        vSemaphoreDelete(ctx->pool_mutex);
        vSemaphoreDelete(ctx->config_mutex);
        ctx->pool_mutex = NULL;
        ctx->config_mutex = NULL;
        LOG_E(TAG, "Failed to create config save timer");
        ctx->state = RELAY_MGR_STATE_UNINITIALIZED;
        return ESP_ERR_NO_MEM;
    }
    
    for (int i = 0; i < 3; i++) {
        ctx->pool_in_use[i] = false;
    }
    
    ctx->gpio_event_queue = xQueueCreate(50, sizeof(gpio_event_t));
    if (ctx->gpio_event_queue == NULL) {
        xTimerDelete(ctx->config_save_timer, portMAX_DELAY);
        vSemaphoreDelete(ctx->pool_mutex);
        vSemaphoreDelete(ctx->config_mutex);
        ctx->config_save_timer = NULL;
        ctx->pool_mutex = NULL;
        ctx->config_mutex = NULL;
        LOG_E(TAG, "Failed to create GPIO event queue");
        ctx->state = RELAY_MGR_STATE_UNINITIALIZED;
        return ESP_ERR_NO_MEM;
    }
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        relay->gpio_pin = DEFAULT_RELAY_PINS[i];
        snprintf(relay->relay_id, sizeof(relay->relay_id), "relay_%d", i + 1);
        strncpy(relay->name, DEFAULT_RELAY_NAMES[i], sizeof(relay->name) - 1);
        relay->name[sizeof(relay->name) - 1] = '\0';
        relay->contact_type = RELAY_CONTACT_NC;
        relay->is_active = DEFAULT_ACTIVE_STATES[i];
        relay->current_state = RELAY_STATE_DISC;
        relay->last_change_time = 0;
        relay->last_report_time = 0;
    }
    
    esp_err_t ret = load_relay_config();
    if (ret != ESP_OK) {
        LOG_W(TAG, "Could not load config from NVS, using defaults");
    }
    
    ret = gpio_install_isr_service(0);
    if (ret != ESP_OK && ret != ESP_ERR_INVALID_STATE) {
        LOG_E(TAG, "Failed to install ISR service: %s", esp_err_to_name(ret));
        goto cleanup;
    }
    LOG_I(TAG, "GPIO ISR service ready");
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        if (!GPIO_IS_VALID_GPIO(relay->gpio_pin)) {
            LOG_E(TAG, "Invalid GPIO pin %d for relay %d", relay->gpio_pin, i);
            continue;
        }
        
        gpio_config_t io_conf = {
            .pin_bit_mask = (1ULL << relay->gpio_pin),
            .mode = GPIO_MODE_INPUT,
            .pull_up_en = GPIO_PULLUP_ENABLE,
            .pull_down_en = GPIO_PULLDOWN_DISABLE,
            .intr_type = GPIO_INTR_DISABLE
        };
        
        ret = gpio_config(&io_conf);
        if (ret != ESP_OK) {
            LOG_E(TAG, "Failed to configure GPIO %d: %s", relay->gpio_pin, esp_err_to_name(ret));
            continue;
        }
        
        gpio_pullup_en(relay->gpio_pin);
        gpio_pulldown_dis(relay->gpio_pin);
        
        vTaskDelay(pdMS_TO_TICKS(100));
        
        int stable_reading = relay_manager_read_stable_gpio(relay->gpio_pin);
        if (stable_reading >= 0) {
            relay->current_state = gpio_to_logical_state(stable_reading, relay->contact_type);
            relay->last_change_time = esp_timer_get_time();
            
            LOG_I(TAG, "Relay %s (GPIO %d): %s, Type: %s, Active: %s, Initial: %s (GPIO=%d)", 
                    relay->relay_id, relay->gpio_pin, relay->name,
                    contact_type_to_string(relay->contact_type),
                    relay->is_active ? "YES" : "NO",
                    relay_state_to_string(relay->current_state),
                    stable_reading);
        } else {
            LOG_E(TAG, "Failed to read stable state for GPIO %d", relay->gpio_pin);
            relay->current_state = RELAY_STATE_ERROR;
        }
    }
    
    ctx->initialized = true;
    ctx->state = RELAY_MGR_STATE_RUNNING;
    
    BaseType_t task_ret = xTaskCreate(
        relay_event_task,
        "relay_events",
        8192,
        NULL,
        4,
        &ctx->event_task_handle
    );
    
    if (task_ret != pdPASS) {
        LOG_E(TAG, "Failed to create relay event task");
        ret = ESP_FAIL;
        goto cleanup;
    }
    
    LOG_I(TAG, "Relay event task created with 8KB stack");
    
    vTaskDelay(pdMS_TO_TICKS(300));
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        if (!GPIO_IS_VALID_GPIO(relay->gpio_pin)) {
            continue;
        }
        
        ret = gpio_set_intr_type(relay->gpio_pin, GPIO_INTR_ANYEDGE);
        if (ret != ESP_OK) {
            LOG_W(TAG, "Failed to set interrupt type for GPIO %d", relay->gpio_pin);
            continue;
        }
        
        ret = gpio_isr_handler_add(relay->gpio_pin, gpio_isr_handler, (void*)(uintptr_t)relay->gpio_pin);
        if (ret != ESP_OK) {
            LOG_W(TAG, "Failed to add ISR for GPIO %d", relay->gpio_pin);
            continue;
        }
        
        ret = gpio_intr_enable(relay->gpio_pin);
        if (ret != ESP_OK) {
            LOG_W(TAG, "Failed to enable interrupt for GPIO %d", relay->gpio_pin);
            continue;
        }
    }
    
    vTaskDelay(pdMS_TO_TICKS(100));
    
    LOG_I(TAG, "Relay Manager initialized successfully");
    
    esp_err_t diag_init = init_interrupt_diagnostics();
    if (diag_init != ESP_OK) {
        LOG_W(TAG, "Failed to initialize interrupt diagnostics");
    }
    
    return ESP_OK;
    
cleanup:
    ctx->state = RELAY_MGR_STATE_UNINITIALIZED;
    ctx->initialized = false;
    
    if (ctx->event_task_handle) {
        vTaskDelete(ctx->event_task_handle);
        ctx->event_task_handle = NULL;
    }
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        if (GPIO_IS_VALID_GPIO(DEFAULT_RELAY_PINS[i])) {
            gpio_intr_disable(DEFAULT_RELAY_PINS[i]);
            gpio_isr_handler_remove(DEFAULT_RELAY_PINS[i]);
        }
    }
    
    if (ctx->gpio_event_queue) {
        vQueueDelete(ctx->gpio_event_queue);
        ctx->gpio_event_queue = NULL;
    }
    
    if (ctx->config_save_timer) {
        xTimerDelete(ctx->config_save_timer, portMAX_DELAY);
        ctx->config_save_timer = NULL;
    }
    
    if (ctx->pool_mutex) {
        vSemaphoreDelete(ctx->pool_mutex);
        ctx->pool_mutex = NULL;
    }
    
    if (ctx->config_mutex) {
        vSemaphoreDelete(ctx->config_mutex);
        ctx->config_mutex = NULL;
    }
    
    return ret;
}

static void IRAM_ATTR gpio_isr_handler(void *arg) {
    gpio_num_t gpio_pin = (gpio_num_t)(uintptr_t)arg;
    
    s_total_interrupts_received++;
    s_last_interrupt_time = esp_timer_get_time() / 1000;
    
    if (!s_relay_ctx.gpio_event_queue) {
        return;
    }
    
    gpio_event_t event = { .gpio_pin = gpio_pin };
    
    BaseType_t higher_priority_task_woken = pdFALSE;
    BaseType_t result = xQueueSendFromISR(s_relay_ctx.gpio_event_queue, &event, &higher_priority_task_woken);
    
    if (result != pdTRUE) {
        gpio_event_t dummy;
        if (xQueueReceiveFromISR(s_relay_ctx.gpio_event_queue, &dummy, &higher_priority_task_woken) == pdTRUE) {
            xQueueSendFromISR(s_relay_ctx.gpio_event_queue, &event, &higher_priority_task_woken);
        }
    }
    
    if (higher_priority_task_woken == pdTRUE) {
        portYIELD_FROM_ISR();
    }
}

esp_err_t relay_manager_report_initial_states(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    if (!ctx->initialized || ctx->state != RELAY_MGR_STATE_RUNNING) {
        return ESP_ERR_INVALID_STATE;
    }
    
    LOG_I(TAG, "Reporting initial relay states after configuration");
    
    int64_t real_timestamp_ms;
    if (time_manager_is_synchronized()) {
        real_timestamp_ms = (int64_t)time_manager_get_time() * 1000;
    } else {
        real_timestamp_ms = esp_timer_get_time() / 1000;
    }
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        if (!relay->is_active || !GPIO_IS_VALID_GPIO(relay->gpio_pin)) {
            continue;
        }
        
        int stable_reading = relay_manager_read_stable_gpio(relay->gpio_pin);
        if (stable_reading >= 0) {
            relay_state_t current_state = gpio_to_logical_state(stable_reading, relay->contact_type);
            relay->current_state = current_state;
            relay->last_change_time = real_timestamp_ms;
            relay->last_report_time = real_timestamp_ms;
            
            LOG_I(TAG, "Initial state report - Relay %s: %s (GPIO: %d)", 
                    relay->relay_id,
                    relay_state_to_string(current_state),
                    stable_reading);
            
            if (ctx->state_callback) {
                relay_event_t event = {
                    .gpio_pin = relay->gpio_pin,
                    .old_state = RELAY_STATE_DISC,
                    .new_state = current_state,
                    .timestamp = real_timestamp_ms,
                    .contact_type = relay->contact_type
                };
                
                strncpy(event.relay_id, relay->relay_id, sizeof(event.relay_id) - 1);
                event.relay_id[sizeof(event.relay_id) - 1] = '\0';
                
                strncpy(event.name, relay->name, sizeof(event.name) - 1);
                event.name[sizeof(event.name) - 1] = '\0';
                
                ctx->state_callback(&event, ctx->state_callback_user_data);
            }
            
            vTaskDelay(pdMS_TO_TICKS(50));
        }
    }
    
    LOG_I(TAG, "Initial relay states reported");
    return ESP_OK;
}

static void relay_event_task(void *pvParameters) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    gpio_event_t gpio_event;
    uint32_t idle_cycles = 0;
    int64_t last_auto_check = 0;
    int64_t last_interrupt_check = 0;
    
    LOG_I(TAG, "Relay event task started");
    
    vTaskDelay(pdMS_TO_TICKS(200));
    
    while (1) {
        if (ctx->state != RELAY_MGR_STATE_RUNNING) {
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }
        
        int64_t current_time = esp_timer_get_time();
        
        if ((current_time - last_interrupt_check) > 60000000) {
            last_interrupt_check = current_time;
            
            uint32_t total_interrupts = 0;
            int64_t last_interrupt_time = 0;
            relay_manager_get_interrupt_stats(&total_interrupts, NULL, &last_interrupt_time);
            
            int64_t time_since_last_interrupt = (current_time / 1000) - last_interrupt_time;
            if (last_interrupt_time > 0 && time_since_last_interrupt > 120000) {
                LOG_W(TAG, "No interrupts in %lld ms - performing FORCE reconfiguration", time_since_last_interrupt);
                
                esp_err_t force_result = relay_manager_force_reconfigure_interrupts();
                if (force_result == ESP_OK) {
                    LOG_I(TAG, "Force reconfiguration successful");
                } else {
                    LOG_E(TAG, "Force reconfiguration failed");
                }
            }
        }
        
        if ((current_time - last_auto_check) > (RELAY_MANAGER_AUTO_CHECK_INTERVAL_MS * 1000)) {
            last_auto_check = current_time;
            
            for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
                relay_config_t *relay = &ctx->relays[i];
                
                if (!relay->is_active || !GPIO_IS_VALID_GPIO(relay->gpio_pin)) {
                    continue;
                }
                
                int current_gpio = gpio_get_level(relay->gpio_pin);
                relay_state_t expected_state = gpio_to_logical_state(current_gpio, relay->contact_type);
                
                if (expected_state != relay->current_state) {
                    int stable_reading = relay_manager_read_stable_gpio(relay->gpio_pin);
                    if (stable_reading >= 0) {
                        relay_state_t confirmed_state = gpio_to_logical_state(stable_reading, relay->contact_type);
                        
                        if (confirmed_state != relay->current_state) {
                            relay_state_t old_state = relay->current_state;
                            relay->current_state = confirmed_state;
                            
                            int64_t real_timestamp_ms;
                            if (time_manager_is_synchronized()) {
                                real_timestamp_ms = (int64_t)time_manager_get_time() * 1000;
                            } else {
                                real_timestamp_ms = esp_timer_get_time() / 1000;
                            }
                            
                            relay->last_change_time = real_timestamp_ms;
                            relay->last_report_time = real_timestamp_ms;
                            
                            if (ctx->state_callback) {
                                relay_event_t event = {
                                    .gpio_pin = relay->gpio_pin,
                                    .old_state = old_state,
                                    .new_state = confirmed_state,
                                    .timestamp = real_timestamp_ms,
                                    .contact_type = relay->contact_type
                                };
                                
                                strncpy(event.relay_id, relay->relay_id, sizeof(event.relay_id) - 1);
                                event.relay_id[sizeof(event.relay_id) - 1] = '\0';
                                
                                strncpy(event.name, relay->name, sizeof(event.name) - 1);
                                event.name[sizeof(event.name) - 1] = '\0';
                                
                                ctx->state_callback(&event, ctx->state_callback_user_data);
                            }
                        }
                    }
                }
            }
        }
        
        if (xQueueReceive(ctx->gpio_event_queue, &gpio_event, pdMS_TO_TICKS(200)) == pdTRUE) {
            idle_cycles = 0;
            ctx->total_events_processed++;
            s_interrupts_processed++;
            
            if (ctx->state != RELAY_MGR_STATE_RUNNING || !ctx->initialized) {
                continue;
            }
            
            int relay_index = find_relay_index_by_gpio(gpio_event.gpio_pin);
            if (relay_index < 0) {
                continue;
            }
            
            relay_config_t *relay = &ctx->relays[relay_index];
            
            if (!relay->is_active) {
                continue;
            }
            
            int64_t current_time = esp_timer_get_time();
            int64_t time_since_last = current_time - relay->last_change_time;
            if (time_since_last < (RELAY_MANAGER_DEBOUNCE_TIME_MS * 1000)) {
                ctx->debounce_filtered_events++;
                continue;
            }
            
            vTaskDelay(pdMS_TO_TICKS(3));
            int quick_reading = gpio_get_level(gpio_event.gpio_pin);
            relay_state_t quick_state = gpio_to_logical_state(quick_reading, relay->contact_type);
            
            if (quick_state == relay->current_state) {
                continue;
            }
            
            int stable_reading = relay_manager_read_stable_gpio(gpio_event.gpio_pin);
            if (stable_reading < 0) {
                relay->current_state = RELAY_STATE_ERROR;
                
                gpio_intr_disable(gpio_event.gpio_pin);
                gpio_isr_handler_remove(gpio_event.gpio_pin);
                gpio_reset_pin(gpio_event.gpio_pin);
                vTaskDelay(pdMS_TO_TICKS(50));
                
                gpio_config_t io_conf = {
                    .pin_bit_mask = (1ULL << gpio_event.gpio_pin),
                    .mode = GPIO_MODE_INPUT,
                    .pull_up_en = GPIO_PULLUP_ENABLE,
                    .pull_down_en = GPIO_PULLDOWN_DISABLE,
                    .intr_type = GPIO_INTR_ANYEDGE
                };
                esp_err_t gpio_ret = gpio_config(&io_conf);
                
                if (gpio_ret == ESP_OK) {
                    esp_err_t isr_ret = gpio_isr_handler_add(gpio_event.gpio_pin, gpio_isr_handler, 
                                        (void*)(uintptr_t)gpio_event.gpio_pin);
                    if (isr_ret == ESP_OK) {
                        gpio_intr_enable(gpio_event.gpio_pin);
                        LOG_W(TAG, "GPIO %d re-initialized after read failure", gpio_event.gpio_pin);
                    } else {
                        LOG_E(TAG, "Failed to re-add ISR for GPIO %d: %s", gpio_event.gpio_pin, esp_err_to_name(isr_ret));
                    }
                } else {
                    LOG_E(TAG, "Failed to reconfigure GPIO %d: %s", gpio_event.gpio_pin, esp_err_to_name(gpio_ret));
                }
                continue;
            }
            
            relay_state_t new_state = gpio_to_logical_state(stable_reading, relay->contact_type);
            
            if (new_state == relay->current_state) {
                continue;
            }
            
            relay_state_t old_state = relay->current_state;
            relay->current_state = new_state;
            
            int64_t real_timestamp_ms;
            if (time_manager_is_synchronized()) {
                real_timestamp_ms = (int64_t)time_manager_get_time() * 1000;
            } else {
                real_timestamp_ms = esp_timer_get_time() / 1000;
            }
            
            relay->last_change_time = real_timestamp_ms;
            relay->last_report_time = real_timestamp_ms;
            
            LOG_I(TAG, "Relay %s state change: %s -> %s (stable: %d)", 
                    relay->relay_id,
                    relay_state_to_string(old_state),
                    relay_state_to_string(new_state),
                    stable_reading);
            
            if (ctx->state_callback) {
                relay_event_t event = {
                    .gpio_pin = relay->gpio_pin,
                    .old_state = old_state,
                    .new_state = new_state,
                    .timestamp = real_timestamp_ms,
                    .contact_type = relay->contact_type
                };
                
                strncpy(event.relay_id, relay->relay_id, sizeof(event.relay_id) - 1);
                event.relay_id[sizeof(event.relay_id) - 1] = '\0';
                
                strncpy(event.name, relay->name, sizeof(event.name) - 1);
                event.name[sizeof(event.name) - 1] = '\0';
                
                ctx->state_callback(&event, ctx->state_callback_user_data);
                
                save_relay_states_to_nvs();
            }
        } else {
            idle_cycles++;
        }
    }
    
    LOG_W(TAG, "Relay event task exiting");
    ctx->event_task_handle = NULL;
    vTaskDelete(NULL);
}

static relay_state_t gpio_to_logical_state(int gpio_level, relay_contact_type_t contact_type) {
    if (contact_type == RELAY_CONTACT_NC) {
        return (gpio_level == 0) ? RELAY_STATE_OK : RELAY_STATE_DISC;
    } else {
        return (gpio_level == 1) ? RELAY_STATE_OK : RELAY_STATE_DISC;
    }
}

esp_err_t relay_manager_set_active(const char *relay_id, bool active) {
    if (!relay_id) {
        return ESP_ERR_INVALID_ARG;
    }
    
    relay_manager_context_t *ctx = &s_relay_ctx;
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    int index = find_relay_index_by_id(relay_id);
    if (index < 0) {
        return ESP_ERR_NOT_FOUND;
    }
    
    if (xSemaphoreTake(ctx->config_mutex, portMAX_DELAY) == pdTRUE) {
        relay_config_t *relay = &ctx->relays[index];
        
        relay->is_active = active;
        
        LOG_I(TAG, "Setting relay %s active=%s, scheduling config save", 
              relay_id, active ? "true" : "false");
        
        schedule_config_save();
        
        if (active && GPIO_IS_VALID_GPIO(relay->gpio_pin)) {
            LOG_I(TAG, "Re-reading state for newly activated relay %s", relay_id);
            
            gpio_intr_disable(relay->gpio_pin);
            vTaskDelay(pdMS_TO_TICKS(100));
            
            int stable_reading = relay_manager_read_stable_gpio(relay->gpio_pin);
            if (stable_reading >= 0) {
                relay_state_t new_state = gpio_to_logical_state(stable_reading, relay->contact_type);
                relay->current_state = new_state;
                
                int64_t real_timestamp_ms;
                if (time_manager_is_synchronized()) {
                    real_timestamp_ms = (int64_t)time_manager_get_time() * 1000;
                } else {
                    real_timestamp_ms = esp_timer_get_time() / 1000;
                }
                
                relay->last_change_time = real_timestamp_ms;
                relay->last_report_time = real_timestamp_ms;
                
                LOG_I(TAG, "Relay %s activation read: GPIO=%d, State=%s", 
                        relay_id, stable_reading, relay_state_to_string(relay->current_state));
                
                gpio_isr_handler_remove(relay->gpio_pin);
                esp_err_t isr_ret = gpio_isr_handler_add(relay->gpio_pin, gpio_isr_handler, 
                                                         (void*)(uintptr_t)relay->gpio_pin);
                if (isr_ret != ESP_OK) {
                    LOG_E(TAG, "Failed to reinstall ISR for %s: %s", relay_id, esp_err_to_name(isr_ret));
                } else {
                    gpio_intr_enable(relay->gpio_pin);
                }
                
                vTaskDelay(pdMS_TO_TICKS(100));
            }
        }
        
        xSemaphoreGive(ctx->config_mutex);
        
        LOG_I(TAG, "Relay %s %s", relay_id, active ? "ACTIVATED" : "DEACTIVATED");
        
        if (active) {
            debug_relay_states("AFTER_ACTIVATION");
        }
        
        return ESP_OK;
    }
    
    return ESP_FAIL;
}

esp_err_t relay_manager_set_contact_type(const char *relay_id, relay_contact_type_t contact_type) {
    if (!relay_id) {
        return ESP_ERR_INVALID_ARG;
    }
    
    relay_manager_context_t *ctx = &s_relay_ctx;
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    int index = find_relay_index_by_id(relay_id);
    if (index < 0) {
        return ESP_ERR_NOT_FOUND;
    }
    
    if (xSemaphoreTake(ctx->config_mutex, portMAX_DELAY) == pdTRUE) {
        relay_config_t *relay = &ctx->relays[index];
        relay_state_t old_state = relay->current_state;
        relay->contact_type = contact_type;
        
        if (GPIO_IS_VALID_GPIO(relay->gpio_pin)) {
            if (relay->is_active) {
                gpio_intr_disable(relay->gpio_pin);
                vTaskDelay(pdMS_TO_TICKS(20));
            }
            
            int stable_reading = relay_manager_read_stable_gpio(relay->gpio_pin);
            if (stable_reading >= 0) {
                relay_state_t new_state = gpio_to_logical_state(stable_reading, contact_type);
                relay->current_state = new_state;
                
                if (relay->is_active) {
                    int64_t real_timestamp_ms;
                    if (time_manager_is_synchronized()) {
                        real_timestamp_ms = (int64_t)time_manager_get_time() * 1000;
                    } else {
                        real_timestamp_ms = esp_timer_get_time() / 1000;
                    }
                    
                    relay->last_change_time = real_timestamp_ms;
                    relay->last_report_time = real_timestamp_ms;
                    
                    gpio_isr_handler_remove(relay->gpio_pin);
                    esp_err_t isr_ret = gpio_isr_handler_add(relay->gpio_pin, gpio_isr_handler, 
                                                             (void*)(uintptr_t)relay->gpio_pin);
                    if (isr_ret != ESP_OK) {
                        LOG_E(TAG, "Failed to reinstall ISR for %s: %s", relay_id, esp_err_to_name(isr_ret));
                    } else {
                        gpio_intr_enable(relay->gpio_pin);
                    }
                    
                    vTaskDelay(pdMS_TO_TICKS(30));
                    
                    if (new_state != old_state && ctx->state_callback) {
                        relay_event_t event = {
                            .gpio_pin = relay->gpio_pin,
                            .old_state = old_state,
                            .new_state = new_state,
                            .timestamp = real_timestamp_ms,
                            .contact_type = relay->contact_type
                        };
                        
                        strncpy(event.relay_id, relay->relay_id, sizeof(event.relay_id) - 1);
                        event.relay_id[sizeof(event.relay_id) - 1] = '\0';
                        
                        strncpy(event.name, relay->name, sizeof(event.name) - 1);
                        event.name[sizeof(event.name) - 1] = '\0';
                        
                        ctx->state_callback(&event, ctx->state_callback_user_data);
                    }
                }
            }
        }
        
        schedule_config_save();
        xSemaphoreGive(ctx->config_mutex);
        
        LOG_I(TAG, "Relay %s contact type changed to: %s", relay_id, contact_type_to_string(contact_type));
        return ESP_OK;
    }
    
    return ESP_FAIL;
}

esp_err_t relay_manager_verify_interrupts_after_config(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    if (!ctx->initialized || ctx->state != RELAY_MGR_STATE_RUNNING) {
        return ESP_ERR_INVALID_STATE;
    }
    
    LOG_I(TAG, "Verifying GPIO interrupts after configuration");
    
    int active_relays = 0;
    int interrupts_ok = 0;
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        if (!relay->is_active || !GPIO_IS_VALID_GPIO(relay->gpio_pin)) {
            continue;
        }
        
        active_relays++;
        
        esp_err_t ret = gpio_isr_handler_add(relay->gpio_pin, gpio_isr_handler, (void*)(uintptr_t)relay->gpio_pin);
        if (ret == ESP_OK) {
            interrupts_ok++;
            gpio_intr_enable(relay->gpio_pin);
            LOG_D(TAG, "Interrupt verified for relay %s (GPIO %d)", relay->relay_id, relay->gpio_pin);
        } else if (ret == ESP_ERR_INVALID_STATE) {
            interrupts_ok++;
            LOG_D(TAG, "Interrupt already configured for relay %s (GPIO %d)", relay->relay_id, relay->gpio_pin);
        } else {
            LOG_W(TAG, "Failed to verify interrupt for relay %s (GPIO %d): %s", 
                  relay->relay_id, relay->gpio_pin, esp_err_to_name(ret));
        }
        
        vTaskDelay(pdMS_TO_TICKS(5));
    }
    
    LOG_I(TAG, "Interrupt verification: %d/%d active relays have working interrupts", interrupts_ok, active_relays);
    
    if (interrupts_ok == active_relays && active_relays > 0) {
        return ESP_OK;
    } else if (active_relays == 0) {
        LOG_W(TAG, "No active relays found");
        return ESP_FAIL;
    } else {
        LOG_W(TAG, "Some interrupts could not be verified");
        return ESP_FAIL;
    }
}

esp_err_t relay_manager_verify_all_states(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    if (!ctx->initialized || ctx->state != RELAY_MGR_STATE_RUNNING) {
        return ESP_ERR_INVALID_STATE;
    }
    
    LOG_I(TAG, "Verifying all relay states for consistency");
    
    int corrections_made = 0;
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        if (!relay->is_active || !GPIO_IS_VALID_GPIO(relay->gpio_pin)) {
            continue;
        }
        
        int stable_reading = relay_manager_read_stable_gpio(relay->gpio_pin);
        if (stable_reading < 0) {
            LOG_W(TAG, "Cannot read GPIO %d during verification", relay->gpio_pin);
            continue;
        }
        
        relay_state_t expected_state = gpio_to_logical_state(stable_reading, relay->contact_type);
        
        if (expected_state != relay->current_state) {
            LOG_W(TAG, "State inconsistency detected for %s: expected %s, actual %s (GPIO=%d)", 
                    relay->relay_id, 
                    relay_state_to_string(expected_state),
                    relay_state_to_string(relay->current_state),
                    stable_reading);
            
            relay_state_t old_state = relay->current_state;
            relay->current_state = expected_state;
            
            int64_t real_timestamp_ms;
            if (time_manager_is_synchronized()) {
                real_timestamp_ms = (int64_t)time_manager_get_time() * 1000;
            } else {
                real_timestamp_ms = esp_timer_get_time() / 1000;
            }
            
            relay->last_change_time = real_timestamp_ms;
            relay->last_report_time = real_timestamp_ms;
            
            if (ctx->state_callback) {
                relay_event_t event = {
                    .gpio_pin = relay->gpio_pin,
                    .old_state = old_state,
                    .new_state = expected_state,
                    .timestamp = real_timestamp_ms,
                    .contact_type = relay->contact_type
                };
                
                strncpy(event.relay_id, relay->relay_id, sizeof(event.relay_id) - 1);
                event.relay_id[sizeof(event.relay_id) - 1] = '\0';
                
                strncpy(event.name, relay->name, sizeof(event.name) - 1);
                event.name[sizeof(event.name) - 1] = '\0';
                
                ctx->state_callback(&event, ctx->state_callback_user_data);
            }
            
            corrections_made++;
        }
    }
    
    if (corrections_made > 0) {
        LOG_I(TAG, "Made %d state corrections", corrections_made);
    } else {
        LOG_I(TAG, "All relay states are consistent");
    }
    
    return ESP_OK;
}

static esp_err_t load_relay_config(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    esp_err_t ret;
    
    LOG_I(TAG, "=== LOADING RELAY CONFIG FROM NVS ===");
    
    size_t active_size = sizeof(bool) * RELAY_MANAGER_MAX_RELAYS;
    bool active_states[RELAY_MANAGER_MAX_RELAYS];
    size_t actual_size = active_size;
    
    ret = config_manager_get_blob("relay_active", active_states, &actual_size);
    if (ret == ESP_OK && actual_size == active_size) {
        int active_count = 0;
        for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
            ctx->relays[i].is_active = active_states[i];
            if (active_states[i]) {
                active_count++;
                LOG_I(TAG, "Loaded: Relay %s = ACTIVE", ctx->relays[i].relay_id);
            }
        }
        LOG_I(TAG, "Loaded %d active relays from NVS", active_count);
    } else {
        LOG_W(TAG, "Failed to load active states from NVS: %s (size: %zu/%zu)", 
              esp_err_to_name(ret), actual_size, active_size);
        LOG_W(TAG, "All relays will remain INACTIVE (default state)");
    }
    
    char names_key[32];
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        snprintf(names_key, sizeof(names_key), "relay_name_%d", i);
        char name_buffer[RELAY_MANAGER_NAME_MAX_LENGTH];
        
        ret = config_manager_get_str(names_key, name_buffer, sizeof(name_buffer));
        if (ret == ESP_OK) {
            strncpy(ctx->relays[i].name, name_buffer, sizeof(ctx->relays[i].name) - 1);
            ctx->relays[i].name[sizeof(ctx->relays[i].name) - 1] = '\0';
        }
    }
    
    size_t types_size = sizeof(relay_contact_type_t) * RELAY_MANAGER_MAX_RELAYS;
    relay_contact_type_t contact_types[RELAY_MANAGER_MAX_RELAYS];
    actual_size = types_size;
    
    ret = config_manager_get_blob("relay_types", contact_types, &actual_size);
    if (ret == ESP_OK && actual_size == types_size) {
        for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
            ctx->relays[i].contact_type = contact_types[i];
        }
        LOG_I(TAG, "Loaded contact types from NVS");
    } else {
        LOG_W(TAG, "Failed to load contact types from NVS: %s", esp_err_to_name(ret));
    }
    
    LOG_I(TAG, "=== RELAY CONFIG LOAD COMPLETED ===");
    return ESP_OK;
}

static esp_err_t save_relay_config_immediate(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    esp_err_t ret;
    
    LOG_I(TAG, "Saving relay config to NVS...");
    
    bool any_active = false;
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        if (ctx->relays[i].is_active) {
            LOG_I(TAG, "Saving: Relay %s = ACTIVE", ctx->relays[i].relay_id);
            any_active = true;
        }
    }
    
    if (!any_active) {
        LOG_W(TAG, "Warning: No active relays to save!");
    }
    
    bool active_states[RELAY_MANAGER_MAX_RELAYS];
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        active_states[i] = ctx->relays[i].is_active;
    }
    
    ret = config_manager_set_blob("relay_active", active_states, sizeof(active_states));
    if (ret != ESP_OK) {
        LOG_E(TAG, "Failed to save active states: %s", esp_err_to_name(ret));
        return ret;
    }
    
    char names_key[32];
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        snprintf(names_key, sizeof(names_key), "relay_name_%d", i);
        ret = config_manager_set_str(names_key, ctx->relays[i].name);
        if (ret != ESP_OK) {
            LOG_W(TAG, "Failed to save name for relay %d", i);
        }
    }
    
    relay_contact_type_t contact_types[RELAY_MANAGER_MAX_RELAYS];
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        contact_types[i] = ctx->relays[i].contact_type;
    }
    
    ret = config_manager_set_blob("relay_types", contact_types, sizeof(contact_types));
    if (ret != ESP_OK) {
        LOG_E(TAG, "Failed to save contact types: %s", esp_err_to_name(ret));
        return ret;
    }
    
    LOG_I(TAG, "Relay configuration saved successfully");
    return ESP_OK;
}

esp_err_t relay_manager_set_state_callback(relay_state_change_callback_t callback, void *user_data) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (xSemaphoreTake(ctx->config_mutex, portMAX_DELAY) == pdTRUE) {
        ctx->state_callback = callback;
        ctx->state_callback_user_data = user_data;
        xSemaphoreGive(ctx->config_mutex);
    }
    
    return ESP_OK;
}

esp_err_t relay_manager_set_mqtt_callback(relay_mqtt_command_callback_t callback, void *user_data) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (xSemaphoreTake(ctx->config_mutex, portMAX_DELAY) == pdTRUE) {
        ctx->mqtt_callback = callback;
        ctx->mqtt_callback_user_data = user_data;
        xSemaphoreGive(ctx->config_mutex);
    }
    
    return ESP_OK;
}

esp_err_t relay_manager_get_state(const char *relay_id, relay_state_t *state) {
    if (!relay_id || !state) {
        return ESP_ERR_INVALID_ARG;
    }
    
    relay_manager_context_t *ctx = &s_relay_ctx;
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    int index = find_relay_index_by_id(relay_id);
    if (index < 0) {
        return ESP_ERR_NOT_FOUND;
    }
    
    *state = ctx->relays[index].current_state;
    return ESP_OK;
}

esp_err_t relay_manager_get_all_states_json(char *json_buffer, size_t buffer_size) {
    if (!json_buffer || buffer_size == 0) {
        return ESP_ERR_INVALID_ARG;
    }
    
    relay_manager_context_t *ctx = &s_relay_ctx;
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    char *buffer = get_message_buffer();
    if (!buffer) {
        return ESP_ERR_NO_MEM;
    }
    
    int pos = 0;
    pos += snprintf(buffer + pos, 256 - pos, "{");
    
    bool first = true;
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        if (!relay->is_active) {
            continue;
        }
        
        if (!first) {
            pos += snprintf(buffer + pos, 256 - pos, ",");
        }
        first = false;
        
        pos += snprintf(buffer + pos, 256 - pos,
                       "\"%s\":{\"name\":\"%s\",\"status\":\"%s\",\"pin\":%d,\"contact_type\":\"%s\",\"timestamp\":{\"value\":%lld,\"type\":\"realtime\"}}",
                       relay->relay_id, relay->name, relay_state_to_string(relay->current_state),
                       relay->gpio_pin, contact_type_to_string(relay->contact_type),
                       relay->last_change_time / 1000);
    }
    
    pos += snprintf(buffer + pos, 256 - pos, "}");
    
    if (pos < buffer_size) {
        strcpy(json_buffer, buffer);
        release_message_buffer(buffer);
        return ESP_OK;
    } else {
        release_message_buffer(buffer);
        return ESP_ERR_INVALID_SIZE;
    }
}

esp_err_t relay_manager_set_name(const char *relay_id, const char *name) {
    if (!relay_id || !name) {
        return ESP_ERR_INVALID_ARG;
    }
    
    relay_manager_context_t *ctx = &s_relay_ctx;
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    int index = find_relay_index_by_id(relay_id);
    if (index < 0) {
        return ESP_ERR_NOT_FOUND;
    }
    
    if (xSemaphoreTake(ctx->config_mutex, portMAX_DELAY) == pdTRUE) {
        strncpy(ctx->relays[index].name, name, sizeof(ctx->relays[index].name) - 1);
        ctx->relays[index].name[sizeof(ctx->relays[index].name) - 1] = '\0';
        
        schedule_config_save();
        xSemaphoreGive(ctx->config_mutex);
        
        LOG_I(TAG, "Relay %s name changed to: %s", relay_id, name);
        return ESP_OK;
    }
    
    return ESP_FAIL;
}

static char* parse_simple_value(const char *data, const char *key) {
    static char value_buffer[64];
    char search_key[32];
    
    snprintf(search_key, sizeof(search_key), "%s=", key);
    
    char *start = strstr(data, search_key);
    if (!start) {
        return NULL;
    }
    
    start += strlen(search_key);
    char *end = strchr(start, '&');
    if (!end) {
        end = start + strlen(start);
    }
    
    size_t len = end - start;
    if (len >= sizeof(value_buffer)) {
        len = sizeof(value_buffer) - 1;
    }
    
    memcpy(value_buffer, start, len);
    value_buffer[len] = '\0';
    
    return value_buffer;
}

esp_err_t relay_manager_process_mqtt_command(const char *command_data) {
    if (!command_data) {
        return ESP_ERR_INVALID_ARG;
    }
    
    relay_manager_context_t *ctx = &s_relay_ctx;
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    ctx->mqtt_commands_processed++;
    
    char *command = parse_simple_value(command_data, "command");
    if (!command) {
        LOG_E(TAG, "No command found in data");
        return ESP_ERR_INVALID_ARG;
    }
    
    LOG_I(TAG, "Processing MQTT command: %s", command);
    
    esp_err_t ret = ESP_FAIL;
    
    if (strcmp(command, "set_name") == 0) {
        char *relay_id = parse_simple_value(command_data, "relay_id");
        char *name = parse_simple_value(command_data, "name");
        
        if (relay_id && name) {
            ret = relay_manager_set_name(relay_id, name);
        }
    }
    else if (strcmp(command, "set_active") == 0) {
        char *relay_id = parse_simple_value(command_data, "relay_id");
        char *active_str = parse_simple_value(command_data, "active");
        
        if (relay_id && active_str) {
            bool active = (strcmp(active_str, "true") == 0 || strcmp(active_str, "1") == 0);
            ret = relay_manager_set_active(relay_id, active);
        }
    }
    else if (strcmp(command, "set_contact_type") == 0) {
        char *relay_id = parse_simple_value(command_data, "relay_id");
        char *contact_type_str = parse_simple_value(command_data, "contact_type");
        
        if (relay_id && contact_type_str) {
            relay_contact_type_t type = string_to_contact_type(contact_type_str);
            ret = relay_manager_set_contact_type(relay_id, type);
        }
    }
    else if (strcmp(command, "get_config") == 0) {
        ret = ESP_OK;
    }
    else {
        LOG_W(TAG, "Unknown command: %s", command);
        ret = ESP_ERR_NOT_SUPPORTED;
    }
    
    if (ctx->mqtt_callback) {
        ctx->mqtt_callback("relay_config", command_data, ctx->mqtt_callback_user_data);
    }
    
    return ret;
}

esp_err_t relay_manager_check_all_states(bool force_report) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    int64_t real_timestamp_ms;
    if (time_manager_is_synchronized()) {
        real_timestamp_ms = (int64_t)time_manager_get_time() * 1000;
    } else {
        real_timestamp_ms = esp_timer_get_time() / 1000;
    }
    
    int state_changes = 0;
    
    LOG_I(TAG, "Manual check starting (force_report=%s)", force_report ? "true" : "false");
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        if (!relay->is_active) {
            continue;
        }
        
        if (!GPIO_IS_VALID_GPIO(relay->gpio_pin)) {
            continue;
        }
        
        int stable_reading = relay_manager_read_stable_gpio(relay->gpio_pin);
        if (stable_reading < 0) {
            LOG_W(TAG, "Failed to read GPIO %d during manual check", relay->gpio_pin);
            continue;
        }
        
        relay_state_t current_state = gpio_to_logical_state(stable_reading, relay->contact_type);
        
        if (current_state != relay->current_state) {
            relay_state_t old_state = relay->current_state;
            relay->current_state = current_state;
            relay->last_change_time = real_timestamp_ms;
            relay->last_report_time = real_timestamp_ms;
            
            state_changes++;
            
            LOG_I(TAG, "Manual check - Relay %s: %s -> %s (GPIO: %d)", 
                    relay->relay_id,
                    relay_state_to_string(old_state),
                    relay_state_to_string(current_state),
                    stable_reading);
            
            if (ctx->state_callback) {
                relay_event_t event = {
                    .gpio_pin = relay->gpio_pin,
                    .old_state = old_state,
                    .new_state = current_state,
                    .timestamp = real_timestamp_ms,
                    .contact_type = relay->contact_type
                };
                
                strncpy(event.relay_id, relay->relay_id, sizeof(event.relay_id) - 1);
                event.relay_id[sizeof(event.relay_id) - 1] = '\0';
                
                strncpy(event.name, relay->name, sizeof(event.name) - 1);
                event.name[sizeof(event.name) - 1] = '\0';
                
                ctx->state_callback(&event, ctx->state_callback_user_data);
            }
        }
    }
    
    LOG_I(TAG, "Manual check completed: %d state changes detected", state_changes);
    
    return ESP_OK;
}

static const char* relay_state_to_string(relay_state_t state) {
    switch (state) {
        case RELAY_STATE_OK:    return "OK";
        case RELAY_STATE_DISC:  return "DISC";
        case RELAY_STATE_ERROR: return "ERROR";
        default:                return "UNKNOWN";
    }
}

static const char* contact_type_to_string(relay_contact_type_t type) {
    switch (type) {
        case RELAY_CONTACT_NO: return "NO";
        case RELAY_CONTACT_NC: return "NC";
        default:               return "NO";
    }
}

static relay_contact_type_t string_to_contact_type(const char* str) {
    if (str && strcmp(str, "NC") == 0) {
        return RELAY_CONTACT_NC;
    }
    return RELAY_CONTACT_NO;
}

static int find_relay_index_by_id(const char *relay_id) {
    if (!relay_id) return -1;
    
    relay_manager_context_t *ctx = &s_relay_ctx;
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        if (strcmp(ctx->relays[i].relay_id, relay_id) == 0) {
            return i;
        }
    }
    return -1;
}

static int find_relay_index_by_gpio(gpio_num_t gpio_pin) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        if (ctx->relays[i].gpio_pin == gpio_pin) {
            return i;
        }
    }
    return -1;
}

esp_err_t relay_manager_get_config_json(char *json_buffer, size_t buffer_size) {
    if (!json_buffer || buffer_size == 0) {
        return ESP_ERR_INVALID_ARG;
    }
    
    relay_manager_context_t *ctx = &s_relay_ctx;
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    char *buffer = get_message_buffer();
    if (!buffer) {
        return ESP_ERR_NO_MEM;
    }
    
    int pos = 0;
    pos += snprintf(buffer + pos, 256 - pos, "{");
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        if (i > 0) {
            pos += snprintf(buffer + pos, 256 - pos, ",");
        }
        
        pos += snprintf(buffer + pos, 256 - pos,
                       "\"%s\":{\"pin\":%d,\"active\":%s,\"name\":\"%s\",\"contact_type\":\"%s\"}",
                       relay->relay_id, relay->gpio_pin, relay->is_active ? "true" : "false",
                       relay->name, contact_type_to_string(relay->contact_type));
    }
    
    pos += snprintf(buffer + pos, 256 - pos, "}");
    
    if (pos < buffer_size) {
        strcpy(json_buffer, buffer);
        release_message_buffer(buffer);
        return ESP_OK;
    } else {
        release_message_buffer(buffer);
        return ESP_ERR_INVALID_SIZE;
    }
}

esp_err_t relay_manager_get_diagnostics_json(char *json_buffer, size_t buffer_size) {
    if (!json_buffer || buffer_size == 0) {
        return ESP_ERR_INVALID_ARG;
    }
    
    relay_manager_context_t *ctx = &s_relay_ctx;
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    int active_count = 0;
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        if (ctx->relays[i].is_active) {
            active_count++;
        }
    }
    
    snprintf(json_buffer, buffer_size,
             "{\"total_events\":%lu,\"filtered_events\":%lu,\"mqtt_commands\":%lu,\"active_relays\":%d}",
             ctx->total_events_processed, ctx->debounce_filtered_events,
             ctx->mqtt_commands_processed, active_count);
    
    return ESP_OK;
}

esp_err_t relay_manager_force_reconfigure_interrupts(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    if (!ctx->initialized || ctx->state != RELAY_MGR_STATE_RUNNING) {
        return ESP_ERR_INVALID_STATE;
    }
    
    LOG_I(TAG, "FORCE reconfiguring ALL GPIO interrupts");
    debug_relay_states("FORCE_RECONFIG_START");
    
    int active_count = 0;
    int success_count = 0;
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        if (!relay->is_active || !GPIO_IS_VALID_GPIO(relay->gpio_pin)) {
            continue;
        }
        
        active_count++;
        
        LOG_I(TAG, "Force reconfiguring relay %s (GPIO %d)", relay->relay_id, relay->gpio_pin);
        
        gpio_intr_disable(relay->gpio_pin);
        gpio_isr_handler_remove(relay->gpio_pin);
        
        vTaskDelay(pdMS_TO_TICKS(20));
        
        gpio_config_t io_conf = {
            .pin_bit_mask = (1ULL << relay->gpio_pin),
            .mode = GPIO_MODE_INPUT,
            .pull_up_en = GPIO_PULLUP_ENABLE,
            .pull_down_en = GPIO_PULLDOWN_DISABLE,
            .intr_type = GPIO_INTR_ANYEDGE
        };
        
        esp_err_t ret = gpio_config(&io_conf);
        if (ret != ESP_OK) {
            LOG_E(TAG, "Failed to reconfigure GPIO %d: %s", relay->gpio_pin, esp_err_to_name(ret));
            continue;
        }
        
        ret = gpio_isr_handler_add(relay->gpio_pin, gpio_isr_handler, (void*)(uintptr_t)relay->gpio_pin);
        if (ret != ESP_OK) {
            LOG_E(TAG, "Failed to add ISR for GPIO %d: %s", relay->gpio_pin, esp_err_to_name(ret));
            continue;
        }
        
        ret = gpio_intr_enable(relay->gpio_pin);
        if (ret != ESP_OK) {
            LOG_E(TAG, "Failed to enable interrupt for GPIO %d: %s", relay->gpio_pin, esp_err_to_name(ret));
            gpio_isr_handler_remove(relay->gpio_pin);
            continue;
        }
        
        success_count++;
        LOG_I(TAG, "Successfully reconfigured interrupt for relay %s", relay->relay_id);
        
        vTaskDelay(pdMS_TO_TICKS(50));
    }
    
    LOG_I(TAG, "Force reconfiguration completed: %d/%d active relays configured", success_count, active_count);
    debug_relay_states("FORCE_RECONFIG_END");
    
    return (success_count == active_count && active_count > 0) ? ESP_OK : ESP_FAIL;
}

esp_err_t relay_manager_deinit(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    if (ctx->state == RELAY_MGR_STATE_UNINITIALIZED || 
        ctx->state == RELAY_MGR_STATE_DEINITIALIZING) {
        return ESP_ERR_INVALID_STATE;
    }
    
    LOG_I(TAG, "Deinitializing Relay Manager");
    ctx->state = RELAY_MGR_STATE_DEINITIALIZING;
    ctx->initialized = false;
    
    relay_manager_cleanup_interrupt_diagnostics();
    
    if (ctx->config_save_pending && ctx->config_save_timer) {
        xTimerStop(ctx->config_save_timer, portMAX_DELAY);
        save_relay_config_immediate();
        ctx->config_save_pending = false;
    }
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        if (GPIO_IS_VALID_GPIO(ctx->relays[i].gpio_pin)) {
            gpio_intr_disable(ctx->relays[i].gpio_pin);
            gpio_isr_handler_remove(ctx->relays[i].gpio_pin);
            gpio_reset_pin(ctx->relays[i].gpio_pin);
        }
    }
    
    vTaskDelay(pdMS_TO_TICKS(100));
    
    if (ctx->gpio_event_queue) {
        xQueueReset(ctx->gpio_event_queue);
    }
    
    if (ctx->event_task_handle) {
        TaskHandle_t temp_handle = ctx->event_task_handle;
        ctx->event_task_handle = NULL;
        vTaskDelete(temp_handle);
        vTaskDelay(pdMS_TO_TICKS(200));
    }
    
    ctx->state_callback = NULL;
    ctx->state_callback_user_data = NULL;
    ctx->mqtt_callback = NULL;
    ctx->mqtt_callback_user_data = NULL;
    
    if (ctx->gpio_event_queue) {
        vQueueDelete(ctx->gpio_event_queue);
        ctx->gpio_event_queue = NULL;
    }
    
    if (ctx->config_save_timer) {
        xTimerDelete(ctx->config_save_timer, portMAX_DELAY);
        ctx->config_save_timer = NULL;
    }
    
    for (int i = 0; i < 3; i++) {
        ctx->pool_in_use[i] = false;
    }
    
    if (ctx->pool_mutex) {
        vSemaphoreDelete(ctx->pool_mutex);
        ctx->pool_mutex = NULL;
    }
    
    if (ctx->config_mutex) {
        vSemaphoreDelete(ctx->config_mutex);
        ctx->config_mutex = NULL;
    }
    
    memset(ctx, 0, sizeof(relay_manager_context_t));
    ctx->state = RELAY_MGR_STATE_UNINITIALIZED;
    
    LOG_I(TAG, "Relay Manager deinitialized");
    
    return ESP_OK;
}