#include "relay_manager.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_system.h"
#include "config_manager.h"
#include "cJSON.h"

#define TAG "RELAY_MGR"

// Configuración de pines por defecto (basada en MicroPython original)
static const gpio_num_t DEFAULT_RELAY_PINS[RELAY_MANAGER_MAX_RELAYS] = {
    GPIO_NUM_32,  // relay_1
    GPIO_NUM_33,  // relay_2
    GPIO_NUM_25,  // relay_3
    GPIO_NUM_26,  // relay_4
    GPIO_NUM_27,  // relay_5
    GPIO_NUM_14   // relay_6
};

// Nombres por defecto para los relays
static const char* DEFAULT_RELAY_NAMES[RELAY_MANAGER_MAX_RELAYS] = {
    "Relay 1",       // relay_1
    "Relay 2",       // relay_2
    "Relay 3",      // relay_3
    "Relay 4",      // relay_4
    "Relay 5",      // relay_5
    "Relay 6"       // relay_6
};

// Estados activos por defecto (TODOS INACTIVOS hasta que la APP los active)
static const bool DEFAULT_ACTIVE_STATES[RELAY_MANAGER_MAX_RELAYS] = {
    false,  // relay_1 inactivo
    false,  // relay_2 inactivo
    false,  // relay_3 inactivo
    false,  // relay_4 inactivo
    false,  // relay_5 inactivo
    false   // relay_6 inactivo
};

// Estructura interna para eventos de GPIO
typedef struct {
    gpio_num_t gpio_pin;
    int gpio_level;
    int64_t timestamp;
} gpio_event_t;

// Contexto del Relay Manager
typedef struct {
    // Configuración de relays
    relay_config_t relays[RELAY_MANAGER_MAX_RELAYS];
    bool initialized;
    
    // FreeRTOS objects
    QueueHandle_t gpio_event_queue;
    SemaphoreHandle_t config_mutex;
    TaskHandle_t event_task_handle;
    
    // Callbacks
    relay_state_change_callback_t state_callback;
    void *state_callback_user_data;
    relay_mqtt_command_callback_t mqtt_callback;
    void *mqtt_callback_user_data;
    
    // Estadísticas
    uint32_t total_events_processed;
    uint32_t debounce_filtered_events;
    uint32_t mqtt_commands_processed;
    
} relay_manager_context_t;

static relay_manager_context_t s_relay_ctx = {0};

// Funciones internas
static esp_err_t load_relay_config(void);
static esp_err_t save_relay_config(void);
static void IRAM_ATTR gpio_isr_handler(void *arg);
static void relay_event_task(void *pvParameters);
static relay_state_t gpio_to_logical_state(int gpio_level, relay_contact_type_t contact_type);
static const char* relay_state_to_string(relay_state_t state);
static const char* contact_type_to_string(relay_contact_type_t type);
static relay_contact_type_t string_to_contact_type(const char* str);
static int find_relay_index_by_id(const char *relay_id);
static int find_relay_index_by_gpio(gpio_num_t gpio_pin);

