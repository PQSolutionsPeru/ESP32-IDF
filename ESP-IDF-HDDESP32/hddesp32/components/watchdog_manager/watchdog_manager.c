#include "watchdog_manager.h"
#include <string.h>
#include <stdio.h>
#include <inttypes.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/timers.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_task_wdt.h"
#include "esp_heap_caps.h"
#include "wifi_manager.h"
#include "mqtt_manager.h"

#define TAG "WATCHDOG_MGR"

#define CONFIG_MODE_TIMEOUT_MS          15000
#define CONFIG_MODE_FEED_INTERVAL_MS    5000
#define CONFIG_MODE_HEALTH_CHECK_MS     15000

#define RUNNING_MODE_TIMEOUT_MS         8000
#define RUNNING_MODE_FEED_INTERVAL_MS   3000
#define RUNNING_MODE_HEALTH_CHECK_MS    10000

#define CRITICAL_MODE_TIMEOUT_MS        5000
#define CRITICAL_MODE_FEED_INTERVAL_MS  2000
#define CRITICAL_MODE_HEALTH_CHECK_MS   5000

#define DEFAULT_MEMORY_THRESHOLD        50000
#define MAX_REGISTERED_TASKS            10
#define MAX_RESET_REASON_LENGTH         64

typedef struct {
    TaskHandle_t handle;
    char name[16];
    int64_t last_feed_time;
    bool is_active;
} registered_task_t;

typedef struct {
    int64_t last_activity_time;
    uint32_t activity_count;
} subsystem_activity_t;

typedef struct {
    bool initialized;
    bool twdt_available;
    watchdog_mode_t current_mode;
    watchdog_health_status_t health_status;
    
    watchdog_config_t configs[3];
    
    registered_task_t registered_tasks[MAX_REGISTERED_TASKS];
    int registered_task_count;
    
    subsystem_activity_t subsystem_activity[4];
    
    uint32_t feed_count;
    uint32_t error_count;
    char last_reset_reason[MAX_RESET_REASON_LENGTH];
    
    watchdog_event_callback_t event_callback;
    void *event_user_data;
    
    TimerHandle_t health_check_timer;
    TimerHandle_t feed_timer;
    
    SemaphoreHandle_t mutex;
    
    int64_t last_health_check;
    int64_t last_feed_time;
    
} watchdog_manager_context_t;

static watchdog_manager_context_t s_watchdog_ctx = {0};

static void init_default_configs(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    ctx->configs[WATCHDOG_MODE_CONFIG] = (watchdog_config_t) {
        .timeout_ms = 60000,
        .feed_interval_ms = 15000,
        .health_check_interval_ms = 20000,
        .memory_threshold_bytes = 40000,
        .enable_memory_check = true,
        .enable_wifi_check = false,
        .enable_mqtt_check = false,
        .enable_task_monitoring = true
    };
    
    ctx->configs[WATCHDOG_MODE_RUNNING] = (watchdog_config_t) {
        .timeout_ms = 45000,
        .feed_interval_ms = 12000,
        .health_check_interval_ms = 18000,
        .memory_threshold_bytes = 35000,
        .enable_memory_check = true,
        .enable_wifi_check = true,
        .enable_mqtt_check = true,
        .enable_task_monitoring = true
    };
    
    ctx->configs[WATCHDOG_MODE_CRITICAL] = (watchdog_config_t) {
        .timeout_ms = 20000,
        .feed_interval_ms = 7000,
        .health_check_interval_ms = 10000,
        .memory_threshold_bytes = 25000,
        .enable_memory_check = true,
        .enable_wifi_check = true,
        .enable_mqtt_check = true,
        .enable_task_monitoring = true
    };
    
    ESP_LOGI(TAG, "Conservative watchdog configurations initialized");
}

