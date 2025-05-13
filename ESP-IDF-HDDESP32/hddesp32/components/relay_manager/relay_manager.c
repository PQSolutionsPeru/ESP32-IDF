#include "relay_manager.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "esp_timer.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "driver/gpio.h"
#include "mqtt_manager.h"
#include "esp32_id_manager.h"

#define TAG "RELAY_MGR"

// Configuración por defecto de los relays
static const relay_config_t default_relay_config[RELAY_COUNT] = {
    {GPIO_NUM_32, "relay_1", "Alarma",     RELAY_CONTACT_TYPE_NO, true,  100, 2000},
    {GPIO_NUM_33, "relay_2", "Problema",   RELAY_CONTACT_TYPE_NO, true,  100, 2000},
    {GPIO_NUM_25, "relay_3", "Supervision", RELAY_CONTACT_TYPE_NO, true,  100, 2000},
    {GPIO_NUM_26, "relay_4", "Relay 4",    RELAY_CONTACT_TYPE_NO, false, 100, 2000},
    {GPIO_NUM_27, "relay_5", "Relay 5",    RELAY_CONTACT_TYPE_NO, false, 100, 2000},
    {GPIO_NUM_14, "relay_6", "Relay 6",    RELAY_CONTACT_TYPE_NO, false, 100, 2000}
};

// Estructura para manejar el estado de cada relay
typedef struct {
    relay_config_t config;
    relay_status_t current_status;
    int64_t last_change_time;
    int64_t last_report_time;
    bool state_changed;
    uint8_t last_pin_value;
} relay_state_t;

// Estructura del contexto del relay manager
typedef struct {
    bool initialized;
    relay_state_t relays[RELAY_COUNT];
    SemaphoreHandle_t mutex;
    QueueHandle_t event_queue;
    TaskHandle_t task_handle;
    relay_state_change_callback_t state_callback;
    void *callback_user_data;
    nvs_handle_t nvs_handle;
    bool config_modified;
} relay_manager_context_t;

// Evento para la cola
typedef struct {
    gpio_num_t pin;
    uint8_t pin_value;
    int64_t timestamp;
} relay_event_t;

// Instancia única del contexto
static relay_manager_context_t s_relay_ctx = {0};

// Función para determinar el estado lógico basado en el valor del pin y tipo de contacto
static relay_status_t get_logical_state(uint8_t pin_value, relay_contact_type_t contact_type) {
    if (contact_type == RELAY_CONTACT_TYPE_NC) {
        // NC: pin=1 (abierto) -> OK, pin=0 (cerrado) -> DISC
        return (pin_value == 1) ? RELAY_STATUS_OK : RELAY_STATUS_DISC;
    } else {
        // NO: pin=0 (cerrado) -> OK, pin=1 (abierto) -> DISC
        return (pin_value == 0) ? RELAY_STATUS_OK : RELAY_STATUS_DISC;
    }
}

// Obtener el nombre del estado como string
static const char* get_state_string(relay_status_t status) {
    return (status == RELAY_STATUS_OK) ? RELAY_STATE_OK : RELAY_STATE_DISC;
}

// Buscar relay por ID
static relay_state_t* find_relay_by_id(const char *relay_id) {
    if (!relay_id) return NULL;
    
    for (int i = 0; i < RELAY_COUNT; i++) {
        if (strcmp(s_relay_ctx.relays[i].config.relay_id, relay_id) == 0) {
            return &s_relay_ctx.relays[i];
        }
    }
    return NULL;
}

// Buscar relay por pin
static relay_state_t* find_relay_by_pin(gpio_num_t pin) {
    for (int i = 0; i < RELAY_COUNT; i++) {
        if (s_relay_ctx.relays[i].config.gpio_pin == pin) {
            return &s_relay_ctx.relays[i];
        }
    }
    return NULL;
}