// Inicialización
esp_err_t relay_manager_init(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    if (ctx->initialized) {
        ESP_LOGW(TAG, "Already initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Initializing Relay Manager with %d relays", RELAY_MANAGER_MAX_RELAYS);
    
    // Limpiar contexto
    memset(ctx, 0, sizeof(relay_manager_context_t));
    
    // Crear mutex para configuración
    ctx->config_mutex = xSemaphoreCreateMutex();
    if (ctx->config_mutex == NULL) {
        ESP_LOGE(TAG, "Failed to create config mutex");
        return ESP_ERR_NO_MEM;
    }
    
    // Crear cola para eventos GPIO
    ctx->gpio_event_queue = xQueueCreate(20, sizeof(gpio_event_t));
    if (ctx->gpio_event_queue == NULL) {
        vSemaphoreDelete(ctx->config_mutex);
        ESP_LOGE(TAG, "Failed to create GPIO event queue");
        return ESP_ERR_NO_MEM;
    }
    
    // Configurar relays con valores por defecto
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        relay->gpio_pin = DEFAULT_RELAY_PINS[i];
        snprintf(relay->relay_id, sizeof(relay->relay_id), "relay_%d", i + 1);
        strncpy(relay->name, DEFAULT_RELAY_NAMES[i], sizeof(relay->name) - 1);
        relay->name[sizeof(relay->name) - 1] = '\0';
        relay->contact_type = RELAY_CONTACT_NO;  // NO por defecto
        relay->is_active = DEFAULT_ACTIVE_STATES[i];
        relay->current_state = RELAY_STATE_DISC;  // Estado inicial
        relay->last_change_time = 0;
        relay->last_report_time = 0;
    }
    
    // Cargar configuración persistente
    esp_err_t ret = load_relay_config();
    if (ret != ESP_OK) {
        ESP_LOGW(TAG, "Could not load config from NVS, using defaults: %s", esp_err_to_name(ret));
    }
    
    // PRIMERO: Instalar servicio de ISR ANTES de configurar interrupciones
    ret = gpio_install_isr_service(0);
    if (ret != ESP_OK && ret != ESP_ERR_INVALID_STATE) {
        ESP_LOGE(TAG, "Failed to install ISR service: %s", esp_err_to_name(ret));
        goto cleanup;
    }
    
    // SEGUNDO: Configurar GPIOs e interrupciones
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        // Configurar GPIO
        gpio_config_t io_conf = {
            .pin_bit_mask = (1ULL << relay->gpio_pin),
            .mode = GPIO_MODE_INPUT,
            .pull_up_en = GPIO_PULLUP_ENABLE,    // Pull-up interno
            .pull_down_en = GPIO_PULLDOWN_DISABLE,
            .intr_type = GPIO_INTR_ANYEDGE       // Detectar ambos flancos
        };
        
        ret = gpio_config(&io_conf);
        if (ret != ESP_OK) {
            ESP_LOGE(TAG, "Failed to configure GPIO %d: %s", relay->gpio_pin, esp_err_to_name(ret));
            goto cleanup;
        }
        
        // Leer estado inicial
        int initial_level = gpio_get_level(relay->gpio_pin);
        relay->current_state = gpio_to_logical_state(initial_level, relay->contact_type);
        relay->last_change_time = esp_timer_get_time();
        
        ESP_LOGI(TAG, "Relay %s (GPIO %d): %s, Type: %s, Active: %s, Initial: %s", 
                relay->relay_id, relay->gpio_pin, relay->name,
                contact_type_to_string(relay->contact_type),
                relay->is_active ? "YES" : "NO",
                relay_state_to_string(relay->current_state));
        
        // Configurar interrupción (AHORA sí funciona porque ya instalamos el servicio)
        ret = gpio_isr_handler_add(relay->gpio_pin, gpio_isr_handler, (void*)(uintptr_t)relay->gpio_pin);
        if (ret != ESP_OK) {
            ESP_LOGE(TAG, "Failed to add ISR for GPIO %d: %s", relay->gpio_pin, esp_err_to_name(ret));
            goto cleanup;
        }
    }
    
    // Crear task para procesar eventos
    BaseType_t task_ret = xTaskCreate(
        relay_event_task,
        "relay_events",
        4096,  // Stack size
        NULL,
        5,     // Priority
        &ctx->event_task_handle
    );
    
    if (task_ret != pdPASS) {
        ESP_LOGE(TAG, "Failed to create relay event task");
        ret = ESP_FAIL;
        goto cleanup;
    }
    
    ctx->initialized = true;
    ESP_LOGI(TAG, "Relay Manager initialized successfully");
    return ESP_OK;
    
cleanup:
    // Limpieza en caso de error
    if (ctx->event_task_handle) {
        vTaskDelete(ctx->event_task_handle);
        ctx->event_task_handle = NULL;
    }
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        gpio_isr_handler_remove(DEFAULT_RELAY_PINS[i]);
    }
    
    if (ctx->gpio_event_queue) {
        vQueueDelete(ctx->gpio_event_queue);
        ctx->gpio_event_queue = NULL;
    }
    
    if (ctx->config_mutex) {
        vSemaphoreDelete(ctx->config_mutex);
        ctx->config_mutex = NULL;
    }
    
    return ret;
}

// Handler de interrupción GPIO (ejecutado en contexto ISR)
static void IRAM_ATTR gpio_isr_handler(void *arg) {
    gpio_num_t gpio_pin = (gpio_num_t)(uintptr_t)arg;
    
    gpio_event_t event = {
        .gpio_pin = gpio_pin,
        .gpio_level = gpio_get_level(gpio_pin),
        .timestamp = esp_timer_get_time()
    };
    
    // Enviar a cola (no bloquear en ISR)
    BaseType_t higher_priority_task_woken = pdFALSE;
    xQueueSendFromISR(s_relay_ctx.gpio_event_queue, &event, &higher_priority_task_woken);
    
    if (higher_priority_task_woken == pdTRUE) {
        portYIELD_FROM_ISR();
    }
}