static void health_check_timer_callback(TimerHandle_t xTimer) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return;
    }
    
    size_t current_free = esp_get_free_heap_size();
    static size_t last_free = 0;
    
    if (last_free > 0 && last_free > current_free && (last_free - current_free) > 5000) {
        ESP_LOGW(TAG, "Significant memory decrease detected: %zu -> %zu", last_free, current_free);
        mqtt_manager_emergency_memory_cleanup();
    }
    last_free = current_free;
    
    watchdog_health_status_t status = watchdog_manager_check_system_health();
    
    if (status != ctx->health_status) {
        watchdog_health_status_t old_status = ctx->health_status;
        ctx->health_status = status;
        
        ESP_LOGI(TAG, "System health changed: %d -> %d", old_status, status);
        
        if (ctx->event_callback) {
            ctx->event_callback(status, WATCHDOG_CHECK_TASKS, ctx->event_user_data);
        }
        
        if (status == WATCHDOG_HEALTH_CRITICAL) {
            ESP_LOGE(TAG, "CRITICAL health status - initiating recovery procedures");
            
            if (ctx->current_mode != WATCHDOG_MODE_CRITICAL) {
                watchdog_manager_set_mode(WATCHDOG_MODE_CRITICAL);
            }
            
            size_t free_heap = esp_get_free_heap_size();
            if (free_heap < 20000) {
                ESP_LOGE(TAG, "Critical memory situation, forcing system reset");
                watchdog_manager_force_reset("critical_memory_shortage");
            }
        } else if (status == WATCHDOG_HEALTH_ERROR) {
            ESP_LOGE(TAG, "System health error detected");
            watchdog_manager_force_reset("system_health_error");
        }
    }
    
    ctx->last_health_check = esp_timer_get_time() / 1000;
}

static void feed_timer_callback(TimerHandle_t xTimer) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return;
    }
    
    bool any_task_fed = false;
    int64_t current_time = esp_timer_get_time() / 1000;
    
    for (int i = 0; i < ctx->registered_task_count; i++) {
        if (ctx->registered_tasks[i].is_active) {
            int64_t time_since_feed = current_time - ctx->registered_tasks[i].last_feed_time;
            
            if (time_since_feed < (ctx->configs[ctx->current_mode].feed_interval_ms * 2)) {
                any_task_fed = true;
                break;
            }
        }
    }
    
    if (!any_task_fed && ctx->registered_task_count > 0) {
        ESP_LOGW(TAG, "No tasks have fed watchdog recently");
        ctx->error_count++;
        
        if (ctx->error_count > 10) {
            ESP_LOGE(TAG, "All tasks appear to be starved");
            
            if (ctx->event_callback) {
                ctx->event_callback(WATCHDOG_HEALTH_CRITICAL, WATCHDOG_CHECK_TASKS, ctx->event_user_data);
            }
        }
    } else if (ctx->error_count > 0) {
        ctx->error_count = 0;
    }
}

static watchdog_health_status_t check_wifi_health(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    watchdog_config_t *config = &ctx->configs[ctx->current_mode];
    
    if (!config->enable_wifi_check) {
        return WATCHDOG_HEALTH_GOOD;
    }
    
    if (!wifi_manager_is_connected()) {
        ESP_LOGW(TAG, "WiFi disconnected");
        return WATCHDOG_HEALTH_WARNING;
    }
    
    return WATCHDOG_HEALTH_GOOD;
}

static watchdog_health_status_t check_memory_health(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    watchdog_config_t *config = &ctx->configs[ctx->current_mode];
    
    if (!config->enable_memory_check) {
        return WATCHDOG_HEALTH_GOOD;
    }
    
    size_t free_heap = esp_get_free_heap_size();
    size_t min_free = esp_get_minimum_free_heap_size();
    
    uint32_t critical_threshold = 35000;
    uint32_t warning_threshold = 55000;
    
    static int critical_count = 0;
    static int warning_count = 0;
    
    if (free_heap < critical_threshold || min_free < critical_threshold) {
        critical_count++;
        if (critical_count >= 3) {
            ESP_LOGE(TAG, "CRITICAL memory confirmed: free=%zu min=%zu threshold=%" PRIu32 " (count=%d)", 
                    free_heap, min_free, critical_threshold, critical_count);
            return WATCHDOG_HEALTH_CRITICAL;
        } else {
            ESP_LOGW(TAG, "Critical memory detected but not confirmed: count=%d/3", critical_count);
            return WATCHDOG_HEALTH_WARNING;
        }
    } else {
        critical_count = 0;
    }
    
    if (free_heap < warning_threshold || min_free < warning_threshold) {
        warning_count++;
        if (warning_count >= 5) {
            ESP_LOGW(TAG, "Low memory confirmed: free=%zu min=%zu threshold=%" PRIu32,
                    free_heap, min_free, warning_threshold);
            return WATCHDOG_HEALTH_WARNING;
        }
    } else {
        warning_count = 0;
    }
    
    return WATCHDOG_HEALTH_GOOD;
}