// ISR para interrupciones GPIO
static void IRAM_ATTR gpio_isr_handler(void* arg) {
    gpio_num_t pin = (gpio_num_t)arg;
    relay_state_t *relay = find_relay_by_pin(pin);
    
    if (!relay || !relay->config.is_active) {
        return;
    }
    
    relay_event_t event = {
        .pin = pin,
        .pin_value = gpio_get_level(pin),
        .timestamp = esp_timer_get_time()
    };
    
    // Enviar evento a la cola desde ISR
    xQueueSendFromISR(s_relay_ctx.event_queue, &event, NULL);
}

// Publicar estado del relay vía MQTT
static esp_err_t publish_relay_state(relay_state_t *relay) {
    char topic[256];
    char data[512];
    char esp32_id[ESP32_ID_LENGTH + 1];
    
    // Obtener ESP32 ID
    if (esp32_id_manager_get_id(esp32_id, sizeof(esp32_id)) != ESP_OK) {
        ESP_LOGE(TAG, "Failed to get ESP32 ID");
        return ESP_FAIL;
    }
    
    // TODO: Obtener client_id y panel_id desde MQTT Manager cuando esté configurado
    // Por ahora usar valores temporales o esperar configuración
    
    // Construir tópico
    snprintf(topic, sizeof(topic), "clients/%s/panels/%s", 
             "client_id", "panel_id"); // TODO: Usar valores reales
    
    // Construir mensaje JSON
    snprintf(data, sizeof(data),
             "{\"esp32_id\":\"%s\",\"relay_id\":\"%s\",\"relay_name\":\"%s\","
             "\"contact_type\":\"%s\",\"state\":\"%s\",\"timestamp\":{\"value\":%lld,\"type\":\"realtime\"}}",
             esp32_id,
             relay->config.relay_id,
             relay->config.custom_name,
             relay->config.contact_type == RELAY_CONTACT_TYPE_NO ? "NO" : "NC",
             get_state_string(relay->current_status),
             relay->last_change_time / 1000); // Convertir a ms
    
    // Publicar con QoS 1
    return mqtt_manager_publish(topic, data, -1, 1, false);
}

// Tarea principal del relay manager
static void relay_manager_task(void *pvParameter) {
    relay_event_t event;
    
    ESP_LOGI(TAG, "Relay manager task started");
    
    while (1) {
        // Esperar evento con timeout de 100ms
        if (xQueueReceive(s_relay_ctx.event_queue, &event, pdMS_TO_TICKS(100)) == pdTRUE) {
            relay_state_t *relay = find_relay_by_pin(event.pin);
            
            if (!relay || !relay->config.is_active) {
                continue;
            }
            
            // Aplicar debounce
            int64_t time_since_last = event.timestamp - relay->last_change_time;
            if (time_since_last < relay->config.debounce_time_ms * 1000) {
                continue;
            }
            
            // Calcular estado lógico
            relay_status_t new_status = get_logical_state(event.pin_value, relay->config.contact_type);
            
            // Verificar si cambió el estado
            if (new_status != relay->current_status) {
                ESP_LOGI(TAG, "Relay %s (%s) state changed: %s -> %s",
                         relay->config.relay_id,
                         relay->config.custom_name,
                         get_state_string(relay->current_status),
                         get_state_string(new_status));
                
                // Actualizar estado
                if (xSemaphoreTake(s_relay_ctx.mutex, portMAX_DELAY) == pdTRUE) {
                    relay->current_status = new_status;
                    relay->last_change_time = event.timestamp;
                    relay->state_changed = true;
                    relay->last_pin_value = event.pin_value;
                    xSemaphoreGive(s_relay_ctx.mutex);
                }
                
                // Verificar intervalo mínimo de reporte
                int64_t time_since_report = event.timestamp - relay->last_report_time;
                if (time_since_report >= relay->config.min_report_interval_ms * 1000) {
                    // Publicar cambio vía MQTT
                    if (mqtt_manager_is_connected()) {
                        publish_relay_state(relay);
                        relay->last_report_time = event.timestamp;
                    }
                    
                    // Notificar callback si existe
                    if (s_relay_ctx.state_callback) {
                        s_relay_ctx.state_callback(relay->config.relay_id, new_status, 
                                                  s_relay_ctx.callback_user_data);
                    }
                }
            }
        }
        
        // Procesar otras tareas periódicas (guardar configuración si cambió)
        if (s_relay_ctx.config_modified) {
            relay_manager_save_config();
            s_relay_ctx.config_modified = false;
        }
    }
}