// Task para procesar eventos de GPIO con debounce
static void relay_event_task(void *pvParameters) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    gpio_event_t gpio_event;
    
    ESP_LOGI(TAG, "Relay event task started");
    
    while (1) {
        // Esperar evento de GPIO
        if (xQueueReceive(ctx->gpio_event_queue, &gpio_event, portMAX_DELAY) == pdTRUE) {
            ctx->total_events_processed++;
            
            // Encontrar relay correspondiente
            int relay_index = find_relay_index_by_gpio(gpio_event.gpio_pin);
            if (relay_index < 0) {
                ESP_LOGW(TAG, "GPIO event for unknown pin %d", gpio_event.gpio_pin);
                continue;
            }
            
            relay_config_t *relay = &ctx->relays[relay_index];
            
            // Verificar si el relay está activo
            if (!relay->is_active) {
                continue; // Ignorar relays inactivos
            }
            
            // Aplicar debounce
            int64_t time_since_last = gpio_event.timestamp - relay->last_change_time;
            if (time_since_last < (RELAY_MANAGER_DEBOUNCE_TIME_MS * 1000)) {
                ctx->debounce_filtered_events++;
                continue; // Filtrar por debounce
            }
            
            // Calcular nuevo estado lógico
            relay_state_t new_state = gpio_to_logical_state(gpio_event.gpio_level, relay->contact_type);
            
            // Verificar si realmente cambió el estado
            if (new_state == relay->current_state) {
                continue; // Sin cambio real
            }
            
            // Verificar intervalo mínimo de reporte
            int64_t time_since_last_report = gpio_event.timestamp - relay->last_report_time;
            if (time_since_last_report < (RELAY_MANAGER_MIN_REPORT_INTERVAL_MS * 1000)) {
                continue; // Muy pronto para reportar
            }
            
            // Actualizar estado
            relay_state_t old_state = relay->current_state;
            relay->current_state = new_state;
            relay->last_change_time = gpio_event.timestamp;
            relay->last_report_time = gpio_event.timestamp;
            
            ESP_LOGI(TAG, "Relay %s state change: %s -> %s", 
                    relay->relay_id,
                    relay_state_to_string(old_state),
                    relay_state_to_string(new_state));
            
            // Generar evento para callback
            if (ctx->state_callback) {
                relay_event_t event = {
                    .gpio_pin = relay->gpio_pin,
                    .old_state = old_state,
                    .new_state = new_state,
                    .timestamp = gpio_event.timestamp,
                    .contact_type = relay->contact_type
                };
                
                strncpy(event.relay_id, relay->relay_id, sizeof(event.relay_id) - 1);
                event.relay_id[sizeof(event.relay_id) - 1] = '\0';
                
                strncpy(event.name, relay->name, sizeof(event.name) - 1);
                event.name[sizeof(event.name) - 1] = '\0';
                
                // Llamar callback (fuera del contexto ISR)
                ctx->state_callback(&event, ctx->state_callback_user_data);
            }
        }
    }
}

// Convertir nivel GPIO a estado lógico según tipo de contacto
static relay_state_t gpio_to_logical_state(int gpio_level, relay_contact_type_t contact_type) {
    if (contact_type == RELAY_CONTACT_NC) {
        // Normally Closed: HIGH = OK, LOW = DISC
        return (gpio_level == 1) ? RELAY_STATE_OK : RELAY_STATE_DISC;
    } else {
        // Normally Open: LOW = OK, HIGH = DISC  
        return (gpio_level == 0) ? RELAY_STATE_OK : RELAY_STATE_DISC;
    }
}

// Cargar configuración desde NVS
static esp_err_t load_relay_config(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    esp_err_t ret;
    
    // Cargar estados activos
    size_t active_size = sizeof(bool) * RELAY_MANAGER_MAX_RELAYS;
    bool active_states[RELAY_MANAGER_MAX_RELAYS];
    size_t actual_size = active_size;
    
    ret = config_manager_get_blob("relay_active", active_states, &actual_size);
    if (ret == ESP_OK && actual_size == active_size) {
        for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
            ctx->relays[i].is_active = active_states[i];
        }
        ESP_LOGI(TAG, "Loaded active states from NVS");
    }
    
    // Cargar nombres personalizados
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
    
    // Cargar tipos de contacto
    size_t types_size = sizeof(relay_contact_type_t) * RELAY_MANAGER_MAX_RELAYS;
    relay_contact_type_t contact_types[RELAY_MANAGER_MAX_RELAYS];
    actual_size = types_size;
    
    ret = config_manager_get_blob("relay_types", contact_types, &actual_size);
    if (ret == ESP_OK && actual_size == types_size) {
        for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
            ctx->relays[i].contact_type = contact_types[i];
        }
        ESP_LOGI(TAG, "Loaded contact types from NVS");
    }
    
    return ESP_OK;
}