static watchdog_health_status_t check_tasks_health(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    watchdog_config_t *config = &ctx->configs[ctx->current_mode];
    
    if (!config->enable_task_monitoring) {
        return WATCHDOG_HEALTH_GOOD;
    }
    
    int64_t current_time = esp_timer_get_time() / 1000;
    uint32_t max_timeout = config->timeout_ms * 2;
    
    for (int i = 0; i < ctx->registered_task_count; i++) {
        if (ctx->registered_tasks[i].is_active) {
            int64_t time_since_feed = current_time - ctx->registered_tasks[i].last_feed_time;
            
            if (time_since_feed > max_timeout) {
                ESP_LOGE(TAG, "Task %s hasn't fed watchdog in %lld ms",
                        ctx->registered_tasks[i].name, time_since_feed);
                return WATCHDOG_HEALTH_CRITICAL;
            }
        }
    }
    
    return WATCHDOG_HEALTH_GOOD;
}

static watchdog_health_status_t check_mqtt_health(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    watchdog_config_t *config = &ctx->configs[ctx->current_mode];
    
    if (!config->enable_mqtt_check) {
        return WATCHDOG_HEALTH_GOOD;
    }
    
    if (!mqtt_manager_is_connected()) {
        ESP_LOGW(TAG, "MQTT disconnected");
        return WATCHDOG_HEALTH_WARNING;
    }
    
    return WATCHDOG_HEALTH_GOOD;
}

static esp_err_t setup_twdt(watchdog_manager_context_t *ctx) {
    esp_task_wdt_config_t twdt_config = {
        .timeout_ms = ctx->configs[ctx->current_mode].timeout_ms,
        .idle_core_mask = (1 << CONFIG_FREERTOS_NUMBER_OF_CORES) - 1,
        .trigger_panic = false
    };
    
    esp_err_t status_ret = esp_task_wdt_status(NULL);
    
    if (status_ret == ESP_ERR_INVALID_STATE) {
        esp_err_t ret = esp_task_wdt_init(&twdt_config);
        if (ret != ESP_OK) {
            ESP_LOGE(TAG, "Failed to initialize TWDT: %s", esp_err_to_name(ret));
            return ret;
        }
        ESP_LOGI(TAG, "TWDT initialized with %" PRIu32 "ms timeout", twdt_config.timeout_ms);
    } else {
        esp_err_t ret = esp_task_wdt_reconfigure(&twdt_config);
        if (ret != ESP_OK) {
            ESP_LOGE(TAG, "Failed to reconfigure TWDT: %s", esp_err_to_name(ret));
            return ret;
        }
        ESP_LOGI(TAG, "TWDT reconfigured with %" PRIu32 "ms timeout", twdt_config.timeout_ms);
    }
    
    return ESP_OK;
}