// Configurar un pin GPIO para relay
static esp_err_t configure_relay_gpio(gpio_num_t pin) {
    gpio_config_t io_conf = {
        .pin_bit_mask = (1ULL << pin),
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_ANYEDGE
    };
    
    esp_err_t ret = gpio_config(&io_conf);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to configure GPIO %d: %s", pin, esp_err_to_name(ret));
        return ret;
    }
    
    // Instalar servicio de ISR
    static bool isr_service_installed = false;
    if (!isr_service_installed) {
        ret = gpio_install_isr_service(0);
        if (ret != ESP_OK && ret != ESP_ERR_INVALID_STATE) {
            ESP_LOGE(TAG, "Failed to install ISR service: %s", esp_err_to_name(ret));
            return ret;
        }
        isr_service_installed = true;
    }
    
    // Añadir handler de ISR
    ret = gpio_isr_handler_add(pin, gpio_isr_handler, (void*)pin);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to add ISR handler for GPIO %d: %s", pin, esp_err_to_name(ret));
        return ret;
    }
    
    return ESP_OK;
}

// Inicializar el relay manager
esp_err_t relay_manager_init(void) {
    if (s_relay_ctx.initialized) {
        ESP_LOGW(TAG, "Relay manager already initialized");
        return ESP_OK;
    }
    
    ESP_LOGI(TAG, "Initializing relay manager");
    
    // Crear mutex
    s_relay_ctx.mutex = xSemaphoreCreateMutex();
    if (!s_relay_ctx.mutex) {
        ESP_LOGE(TAG, "Failed to create mutex");
        return ESP_ERR_NO_MEM;
    }
    
    // Crear cola de eventos
    s_relay_ctx.event_queue = xQueueCreate(20, sizeof(relay_event_t));
    if (!s_relay_ctx.event_queue) {
        vSemaphoreDelete(s_relay_ctx.mutex);
        ESP_LOGE(TAG, "Failed to create event queue");
        return ESP_ERR_NO_MEM;
    }
    
    // Inicializar NVS para este módulo
    esp_err_t ret = nvs_open("relay_config", NVS_READWRITE, &s_relay_ctx.nvs_handle);
    if (ret != ESP_OK) {
        ESP_LOGW(TAG, "Failed to open NVS namespace: %s", esp_err_to_name(ret));
    }
    
    // Cargar configuración por defecto
    memcpy(s_relay_ctx.relays, default_relay_config, sizeof(default_relay_config));
    
    // Intentar cargar configuración desde NVS
    relay_manager_load_config();
    
    // Configurar GPIO para cada relay
    for (int i = 0; i < RELAY_COUNT; i++) {
        relay_state_t *relay = &s_relay_ctx.relays[i];
        
        ret = configure_relay_gpio(relay->config.gpio_pin);
        if (ret != ESP_OK) {
            ESP_LOGE(TAG, "Failed to configure relay %s on pin %d", 
                     relay->config.relay_id, relay->config.gpio_pin);
            continue;
        }
        
        // Leer estado inicial
        uint8_t pin_value = gpio_get_level(relay->config.gpio_pin);
        relay->current_status = get_logical_state(pin_value, relay->config.contact_type);
        relay->last_pin_value = pin_value;
        relay->last_change_time = esp_timer_get_time();
        relay->last_report_time = 0;
        relay->state_changed = false;
        
        ESP_LOGI(TAG, "Relay %s (%s) initialized on pin %d - %s, contact type: %s, initial state: %s",
                 relay->config.relay_id,
                 relay->config.custom_name,
                 relay->config.gpio_pin,
                 relay->config.is_active ? "ACTIVE" : "INACTIVE",
                 relay->config.contact_type == RELAY_CONTACT_TYPE_NO ? "NO" : "NC",
                 get_state_string(relay->current_status));
    }
    
    // Crear tarea de procesamiento
    xTaskCreate(relay_manager_task, "relay_manager", 4096, NULL, 10, &s_relay_ctx.task_handle);
    
    s_relay_ctx.initialized = true;
    ESP_LOGI(TAG, "Relay manager initialized successfully");
    
    return ESP_OK;
}