// Guardar configuración en NVS
static esp_err_t save_relay_config(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    esp_err_t ret;
    
    // Guardar estados activos
    bool active_states[RELAY_MANAGER_MAX_RELAYS];
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        active_states[i] = ctx->relays[i].is_active;
    }
    
    ret = config_manager_set_blob("relay_active", active_states, sizeof(active_states));
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to save active states: %s", esp_err_to_name(ret));
        return ret;
    }
    
    // Guardar nombres personalizados
    char names_key[32];
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        snprintf(names_key, sizeof(names_key), "relay_name_%d", i);
        ret = config_manager_set_str(names_key, ctx->relays[i].name);
        if (ret != ESP_OK) {
            ESP_LOGW(TAG, "Failed to save name for relay %d", i);
        }
    }
    
    // Guardar tipos de contacto
    relay_contact_type_t contact_types[RELAY_MANAGER_MAX_RELAYS];
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        contact_types[i] = ctx->relays[i].contact_type;
    }
    
    ret = config_manager_set_blob("relay_types", contact_types, sizeof(contact_types));
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to save contact types: %s", esp_err_to_name(ret));
        return ret;
    }
    
    ESP_LOGI(TAG, "Relay configuration saved to NVS");
    return ESP_OK;
}

// Configurar callback de cambio de estado
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

// Configurar callback MQTT
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

// Obtener estado de un relay
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

// Generar JSON con todos los estados activos
esp_err_t relay_manager_get_all_states_json(char *json_buffer, size_t buffer_size) {
    if (!json_buffer || buffer_size == 0) {
        return ESP_ERR_INVALID_ARG;
    }
    
    relay_manager_context_t *ctx = &s_relay_ctx;
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    cJSON *root = cJSON_CreateObject();
    if (!root) {
        return ESP_ERR_NO_MEM;
    }
    
    // Solo incluir relays activos
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        if (!relay->is_active) {
            continue; // Saltar relays inactivos
        }
        
        cJSON *relay_obj = cJSON_CreateObject();
        if (!relay_obj) {
            cJSON_Delete(root);
            return ESP_ERR_NO_MEM;
        }
        
        cJSON_AddStringToObject(relay_obj, "name", relay->name);
        cJSON_AddStringToObject(relay_obj, "status", relay_state_to_string(relay->current_state));
        cJSON_AddNumberToObject(relay_obj, "pin", relay->gpio_pin);
        cJSON_AddStringToObject(relay_obj, "contact_type", contact_type_to_string(relay->contact_type));
        
        // Timestamp
        cJSON *timestamp_obj = cJSON_CreateObject();
        if (timestamp_obj) {
            cJSON_AddNumberToObject(timestamp_obj, "value", relay->last_change_time / 1000); // ms
            cJSON_AddStringToObject(timestamp_obj, "type", "realtime");
            cJSON_AddItemToObject(relay_obj, "timestamp", timestamp_obj);
        }
        
        cJSON_AddItemToObject(root, relay->relay_id, relay_obj);
    }
    
    char *json_string = cJSON_Print(root);
    cJSON_Delete(root);
    
    if (!json_string) {
        return ESP_ERR_NO_MEM;
    }
    
    if (strlen(json_string) >= buffer_size) {
        free(json_string);
        return ESP_ERR_INVALID_SIZE;
    }
    
    strcpy(json_buffer, json_string);
    free(json_string);
    
    return ESP_OK;
}

// Establecer nombre personalizado
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
        
        esp_err_t ret = save_relay_config();
        xSemaphoreGive(ctx->config_mutex);
        
        if (ret == ESP_OK) {
            ESP_LOGI(TAG, "Relay %s name changed to: %s", relay_id, name);
        }
        
        return ret;
    }
    
    return ESP_FAIL;
}