esp_err_t watchdog_manager_init(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (ctx->initialized) {
        ESP_LOGW(TAG, "Already initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Initializing Watchdog Manager");
    
    memset(ctx, 0, sizeof(watchdog_manager_context_t));
    
    ctx->mutex = xSemaphoreCreateMutex();
    if (ctx->mutex == NULL) {
        ESP_LOGE(TAG, "Failed to create mutex");
        return ESP_ERR_NO_MEM;
    }
    
    init_default_configs();
    ctx->current_mode = WATCHDOG_MODE_CONFIG;
    ctx->health_status = WATCHDOG_HEALTH_GOOD;
    
    esp_err_t ret = setup_twdt(ctx);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "TWDT setup failed: %s", esp_err_to_name(ret));
        vSemaphoreDelete(ctx->mutex);
        return ret;
    }
    ctx->twdt_available = true;
    
    ctx->health_check_timer = xTimerCreate(
        "health_check",
        pdMS_TO_TICKS(ctx->configs[ctx->current_mode].health_check_interval_ms),
        pdTRUE,
        NULL,
        health_check_timer_callback
    );
    
    ctx->feed_timer = xTimerCreate(
        "feed_timer",
        pdMS_TO_TICKS(ctx->configs[ctx->current_mode].feed_interval_ms),
        pdTRUE,
        NULL,
        feed_timer_callback
    );
    
    if (ctx->health_check_timer == NULL || ctx->feed_timer == NULL) {
        ESP_LOGE(TAG, "Failed to create timers");
        if (ctx->mutex) {
            vSemaphoreDelete(ctx->mutex);
        }
        return ESP_ERR_NO_MEM;
    }
    
    ctx->last_health_check = esp_timer_get_time() / 1000;
    ctx->last_feed_time = ctx->last_health_check;
    
    for (int i = 0; i < 4; i++) {
        ctx->subsystem_activity[i].last_activity_time = ctx->last_health_check;
        ctx->subsystem_activity[i].activity_count = 0;
    }
    
    ctx->initialized = true;
    
    xTimerStart(ctx->health_check_timer, 0);
    xTimerStart(ctx->feed_timer, 0);
    
    ESP_LOGI(TAG, "Watchdog Manager initialized successfully with TWDT active");
    return ESP_OK;
}

esp_err_t watchdog_manager_deinit(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Deinitializing Watchdog Manager");
    
    if (ctx->health_check_timer) {
        xTimerStop(ctx->health_check_timer, portMAX_DELAY);
        xTimerDelete(ctx->health_check_timer, portMAX_DELAY);
    }
    
    if (ctx->feed_timer) {
        xTimerStop(ctx->feed_timer, portMAX_DELAY);
        xTimerDelete(ctx->feed_timer, portMAX_DELAY);
    }
    
    for (int i = 0; i < ctx->registered_task_count; i++) {
        if (ctx->registered_tasks[i].is_active && ctx->twdt_available) {
            esp_task_wdt_delete(ctx->registered_tasks[i].handle);
        }
    }
    
    if (ctx->mutex) {
        vSemaphoreDelete(ctx->mutex);
    }
    
    memset(ctx, 0, sizeof(watchdog_manager_context_t));
    
    ESP_LOGI(TAG, "Watchdog Manager deinitialized");
    return ESP_OK;
}

esp_err_t watchdog_manager_set_mode(watchdog_mode_t mode) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (mode < WATCHDOG_MODE_CONFIG || mode > WATCHDOG_MODE_CRITICAL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(5000)) == pdTRUE) {
        if (mode != ctx->current_mode) {
            ESP_LOGI(TAG, "Changing mode from %d to %d", ctx->current_mode, mode);
            
            ctx->current_mode = mode;
            
            esp_task_wdt_config_t twdt_config = {
                .timeout_ms = ctx->configs[mode].timeout_ms,
                .idle_core_mask = (1 << CONFIG_FREERTOS_NUMBER_OF_CORES) - 1,
                .trigger_panic = false
            };
            
            esp_err_t ret = esp_task_wdt_reconfigure(&twdt_config);
            if (ret != ESP_OK) {
                ESP_LOGW(TAG, "Failed to reconfigure TWDT for mode %d: %s", mode, esp_err_to_name(ret));
            } else {
                ESP_LOGI(TAG, "TWDT reconfigured for mode %d with %" PRIu32 "ms timeout", mode, twdt_config.timeout_ms);
            }
            
            if (ctx->health_check_timer) {
                xTimerChangePeriod(ctx->health_check_timer,
                                  pdMS_TO_TICKS(ctx->configs[mode].health_check_interval_ms),
                                  pdMS_TO_TICKS(1000));
            }
                              
            if (ctx->feed_timer) {
                xTimerChangePeriod(ctx->feed_timer,
                                  pdMS_TO_TICKS(ctx->configs[mode].feed_interval_ms),
                                  pdMS_TO_TICKS(1000));
            }
        }
        
        xSemaphoreGive(ctx->mutex);
    } else {
        return ESP_ERR_TIMEOUT;
    }
    
    return ESP_OK;
}