// Configurar callback de cambio de estado
esp_err_t relay_manager_set_state_callback(relay_state_change_callback_t callback, void *user_data) {
    if (!s_relay_ctx.initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (xSemaphoreTake(s_relay_ctx.mutex, portMAX_DELAY) == pdTRUE) {
        s_relay_ctx.state_callback = callback;
        s_relay_ctx.callback_user_data = user_data;
        xSemaphoreGive(s_relay_ctx.mutex);
    }
    
    return ESP_OK;
}

// Obtener estado de un relay
esp_err_t relay_manager_get_state(gpio_num_t pin, relay_status_t *status) {
    if (!s_relay_ctx.initialized || !status) {
        return ESP_ERR_INVALID_ARG;
    }
    
    relay_state_t *relay = find_relay_by_pin(pin);
    if (!relay) {
        return ESP_ERR_NOT_FOUND;
    }
    
    if (xSemaphoreTake(s_relay_ctx.mutex, portMAX_DELAY) == pdTRUE) {
        *status = relay->current_status;
        xSemaphoreGive(s_relay_ctx.mutex);
    }
    
    return ESP_OK;
}

// Obtener estado de todos los relays activos
esp_err_t relay_manager_get_all_states(relay_status_info_t *states, size_t count, size_t *actual_count) {
    if (!s_relay_ctx.initialized || !states || !actual_count) {
        return ESP_ERR_INVALID_ARG;
    }
    
    size_t active_count = 0;
    
    if (xSemaphoreTake(s_relay_ctx.mutex, portMAX_DELAY) == pdTRUE) {
        for (int i = 0; i < RELAY_COUNT && active_count < count; i++) {
            relay_state_t *relay = &s_relay_ctx.relays[i];
            
            if (relay->config.is_active) {
                relay_status_info_t *info = &states[active_count];
                
                strcpy(info->relay_id, relay->config.relay_id);
                strcpy(info->name, relay->config.custom_name);
                info->status = relay->current_status;
                info->pin = relay->config.gpio_pin;
                info->contact_type = relay->config.contact_type;
                info->timestamp = relay->last_change_time;
                
                active_count++;
            }
        }
        xSemaphoreGive(s_relay_ctx.mutex);
    }
    
    *actual_count = active_count;
    ESP_LOGI(TAG, "Reporting %d active relays", active_count);
    
    return ESP_OK;
}

// Establecer nombre personalizado
esp_err_t relay_manager_set_name(const char *relay_id, const char *name) {
    if (!s_relay_ctx.initialized || !relay_id || !name) {
        return ESP_ERR_INVALID_ARG;
    }
    
    relay_state_t *relay = find_relay_by_id(relay_id);
    if (!relay) {
        return ESP_ERR_NOT_FOUND;
    }
    
    if (xSemaphoreTake(s_relay_ctx.mutex, portMAX_DELAY) == pdTRUE) {
        strncpy(relay->config.custom_name, name, sizeof(relay->config.custom_name) - 1);
        relay->config.custom_name[sizeof(relay->config.custom_name) - 1] = '\0';
        s_relay_ctx.config_modified = true;
        xSemaphoreGive(s_relay_ctx.mutex);
    }
    
    ESP_LOGI(TAG, "Set name for %s to '%s'", relay_id, name);
    return ESP_OK;
}

// Activar/desactivar relay
esp_err_t relay_manager_set_active(const char *relay_id, bool active) {
    if (!s_relay_ctx.initialized || !relay_id) {
        return ESP_ERR_INVALID_ARG;
    }
    
    relay_state_t *relay = find_relay_by_id(relay_id);
    if (!relay) {
        return ESP_ERR_NOT_FOUND;
    }
    
    if (xSemaphoreTake(s_relay_ctx.mutex, portMAX_DELAY) == pdTRUE) {
        relay->config.is_active = active;
        s_relay_ctx.config_modified = true;
        xSemaphoreGive(s_relay_ctx.mutex);
    }
    
    ESP_LOGI(TAG, "Set %s to %s", relay_id, active ? "ACTIVE" : "INACTIVE");
    return ESP_OK;
}

// Establecer tipo de contacto
esp_err_t relay_manager_set_contact_type(const char *relay_id, relay_contact_type_t contact_type) {
    if (!s_relay_ctx.initialized || !relay_id) {
        return ESP_ERR_INVALID_ARG;
    }
    
    relay_state_t *relay = find_relay_by_id(relay_id);
    if (!relay) {
        return ESP_ERR_NOT_FOUND;
    }
    
    if (xSemaphoreTake(s_relay_ctx.mutex, portMAX_DELAY) == pdTRUE) {
        relay->config.contact_type = contact_type;
        
        // Recalcular estado actual con el nuevo tipo de contacto
        uint8_t pin_value = gpio_get_level(relay->config.gpio_pin);
        relay_status_t new_status = get_logical_state(pin_value, contact_type);
        
        if (new_status != relay->current_status) {
            relay->current_status = new_status;
            relay->state_changed = true;
        }
        
        s_relay_ctx.config_modified = true;
        xSemaphoreGive(s_relay_ctx.mutex);
    }
    
    ESP_LOGI(TAG, "Set contact type for %s to %s", 
             relay_id, contact_type == RELAY_CONTACT_TYPE_NO ? "NO" : "NC");
    return ESP_OK;
}

// Obtener configuración completa
esp_err_t relay_manager_get_config(relay_config_t *configs, size_t count, size_t *actual_count) {
    if (!s_relay_ctx.initialized || !configs || !actual_count) {
        return ESP_ERR_INVALID_ARG;
    }
    
    size_t copy_count = (count < RELAY_COUNT) ? count : RELAY_COUNT;
    
    if (xSemaphoreTake(s_relay_ctx.mutex, portMAX_DELAY) == pdTRUE) {
        for (size_t i = 0; i < copy_count; i++) {
            memcpy(&configs[i], &s_relay_ctx.relays[i].config, sizeof(relay_config_t));
        }
        xSemaphoreGive(s_relay_ctx.mutex);
    }
    
    *actual_count = copy_count;
    return ESP_OK;
}

// Guardar configuración en NVS (continuación)
esp_err_t relay_manager_save_config(void) {
    if (!s_relay_ctx.initialized || !s_relay_ctx.nvs_handle) {
        return ESP_ERR_INVALID_STATE;
    }
    
    esp_err_t ret;
    
    if (xSemaphoreTake(s_relay_ctx.mutex, portMAX_DELAY) == pdTRUE) {
        // Crear blob con toda la configuración
        uint8_t config_blob[RELAY_COUNT * sizeof(relay_config_t)];
        size_t blob_size = 0;
        
        for (int i = 0; i < RELAY_COUNT; i++) {
            memcpy(&config_blob[blob_size], &s_relay_ctx.relays[i].config, sizeof(relay_config_t));
            blob_size += sizeof(relay_config_t);
        }
        
        // Guardar en NVS
        ret = nvs_set_blob(s_relay_ctx.nvs_handle, "relay_configs", config_blob, blob_size);
        if (ret != ESP_OK) {
            ESP_LOGE(TAG, "Failed to save relay config: %s", esp_err_to_name(ret));
        } else {
            ret = nvs_commit(s_relay_ctx.nvs_handle);
            if (ret != ESP_OK) {
                ESP_LOGE(TAG, "Failed to commit NVS: %s", esp_err_to_name(ret));
            } else {
                ESP_LOGI(TAG, "Relay configuration saved successfully");
            }
        }
        
        xSemaphoreGive(s_relay_ctx.mutex);
    }
    
    return ret;
}

// Cargar configuración desde NVS
esp_err_t relay_manager_load_config(void) {
    if (!s_relay_ctx.initialized || !s_relay_ctx.nvs_handle) {
        return ESP_ERR_INVALID_STATE;
    }
    
    esp_err_t ret;
    uint8_t config_blob[RELAY_COUNT * sizeof(relay_config_t)];
    size_t blob_size = sizeof(config_blob);
    
    ret = nvs_get_blob(s_relay_ctx.nvs_handle, "relay_configs", config_blob, &blob_size);
    if (ret == ESP_ERR_NVS_NOT_FOUND) {
        ESP_LOGI(TAG, "No saved configuration found, using defaults");
        return ESP_OK;
    } else if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to load relay config: %s", esp_err_to_name(ret));
        return ret;
    }
    
    // Verificar tamaño
    if (blob_size != RELAY_COUNT * sizeof(relay_config_t)) {
        ESP_LOGW(TAG, "Saved configuration size mismatch, using defaults");
        return ESP_ERR_INVALID_SIZE;
    }
    
    if (xSemaphoreTake(s_relay_ctx.mutex, portMAX_DELAY) == pdTRUE) {
        // Restaurar configuración
        size_t offset = 0;
        for (int i = 0; i < RELAY_COUNT; i++) {
            memcpy(&s_relay_ctx.relays[i].config, &config_blob[offset], sizeof(relay_config_t));
            offset += sizeof(relay_config_t);
        }
        
        ESP_LOGI(TAG, "Relay configuration loaded successfully");
        xSemaphoreGive(s_relay_ctx.mutex);
    }
    
    return ESP_OK;
}