// Activar/desactivar relay
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
        ctx->relays[index].is_active = active;
        
        esp_err_t ret = save_relay_config();
        xSemaphoreGive(ctx->config_mutex);
        
        if (ret == ESP_OK) {
            ESP_LOGI(TAG, "Relay %s %s", relay_id, active ? "ACTIVATED" : "DEACTIVATED");
        }
        
        return ret;
    }
    
    return ESP_FAIL;
}

// Establecer tipo de contacto
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
        relay->contact_type = contact_type;
        
        // Recalcular estado actual con nueva lógica
        int current_gpio = gpio_get_level(relay->gpio_pin);
        relay->current_state = gpio_to_logical_state(current_gpio, contact_type);
        
        esp_err_t ret = save_relay_config();
        xSemaphoreGive(ctx->config_mutex);
        
        if (ret == ESP_OK) {
            ESP_LOGI(TAG, "Relay %s contact type changed to: %s", relay_id, contact_type_to_string(contact_type));
        }
        
        return ret;
    }
    
    return ESP_FAIL;
}

// Procesar comando MQTT
esp_err_t relay_manager_process_mqtt_command(const char *command_json) {
    if (!command_json) {
        return ESP_ERR_INVALID_ARG;
    }
    
    relay_manager_context_t *ctx = &s_relay_ctx;
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    ctx->mqtt_commands_processed++;
    
    cJSON *json = cJSON_Parse(command_json);
    if (!json) {
        ESP_LOGE(TAG, "Invalid JSON command");
        return ESP_ERR_INVALID_ARG;
    }
    
    cJSON *command = cJSON_GetObjectItem(json, "command");
    if (!command || !cJSON_IsString(command)) {
        cJSON_Delete(json);
        return ESP_ERR_INVALID_ARG;
    }
    
    esp_err_t ret = ESP_FAIL;
    const char *cmd_str = command->valuestring;
    
    ESP_LOGI(TAG, "Processing MQTT command: %s", cmd_str);
    
    if (strcmp(cmd_str, "set_name") == 0) {
        cJSON *relay_id = cJSON_GetObjectItem(json, "relay_id");
        cJSON *name = cJSON_GetObjectItem(json, "name");
        
        if (relay_id && cJSON_IsString(relay_id) && name && cJSON_IsString(name)) {
            ret = relay_manager_set_name(relay_id->valuestring, name->valuestring);
        }
    }
    else if (strcmp(cmd_str, "set_active") == 0) {
        cJSON *relay_id = cJSON_GetObjectItem(json, "relay_id");
        cJSON *active = cJSON_GetObjectItem(json, "active");
        
        if (relay_id && cJSON_IsString(relay_id) && active && cJSON_IsBool(active)) {
            ret = relay_manager_set_active(relay_id->valuestring, cJSON_IsTrue(active));
        }
    }
    else if (strcmp(cmd_str, "set_contact_type") == 0) {
        cJSON *relay_id = cJSON_GetObjectItem(json, "relay_id");
        cJSON *contact_type = cJSON_GetObjectItem(json, "contact_type");
        
        if (relay_id && cJSON_IsString(relay_id) && contact_type && cJSON_IsString(contact_type)) {
            relay_contact_type_t type = string_to_contact_type(contact_type->valuestring);
            ret = relay_manager_set_contact_type(relay_id->valuestring, type);
        }
    }
    else if (strcmp(cmd_str, "get_config") == 0) {
        ret = ESP_OK; // El callback manejará la respuesta
    }
    else {
        ESP_LOGW(TAG, "Unknown command: %s", cmd_str);
        ret = ESP_ERR_NOT_SUPPORTED;
    }
    
    cJSON_Delete(json);
    
    // Llamar callback MQTT si está configurado
    if (ctx->mqtt_callback) {
        ctx->mqtt_callback("relay_config", command_json, ctx->mqtt_callback_user_data);
    }
    
    return ret;
}