watchdog_mode_t watchdog_manager_get_mode(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return WATCHDOG_MODE_CONFIG;
    }
    
    return ctx->current_mode;
}

esp_err_t watchdog_manager_register_task(TaskHandle_t task_handle, const char *task_name) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (task_name == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (task_handle == NULL) {
        task_handle = xTaskGetCurrentTaskHandle();
    }
    
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(10000)) != pdTRUE) {
        ESP_LOGE(TAG, "Could not acquire mutex to register task %s", task_name);
        return ESP_ERR_TIMEOUT;
    }
    
    for (int i = 0; i < ctx->registered_task_count; i++) {
        if (ctx->registered_tasks[i].handle == task_handle) {
            ESP_LOGW(TAG, "Task %s already registered", task_name);
            xSemaphoreGive(ctx->mutex);
            return ESP_OK;
        }
    }
    
    if (ctx->registered_task_count >= MAX_REGISTERED_TASKS) {
        ESP_LOGE(TAG, "No space for more tasks (%d/%d)", ctx->registered_task_count, MAX_REGISTERED_TASKS);
        xSemaphoreGive(ctx->mutex);
        return ESP_ERR_NO_MEM;
    }
    
    esp_err_t ret = esp_task_wdt_add(task_handle);
    if (ret != ESP_OK && ret != ESP_ERR_INVALID_ARG) {
        ESP_LOGE(TAG, "Failed to register task %s in TWDT: %s", task_name, esp_err_to_name(ret));
        xSemaphoreGive(ctx->mutex);
        return ret;
    }
    
    int index = ctx->registered_task_count;
    ctx->registered_tasks[index].handle = task_handle;
    strncpy(ctx->registered_tasks[index].name, task_name, sizeof(ctx->registered_tasks[index].name) - 1);
    ctx->registered_tasks[index].name[sizeof(ctx->registered_tasks[index].name) - 1] = '\0';
    ctx->registered_tasks[index].last_feed_time = esp_timer_get_time() / 1000;
    ctx->registered_tasks[index].is_active = true;
    
    ctx->registered_task_count++;
    
    ESP_LOGI(TAG, "Registered task: %s (%d/%d) in TWDT", task_name, ctx->registered_task_count, MAX_REGISTERED_TASKS);
    
    xSemaphoreGive(ctx->mutex);
    return ESP_OK;
}

esp_err_t watchdog_manager_unregister_task(TaskHandle_t task_handle) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (task_handle == NULL) {
        task_handle = xTaskGetCurrentTaskHandle();
    }
    
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(5000)) == pdTRUE) {
        for (int i = 0; i < ctx->registered_task_count; i++) {
            if (ctx->registered_tasks[i].handle == task_handle) {
                esp_task_wdt_delete(task_handle);
                
                ctx->registered_tasks[i].is_active = false;
                
                ESP_LOGI(TAG, "Unregistered task: %s", ctx->registered_tasks[i].name);
                
                xSemaphoreGive(ctx->mutex);
                return ESP_OK;
            }
        }
        
        xSemaphoreGive(ctx->mutex);
        return ESP_ERR_NOT_FOUND;
    }
    
    return ESP_ERR_TIMEOUT;
}

esp_err_t watchdog_manager_feed(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized || !ctx->twdt_available) {
        return ESP_ERR_INVALID_STATE;
    }
    
    TaskHandle_t current_task = xTaskGetCurrentTaskHandle();
    bool task_registered = false;
    int task_index = -1;
    
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(50)) == pdTRUE) {
        for (int i = 0; i < ctx->registered_task_count; i++) {
            if (ctx->registered_tasks[i].handle == current_task && ctx->registered_tasks[i].is_active) {
                task_registered = true;
                task_index = i;
                break;
            }
        }
        xSemaphoreGive(ctx->mutex);
    }
    
    if (!task_registered) {
        return ESP_ERR_NOT_FOUND;
    }
    
    esp_err_t ret = esp_task_wdt_reset();
    if (ret != ESP_OK) {
        ctx->error_count++;
        ESP_LOGW(TAG, "TWDT reset failed for task %s: %s", 
                task_index >= 0 ? ctx->registered_tasks[task_index].name : "unknown",
                esp_err_to_name(ret));
        return ret;
    }
    
    ctx->feed_count++;
    ctx->last_feed_time = esp_timer_get_time() / 1000;
    
    if (task_index >= 0) {
        ctx->registered_tasks[task_index].last_feed_time = ctx->last_feed_time;
    }
    
    return ESP_OK;
}