// Procesar tareas periódicas
esp_err_t relay_manager_process(void) {
    if (!s_relay_ctx.initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    // Por ahora, la tarea principal maneja todo el procesamiento
    // Esta función puede usarse para verificar el estado del sistema
    // o forzar actualizaciones si es necesario
    
    return ESP_OK;
}

// Función helper para reportar estados iniciales
esp_err_t relay_manager_report_initial_states(void) {
    if (!s_relay_ctx.initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    char topic[256];
    char data[1024];
    char esp32_id[ESP32_ID_LENGTH + 1];
    char relay_states_json[512] = "";
    size_t json_len = 0;
    
    // Obtener ESP32 ID
    if (esp32_id_manager_get_id(esp32_id, sizeof(esp32_id)) != ESP_OK) {
        ESP_LOGE(TAG, "Failed to get ESP32 ID");
        return ESP_FAIL;
    }
    
    // Construir JSON con estados de todos los relays activos
    strcat(relay_states_json, "{");
    
    if (xSemaphoreTake(s_relay_ctx.mutex, portMAX_DELAY) == pdTRUE) {
        bool first = true;
        
        for (int i = 0; i < RELAY_COUNT; i++) {
            relay_state_t *relay = &s_relay_ctx.relays[i];
            
            if (relay->config.is_active) {
                if (!first) {
                    strcat(relay_states_json, ",");
                }
                
                char relay_json[128];
                snprintf(relay_json, sizeof(relay_json),
                         "\"%s\":{\"name\":\"%s\",\"status\":\"%s\",\"pin\":%d,"
                         "\"contact_type\":\"%s\",\"timestamp\":{\"value\":%lld,\"type\":\"realtime\"}}",
                         relay->config.relay_id,
                         relay->config.custom_name,
                         get_state_string(relay->current_status),
                         relay->config.gpio_pin,
                         relay->config.contact_type == RELAY_CONTACT_TYPE_NO ? "NO" : "NC",
                         relay->last_change_time / 1000);
                
                strcat(relay_states_json, relay_json);
                first = false;
            }
        }
        
        xSemaphoreGive(s_relay_ctx.mutex);
    }
    
    strcat(relay_states_json, "}");
    
    // Construir mensaje completo
    snprintf(data, sizeof(data),
             "{\"esp32_id\":\"%s\",\"relay_states\":%s,\"timestamp\":%lld,"
             "\"message_id\":\"init-%lld-%d\",\"type\":\"initial_status\"}",
             esp32_id,
             relay_states_json,
             esp_timer_get_time() / 1000,
             esp_timer_get_time() / 1000,
             esp_random() % 10000);
    
    // TODO: Usar tópico correcto cuando tengamos client_id y panel_id
    snprintf(topic, sizeof(topic), "system/relay_status/%s", esp32_id);
    
    return mqtt_manager_publish(topic, data, -1, 1, false);
}