// Verificar todos los estados
esp_err_t relay_manager_check_all_states(bool force_report) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    int64_t current_time = esp_timer_get_time();
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        if (!relay->is_active) {
            continue;
        }
        
        // Leer estado actual del GPIO
        int gpio_level = gpio_get_level(relay->gpio_pin);
        relay_state_t current_state = gpio_to_logical_state(gpio_level, relay->contact_type);
        
        // Si hay cambio o se fuerza el reporte
        if (current_state != relay->current_state || force_report) {
            relay_state_t old_state = relay->current_state;
            relay->current_state = current_state;
            relay->last_change_time = current_time;
            relay->last_report_time = current_time;
            
            // Generar evento si hay callback
            if (ctx->state_callback) {
                relay_event_t event = {
                    .gpio_pin = relay->gpio_pin,
                    .old_state = old_state,
                    .new_state = current_state,
                    .timestamp = current_time,
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
    
    return ESP_OK;
}

// Funciones de utilidad
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

// Obtener configuración completa en JSON
esp_err_t relay_manager_get_config_json(char *json_buffer, size_t buffer_size) {
    if (!json_buffer || buffer_size == 0) {
        return ESP_ERR_INVALID_ARG;
    }
    
    relay_manager_context_t *ctx = &s_relay_ctx;
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    cJSON *root = cJSON_CreateObject();
    if (!root) {
        return ESP_ERR_NO_MEM;
    }
    
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        relay_config_t *relay = &ctx->relays[i];
        
        cJSON *relay_obj = cJSON_CreateObject();
        if (!relay_obj) {
            cJSON_Delete(root);
            return ESP_ERR_NO_MEM;
        }
        
        cJSON_AddNumberToObject(relay_obj, "pin", relay->gpio_pin);
        cJSON_AddBoolToObject(relay_obj, "active", relay->is_active);
        cJSON_AddStringToObject(relay_obj, "name", relay->name);
        cJSON_AddStringToObject(relay_obj, "contact_type", contact_type_to_string(relay->contact_type));
        
        cJSON_AddItemToObject(root, relay->relay_id, relay_obj);
    }
    
    char *json_string = cJSON_Print(root);
    cJSON_Delete(root);
    
    if (!json_string) {
        return ESP_ERR_NO_MEM;
    }
    
    if (strlen(json_string) >= buffer_size) {
        free(json_string);
        return ESP_ERR_INVALID_SIZE;
    }
    
    strcpy(json_buffer, json_string);
    free(json_string);
    
    return ESP_OK;
}

// Obtener diagnósticos
esp_err_t relay_manager_get_diagnostics_json(char *json_buffer, size_t buffer_size) {
    if (!json_buffer || buffer_size == 0) {
        return ESP_ERR_INVALID_ARG;
    }
    
    relay_manager_context_t *ctx = &s_relay_ctx;
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    cJSON *root = cJSON_CreateObject();
    if (!root) {
        return ESP_ERR_NO_MEM;
    }
    
    cJSON_AddNumberToObject(root, "total_events", ctx->total_events_processed);
    cJSON_AddNumberToObject(root, "filtered_events", ctx->debounce_filtered_events);
    cJSON_AddNumberToObject(root, "mqtt_commands", ctx->mqtt_commands_processed);
    cJSON_AddNumberToObject(root, "active_relays", 0); // Será calculado
    
    // Contar relays activos
    int active_count = 0;
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        if (ctx->relays[i].is_active) {
            active_count++;
        }
    }
    cJSON_SetNumberValue(cJSON_GetObjectItem(root, "active_relays"), active_count);
    
    char *json_string = cJSON_Print(root);
    cJSON_Delete(root);
    
    if (!json_string) {
        return ESP_ERR_NO_MEM;
    }
    
    if (strlen(json_string) >= buffer_size) {
        free(json_string);
        return ESP_ERR_INVALID_SIZE;
    }
    
    strcpy(json_buffer, json_string);
    free(json_string);
    
    return ESP_OK;
}

// Deinicialización
esp_err_t relay_manager_deinit(void) {
    relay_manager_context_t *ctx = &s_relay_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Deinitializing Relay Manager");
    
    // Eliminar task
    if (ctx->event_task_handle) {
        vTaskDelete(ctx->event_task_handle);
        ctx->event_task_handle = NULL;
    }
    
    // Remover ISR handlers
    for (int i = 0; i < RELAY_MANAGER_MAX_RELAYS; i++) {
        gpio_isr_handler_remove(ctx->relays[i].gpio_pin);
    }
    
    // Liberar recursos FreeRTOS
    if (ctx->gpio_event_queue) {
        vQueueDelete(ctx->gpio_event_queue);
        ctx->gpio_event_queue = NULL;
    }
    
    if (ctx->config_mutex) {
        vSemaphoreDelete(ctx->config_mutex);
        ctx->config_mutex = NULL;
    }
    
    ctx->initialized = false;
    ESP_LOGI(TAG, "Relay Manager deinitialized");
    
    return ESP_OK;
}