esp_err_t watchdog_manager_report_activity(watchdog_check_type_t check_type) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (check_type < WATCHDOG_CHECK_MEMORY || check_type > WATCHDOG_CHECK_TASKS) {
        return ESP_ERR_INVALID_ARG;
    }
    
    ctx->subsystem_activity[check_type].last_activity_time = esp_timer_get_time() / 1000;
    ctx->subsystem_activity[check_type].activity_count++;
    
    return ESP_OK;
}

void watchdog_manager_force_reset(const char *reason) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (reason) {
        strncpy(ctx->last_reset_reason, reason, sizeof(ctx->last_reset_reason) - 1);
        ctx->last_reset_reason[sizeof(ctx->last_reset_reason) - 1] = '\0';
        ESP_LOGE(TAG, "Forcing system reset: %s", reason);
    } else {
        ESP_LOGE(TAG, "Forcing system reset: unknown reason");
    }
    
    vTaskDelay(pdMS_TO_TICKS(100));
    
    esp_restart();
}

watchdog_health_status_t watchdog_manager_get_health_status(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return WATCHDOG_HEALTH_ERROR;
    }
    
    return ctx->health_status;
}

watchdog_health_status_t watchdog_manager_check_system_health(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return WATCHDOG_HEALTH_ERROR;
    }
    
    watchdog_health_status_t worst_status = WATCHDOG_HEALTH_GOOD;
    
    watchdog_health_status_t memory_status = check_memory_health();
    if (memory_status > worst_status) {
        worst_status = memory_status;
    }
    
    watchdog_health_status_t wifi_status = check_wifi_health();
    if (wifi_status > worst_status) {
        worst_status = wifi_status;
    }
    
    watchdog_health_status_t mqtt_status = check_mqtt_health();
    if (mqtt_status > worst_status) {
        worst_status = mqtt_status;
    }
    
    watchdog_health_status_t tasks_status = check_tasks_health();
    if (tasks_status > worst_status) {
        worst_status = tasks_status;
    }
    
    ctx->health_status = worst_status;
    ctx->last_health_check = esp_timer_get_time() / 1000;
    
    return worst_status;
}

esp_err_t watchdog_manager_get_stats(uint32_t *feed_count, uint32_t *error_count, const char **last_reset_reason) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (feed_count) {
        *feed_count = ctx->feed_count;
    }
    
    if (error_count) {
        *error_count = ctx->error_count;
    }
    
    if (last_reset_reason) {
        *last_reset_reason = ctx->last_reset_reason;
    }
    
    return ESP_OK;
}

esp_err_t watchdog_manager_set_event_callback(watchdog_event_callback_t callback, void *user_data) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        ctx->event_callback = callback;
        ctx->event_user_data = user_data;
        xSemaphoreGive(ctx->mutex);
    }
    
    return ESP_OK;
}

esp_err_t watchdog_manager_set_config(watchdog_mode_t mode, const watchdog_config_t *config) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized || !config) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (mode < WATCHDOG_MODE_CONFIG || mode > WATCHDOG_MODE_CRITICAL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        ctx->configs[mode] = *config;
        
        if (mode == ctx->current_mode) {
            watchdog_manager_set_mode(mode);
        }
        
        xSemaphoreGive(ctx->mutex);
    }
    
    return ESP_OK;
}

esp_err_t watchdog_manager_get_config(watchdog_mode_t mode, watchdog_config_t *config) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized || !config) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (mode < WATCHDOG_MODE_CONFIG || mode > WATCHDOG_MODE_CRITICAL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    *config = ctx->configs[mode];
    return ESP_OK;
}