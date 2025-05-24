/* Deshabilitamos temporalmente la advertencia de truncamiento para este archivo */
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wformat-truncation"

#include "mqtt_manager.h"
#include <string.h>
#include <stdio.h>
#include <inttypes.h> // Para PRIu32 y otros macros de formato
#include <errno.h>    // Para strerror()
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "freertos/semphr.h"
#include "freertos/queue.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h" // Para esp_timer_get_time()
#include "mqtt_client.h"
#include "esp_tls.h"
#include "mqtt_ssl_setup.h"
#include "esp_random.h"
#include "config_manager.h"
#include "esp32_id_manager.h"
#include "wifi_manager.h"
#include "time_manager.h" // Añadido Time Manager
#include "esp_task_wdt.h"

#define TAG "MQTT_MGR"

// Definición de bits para el grupo de eventos
#define MQTT_CONNECTED_BIT BIT0
#define MQTT_DISCONNECTED_BIT BIT1
#define MQTT_ERROR_BIT BIT2

// Valores por defecto
#define DEFAULT_BROKER "node02.myqtthub.com"
#define DEFAULT_PORT 8883
#define DEFAULT_KEEPALIVE 120
#define DEFAULT_RECONNECT_TIMEOUT_MS 10000
#define DEFAULT_NETWORK_TIMEOUT_MS 20000
#define DEFAULT_MAX_RETRIES 5
#define DEFAULT_BUFFER_SIZE 1024
#define DEFAULT_MAX_QUEUE_SIZE 10
#define DEFAULT_STATUS_INTERVAL_MS 300000  // 5 minutos

// Definición del tamaño de los tópicos para evitar desbordamientos
#define TOPIC_BUFFER_SIZE 256

// Estructura para la cola de mensajes pendientes
typedef struct {
    char topic[TOPIC_BUFFER_SIZE];
    char data[DEFAULT_BUFFER_SIZE];
    int data_len;
    int qos;
    bool retain;
} mqtt_pending_message_t;

// Estructura para manejar el estado del MQTT Manager
typedef struct {
    mqtt_manager_state_t state;
    esp_mqtt_client_handle_t client;
    EventGroupHandle_t event_group;
    SemaphoreHandle_t mutex;
    QueueHandle_t pending_messages;
    
    char broker_url[128];
    int port;
    bool use_ssl;
    char client_id[32];
    char username[64];
    char password[64];
    char lwt_topic[TOPIC_BUFFER_SIZE];
    char lwt_message[256];
    int lwt_qos;
    bool lwt_retain;
    
    char esp32_id[ESP32_ID_LENGTH + 1];
    char mac_address[ESP32_MAC_STR_LENGTH + 1];
    
    bool was_connected;
    int reconnect_attempts;
    int64_t last_reconnect_time;
    int64_t last_activity_time;
    int64_t last_heartbeat_time;
    
    mqtt_manager_message_callback_t message_callback;
    void *message_user_data;
    mqtt_manager_state_callback_t state_callback;
    void *state_user_data;
    
    // Configuración del panel
    char panel_id[32];
    char client_panel_id[32];
    
    // Tópicos
    char status_topic[TOPIC_BUFFER_SIZE];
    char relays_topic[TOPIC_BUFFER_SIZE];
    char config_topic[TOPIC_BUFFER_SIZE];
    char response_topic[TOPIC_BUFFER_SIZE];
} mqtt_manager_context_t;

// Instancia única del contexto del MQTT Manager (patrón singleton)
static mqtt_manager_context_t s_mqtt_manager_ctx = {0};

// Función para generar un ID aleatorio para mensajes
static void generate_message_id(char *buffer, size_t size) {
    uint32_t random = esp_random();
    // Usar PRIu32 para uint32_t en lugar de %u
    snprintf(buffer, size, "msg_%" PRIu32, random);
}

// Handler de eventos MQTT ULTRA-SIMPLIFICADO para ESP32 4MB
static void mqtt_event_handler(void *handler_args, esp_event_base_t base, int32_t event_id, void *event_data) {
    esp_mqtt_event_handle_t event = (esp_mqtt_event_handle_t)event_data;
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    // Variables locales mínimas - sin arrays grandes
    ctx->last_activity_time = esp_timer_get_time() / 1000;
    
    switch (event->event_id) {
        case MQTT_EVENT_CONNECTED:
            ESP_LOGI(TAG, "MQTT connected");
            
            // Operación atómica mínima
            if (xSemaphoreTake(ctx->mutex, 10) == pdTRUE) {
                ctx->state = MQTT_MANAGER_STATE_CONNECTED;
                ctx->was_connected = true;
                ctx->reconnect_attempts = 0;
                xSemaphoreGive(ctx->mutex);
            }
            
            // Notificación simple de eventos
            xEventGroupClearBits(ctx->event_group, MQTT_DISCONNECTED_BIT | MQTT_ERROR_BIT);
            xEventGroupSetBits(ctx->event_group, MQTT_CONNECTED_BIT);
            
            // Callback sin parámetros complejos
            if (ctx->state_callback) {
                ctx->state_callback(ctx->state, ctx->state_user_data);
            }
            
            // Suscripciones mínimas después de un delay
            vTaskDelay(pdMS_TO_TICKS(500));
            if (strlen(ctx->esp32_id) > 0) {
                char config_topic[128];
                snprintf(config_topic, sizeof(config_topic), "esp32/config/%s", ctx->esp32_id);
                strncpy(ctx->config_topic, config_topic, sizeof(ctx->config_topic) - 1);
                ctx->config_topic[sizeof(ctx->config_topic) - 1] = '\0';
                mqtt_manager_subscribe(ctx->config_topic, 0);
                
                char reset_topic[128];
                snprintf(reset_topic, sizeof(reset_topic), "esp32/config/%s/reset", ctx->esp32_id);
                mqtt_manager_subscribe(reset_topic, 0);
            }
            
            // Enviar mensajes pendientes (máximo 2)
            mqtt_pending_message_t pending_msg;
            int pending_count = 0;
            while (xQueueReceive(ctx->pending_messages, &pending_msg, 0) == pdTRUE && pending_count < 2) {
                esp_mqtt_client_publish(event->client, pending_msg.topic, pending_msg.data, 
                                        pending_msg.data_len, pending_msg.qos, pending_msg.retain);
                pending_count++;
                vTaskDelay(pdMS_TO_TICKS(50));
            }
            
            // Enviar network info
            vTaskDelay(pdMS_TO_TICKS(500));
            mqtt_manager_send_network_info();
            break;
            
        case MQTT_EVENT_DISCONNECTED:
            ESP_LOGW(TAG, "MQTT disconnected");
            
            if (xSemaphoreTake(ctx->mutex, 10) == pdTRUE) {
                ctx->state = ctx->was_connected ? MQTT_MANAGER_STATE_RECONNECTING : MQTT_MANAGER_STATE_DISCONNECTED;
                xSemaphoreGive(ctx->mutex);
            }
            
            xEventGroupClearBits(ctx->event_group, MQTT_CONNECTED_BIT);
            xEventGroupSetBits(ctx->event_group, MQTT_DISCONNECTED_BIT);
            
            if (ctx->state_callback) {
                ctx->state_callback(ctx->state, ctx->state_user_data);
            }
            break;
            
        case MQTT_EVENT_SUBSCRIBED:
            ESP_LOGI(TAG, "MQTT subscribed, msg_id=%d", event->msg_id);
            break;
            
        case MQTT_EVENT_PUBLISHED:
            // Solo actualizar timestamp, sin logs verbosos
            if (xSemaphoreTake(ctx->mutex, 5) == pdTRUE) {
                ctx->last_activity_time = esp_timer_get_time() / 1000;
                xSemaphoreGive(ctx->mutex);
            }
            break;
            
        case MQTT_EVENT_DATA:
            ESP_LOGI(TAG, "MQTT data received");
            // Procesamiento MINIMALISTA de datos
            if (ctx->message_callback && event->topic_len < 128 && event->data_len < 256) {
                // Buffers estáticos PEQUEÑOS para evitar stack overflow
                static char mini_topic[128];
                static char mini_data[256];
                
                // Copiar solo si cabe en buffers pequeños
                if (event->topic_len < sizeof(mini_topic) && event->data_len < sizeof(mini_data)) {
                    memcpy(mini_topic, event->topic, event->topic_len);
                    mini_topic[event->topic_len] = '\0';
                    
                    memcpy(mini_data, event->data, event->data_len);
                    mini_data[event->data_len] = '\0';
                    
                    // Callback simple
                    ctx->message_callback(mini_topic, mini_data, event->data_len, ctx->message_user_data);
                }
            }
            
            if (xSemaphoreTake(ctx->mutex, 5) == pdTRUE) {
                ctx->last_activity_time = esp_timer_get_time() / 1000;
                xSemaphoreGive(ctx->mutex);
            }
            break;
            
        case MQTT_EVENT_ERROR:
            ESP_LOGE(TAG, "MQTT error");
            
            if (event->error_handle->error_type == MQTT_ERROR_TYPE_TCP_TRANSPORT) {
                if (event->error_handle->esp_tls_last_esp_err) {
                    ESP_LOGE(TAG, "TLS error: 0x%x", event->error_handle->esp_tls_last_esp_err);
                }
            }
            
            if (xSemaphoreTake(ctx->mutex, 5) == pdTRUE) {
                ctx->state = MQTT_MANAGER_STATE_ERROR;
                xSemaphoreGive(ctx->mutex);
            }
            
            xEventGroupClearBits(ctx->event_group, MQTT_CONNECTED_BIT);
            xEventGroupSetBits(ctx->event_group, MQTT_ERROR_BIT);
            
            if (ctx->state_callback) {
                ctx->state_callback(ctx->state, ctx->state_user_data);
            }
            break;
            
        default:
            // Sin logs para eventos menores
            break;
    }
}

// Función mqtt_manager_init() corregida
esp_err_t mqtt_manager_init(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    // Evitar inicialización múltiple
    if (ctx->event_group != NULL) {
        ESP_LOGW(TAG, "MQTT Manager already initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Initializing MQTT Manager");
    
    // Inicializar variables de estado
    ctx->state = MQTT_MANAGER_STATE_INIT;
    ctx->was_connected = false;
    ctx->reconnect_attempts = 0;
    ctx->last_activity_time = 0;
    ctx->last_heartbeat_time = 0;
    ctx->last_reconnect_time = 0;
    
    // Crear grupo de eventos
    ctx->event_group = xEventGroupCreate();
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "Failed to create event group");
        return ESP_ERR_NO_MEM;
    }
    
    // Crear mutex
    ctx->mutex = xSemaphoreCreateMutex();
    if (ctx->mutex == NULL) {
        vEventGroupDelete(ctx->event_group);
        ctx->event_group = NULL;
        ESP_LOGE(TAG, "Failed to create mutex");
        return ESP_ERR_NO_MEM;
    }
    
    // Crear cola de mensajes pendientes
    ctx->pending_messages = xQueueCreate(DEFAULT_MAX_QUEUE_SIZE, sizeof(mqtt_pending_message_t));
    if (ctx->pending_messages == NULL) {
        vSemaphoreDelete(ctx->mutex);
        vEventGroupDelete(ctx->event_group);
        ctx->mutex = NULL;
        ctx->event_group = NULL;
        ESP_LOGE(TAG, "Failed to create pending messages queue");
        return ESP_ERR_NO_MEM;
    }
    
    // Configurar valores por defecto - IMPORTANTE: Exactamente igual que en MicroPython
    strncpy(ctx->broker_url, DEFAULT_BROKER, sizeof(ctx->broker_url) - 1);
    ctx->broker_url[sizeof(ctx->broker_url) - 1] = '\0';
    ctx->port = DEFAULT_PORT;  // Debe ser 8883 para SSL como en MicroPython
    ctx->use_ssl = true;  // Importante: SSL debe estar activado
    ctx->lwt_qos = 1;     // QoS 1 para LWT (Last Will Testament)
    ctx->lwt_retain = false;
    
    ESP_LOGI(TAG, "MQTT Manager initialized with broker: %s:%d, SSL: %s", 
                ctx->broker_url, ctx->port, ctx->use_ssl ? "true" : "false");
    
    // Marcar como inicializado y desconectado
    ctx->state = MQTT_MANAGER_STATE_DISCONNECTED;
    xEventGroupSetBits(ctx->event_group, MQTT_DISCONNECTED_BIT);
    
    ESP_LOGI(TAG, "MQTT Manager initialized successfully");
    return ESP_OK;
}

// Configurar credenciales MQTT basadas en ESP32 ID (VERSIÓN OPTIMIZADA)
esp_err_t mqtt_manager_set_esp32_id(const char *esp32_id) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "MQTT Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (esp32_id == NULL || strlen(esp32_id) == 0) {
        ESP_LOGE(TAG, "Invalid ESP32 ID");
        return ESP_ERR_INVALID_ARG;
    }
    
    ESP_LOGI(TAG, "Setting MQTT credentials for ESP32 ID: %s", esp32_id);
    
    // Adquirir mutex para proteger la actualización de credenciales
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        // Guardar ESP32 ID
        strncpy(ctx->esp32_id, esp32_id, sizeof(ctx->esp32_id) - 1);
        ctx->esp32_id[sizeof(ctx->esp32_id) - 1] = '\0';
        
        // Obtener MAC address
        esp32_id_manager_get_mac(ctx->mac_address, sizeof(ctx->mac_address));
        
        // IMPORTANTE: Configurar client_id, username y password iguales al ESP32_ID
        strncpy(ctx->client_id, esp32_id, sizeof(ctx->client_id) - 1);
        ctx->client_id[sizeof(ctx->client_id) - 1] = '\0';
        
        strncpy(ctx->username, esp32_id, sizeof(ctx->username) - 1);
        ctx->username[sizeof(ctx->username) - 1] = '\0';
        
        strncpy(ctx->password, esp32_id, sizeof(ctx->password) - 1);
        ctx->password[sizeof(ctx->password) - 1] = '\0';
        
        // Configurar tópico LWT
        snprintf(ctx->lwt_topic, sizeof(ctx->lwt_topic), "system/status/%s", esp32_id);
        
        // Configurar mensaje LWT con timestamp real y hora formateada cuando está disponible
        char timestamp_str[32];
        if (time_manager_is_synchronized()) {
            // Usar tiempo real si está sincronizado
            time_manager_get_timestamp(timestamp_str, sizeof(timestamp_str));
            
            // Obtener hora formateada
            char time_str[32];
            time_manager_get_lima_time_str(time_str, sizeof(time_str));
            
            // Construir mensaje LWT con timestamp y hora formateada
            snprintf(ctx->lwt_message, sizeof(ctx->lwt_message),
                    "{\"esp32_id\":\"%s\",\"status\":\"OFFLINE\",\"type\":\"lwt\",\"timestamp\":%s,\"time\":\"%s\"}",
                    esp32_id, timestamp_str, time_str);
        } else {
            // Usar el timestamp del sistema como fallback
            snprintf(timestamp_str, sizeof(timestamp_str), "%lld", (long long)(esp_timer_get_time() / 1000));
            
            // Construir mensaje LWT sin hora formateada
            snprintf(ctx->lwt_message, sizeof(ctx->lwt_message),
                    "{\"esp32_id\":\"%s\",\"status\":\"OFFLINE\",\"type\":\"lwt\",\"timestamp\":%s}",
                    esp32_id, timestamp_str);
        }
        
        // Configurar tópico de configuración
        snprintf(ctx->config_topic, sizeof(ctx->config_topic), "esp32/config/%s", esp32_id);
        
        // Liberar mutex
        xSemaphoreGive(ctx->mutex);
    }
    
    ESP_LOGI(TAG, "MQTT credentials configured successfully with client_id=%s", ctx->client_id);
    return ESP_OK;
}

// Inicia conexión al broker MQTT
esp_err_t mqtt_manager_connect(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "MQTT Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (strlen(ctx->esp32_id) == 0) {
        ESP_LOGE(TAG, "ESP32 ID not set, call mqtt_manager_set_esp32_id first");
        return ESP_ERR_INVALID_STATE;
    }
    
    // Verificar si ya estamos conectados o conectando
    if (mqtt_manager_is_connected() || ctx->state == MQTT_MANAGER_STATE_CONNECTING) {
        ESP_LOGW(TAG, "Already connected or connecting to MQTT broker");
        return ESP_OK;
    }
    
    // Verificar si WiFi está conectado
    if (!wifi_manager_is_connected()) {
        ESP_LOGE(TAG, "WiFi not connected");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Connecting to MQTT broker: %s:%d", ctx->broker_url, ctx->port);
    
    // Adquirir mutex para proteger la configuración
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        // Actualizar estado
        ctx->state = MQTT_MANAGER_STATE_CONNECTING;
        xEventGroupClearBits(ctx->event_group, MQTT_CONNECTED_BIT | MQTT_ERROR_BIT);
        
        // Si hay un cliente existente, limpiarlo
        if (ctx->client != NULL) {
            esp_mqtt_client_destroy(ctx->client);
            ctx->client = NULL;
        }
        
        // *********** PREPARACIÓN DE MEMORIA PARA SSL SIN VERIFICACIÓN ***********
        ESP_LOGI(TAG, "Preparando memoria para negociación SSL");
        ESP_LOGI(TAG, "Memoria libre antes de limpieza: %ld bytes", esp_get_free_heap_size());
        
        // Verificar memoria mínima requerida
        if (esp_get_free_heap_size() < 30000) {
            ESP_LOGW(TAG, "Memoria baja antes de iniciar SSL (%ld bytes), puede causar problemas", 
                    esp_get_free_heap_size());
        }
        
        // Limpieza de memoria más suave para SSL sin verificación
        for (int i = 0; i < 5; i++) {
            heap_caps_check_integrity_all(true);
            ESP_LOGI(TAG, "Iteración de limpieza %d, memoria libre: %ld bytes", 
                    i, esp_get_free_heap_size());
            vTaskDelay(pdMS_TO_TICKS(100));
        }
        
        // Alimentar el watchdog
        #ifdef CONFIG_ESP_TASK_WDT_EN
        esp_task_wdt_reset();
        #endif
        
        ESP_LOGI(TAG, "Memoria libre después de limpieza: %ld bytes", esp_get_free_heap_size());
        
        // *********** CONFIGURACIÓN MQTT CON SSL SIN VERIFICACIÓN ***********
        
        // Construir URI completa
        char uri[256];
        if (ctx->use_ssl) {
            snprintf(uri, sizeof(uri), "mqtts://%s:%d", ctx->broker_url, ctx->port);
        } else {
            snprintf(uri, sizeof(uri), "mqtt://%s:%d", ctx->broker_url, ctx->port);
        }
        
        ESP_LOGI(TAG, "MQTT URI: %s", uri);
        ESP_LOGI(TAG, "Configuring SSL for MQTT connection");
        
        // Configurar cliente MQTT (ESP-IDF v5.4.1)
        esp_mqtt_client_config_t mqtt_cfg = {0};
        
        // Configuración de broker
        mqtt_cfg.broker.address.uri = uri;
        
        // Configuración de credenciales - EXACTAMENTE COMO MICROPYTHON
        mqtt_cfg.credentials.client_id = ctx->client_id;
        mqtt_cfg.credentials.username = ctx->username;
        mqtt_cfg.credentials.authentication.password = ctx->password;
        
        // Configuración de sesión
        mqtt_cfg.session.keepalive = DEFAULT_KEEPALIVE;
        mqtt_cfg.session.disable_clean_session = false;
        
        // Last Will Testament con timestamp actual
        if (time_manager_is_synchronized()) {
            char timestamp_str[32];
            time_manager_get_timestamp(timestamp_str, sizeof(timestamp_str));
            
            snprintf(ctx->lwt_message, sizeof(ctx->lwt_message),
                    "{\"esp32_id\":\"%s\",\"status\":\"OFFLINE\",\"type\":\"lwt\",\"timestamp\":%s}",
                    ctx->esp32_id, timestamp_str);
        }
        
        mqtt_cfg.session.last_will.topic = ctx->lwt_topic;
        mqtt_cfg.session.last_will.msg = ctx->lwt_message;
        mqtt_cfg.session.last_will.msg_len = strlen(ctx->lwt_message);
        mqtt_cfg.session.last_will.qos = ctx->lwt_qos;
        mqtt_cfg.session.last_will.retain = ctx->lwt_retain;
        
        // *********** APLICAR CONFIGURACIÓN SSL CERT_NONE ***********
        if (ctx->use_ssl) {
            esp_err_t ssl_ret = mqtt_ssl_setup_minimal_config(&mqtt_cfg);
            if (ssl_ret != ESP_OK) {
                ctx->state = MQTT_MANAGER_STATE_ERROR;
                xEventGroupSetBits(ctx->event_group, MQTT_ERROR_BIT);
                xSemaphoreGive(ctx->mutex);
                ESP_LOGE(TAG, "Failed to configure SSL: %s", esp_err_to_name(ssl_ret));
                return ssl_ret;
            }
        }
        
        // Mostrar información de configuración
        ESP_LOGI(TAG, "MQTT Config - Client ID: %s", mqtt_cfg.credentials.client_id);
        ESP_LOGI(TAG, "MQTT Config - Username: %s", mqtt_cfg.credentials.username);
        ESP_LOGI(TAG, "MQTT Config - Password: %s", ctx->password);
        ESP_LOGI(TAG, "MQTT Config - LWT Topic: %s", mqtt_cfg.session.last_will.topic);
        
        // Verificar memoria antes de crear cliente
        ESP_LOGI(TAG, "Free memory before MQTT client init: %ld bytes", esp_get_free_heap_size());
        
        // Crear cliente MQTT
        ctx->client = esp_mqtt_client_init(&mqtt_cfg);
        if (ctx->client == NULL) {
            ctx->state = MQTT_MANAGER_STATE_ERROR;
            xEventGroupSetBits(ctx->event_group, MQTT_ERROR_BIT);
            xSemaphoreGive(ctx->mutex);
            
            ESP_LOGE(TAG, "Failed to initialize MQTT client");
            return ESP_FAIL;
        }
        
        // Registrar handler de eventos
        esp_mqtt_client_register_event(ctx->client, ESP_EVENT_ANY_ID, mqtt_event_handler, NULL);
        
        // Iniciar cliente MQTT
        esp_err_t ret = esp_mqtt_client_start(ctx->client);
        if (ret != ESP_OK) {
            ESP_LOGE(TAG, "Failed to start MQTT client: %s (error code 0x%x)", esp_err_to_name(ret), ret);
            
            esp_mqtt_client_destroy(ctx->client);
            ctx->client = NULL;
            ctx->state = MQTT_MANAGER_STATE_ERROR;
            xEventGroupSetBits(ctx->event_group, MQTT_ERROR_BIT);
            xSemaphoreGive(ctx->mutex);
            
            return ret;
        }
        
        ESP_LOGI(TAG, "MQTT client started successfully, waiting for connection...");
        
        // Liberar mutex
        xSemaphoreGive(ctx->mutex);
    }
    
    // Notificar al callback
    if (ctx->state_callback) {
        ctx->state_callback(ctx->state, ctx->state_user_data);
    }
    
    // Esperar conexión con timeout
    EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                          MQTT_CONNECTED_BIT | MQTT_ERROR_BIT,
                                          pdFALSE, pdFALSE, pdMS_TO_TICKS(15000)); // 15 segundos
    
    if (bits & MQTT_CONNECTED_BIT) {
        ESP_LOGI(TAG, "Successfully connected to MQTT broker");
        return ESP_OK;
    } else if (bits & MQTT_ERROR_BIT) {
        ESP_LOGE(TAG, "Error connecting to MQTT broker");
        return ESP_FAIL;
    } else {
        ESP_LOGW(TAG, "Timeout waiting for MQTT connection");
        return ESP_ERR_TIMEOUT;
    }
}

// Desconecta del broker MQTT
esp_err_t mqtt_manager_disconnect(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "MQTT Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ctx->client == NULL) {
        ESP_LOGW(TAG, "MQTT client not connected");
        return ESP_OK;
    }
    
    ESP_LOGI(TAG, "Disconnecting from MQTT broker");
    
    // Adquirir mutex para proteger la operación
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        // Detener cliente MQTT
        esp_err_t ret = esp_mqtt_client_stop(ctx->client);
        if (ret != ESP_OK) {
            xSemaphoreGive(ctx->mutex);
            ESP_LOGE(TAG, "Failed to stop MQTT client: %s", esp_err_to_name(ret));
            return ret;
        }
        
        // Actualizar estado
        ctx->state = MQTT_MANAGER_STATE_DISCONNECTED;
        ctx->was_connected = false;
        xEventGroupClearBits(ctx->event_group, MQTT_CONNECTED_BIT | MQTT_ERROR_BIT);
        xEventGroupSetBits(ctx->event_group, MQTT_DISCONNECTED_BIT);
        
        // Liberar mutex
        xSemaphoreGive(ctx->mutex);
    }
    
    // Notificar al callback
    if (ctx->state_callback) {
        ctx->state_callback(ctx->state, ctx->state_user_data);
    }
    
    return ESP_OK;
}

// Comprueba si está conectado al broker MQTT
bool mqtt_manager_is_connected(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return false;
    }
    
    EventBits_t bits = xEventGroupGetBits(ctx->event_group);
    return (bits & MQTT_CONNECTED_BIT) != 0;
}

// Suscribe a un tópico MQTT
esp_err_t mqtt_manager_subscribe(const char *topic, int qos) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "MQTT Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ctx->client == NULL) {
        ESP_LOGE(TAG, "MQTT client not connected");
        return ESP_FAIL;
    }
    
    if (topic == NULL || strlen(topic) == 0) {
        ESP_LOGE(TAG, "Invalid topic");
        return ESP_ERR_INVALID_ARG;
    }
    
    // Validar QoS
    if (qos < 0 || qos > 2) {
        ESP_LOGW(TAG, "Invalid QoS value %d, using QoS 1", qos);
        qos = 1;
    }
    
    ESP_LOGI(TAG, "Subscribing to topic: %s with QoS %d", topic, qos);
    
    int msg_id = esp_mqtt_client_subscribe(ctx->client, topic, qos);
    if (msg_id < 0) {
        ESP_LOGE(TAG, "Failed to subscribe to topic: %s", topic);
        return ESP_FAIL;
    }
    
    ESP_LOGI(TAG, "Successfully subscribed to topic: %s, msg_id=%d", topic, msg_id);
    return ESP_OK;
}

// Desuscribe de un tópico MQTT
esp_err_t mqtt_manager_unsubscribe(const char *topic) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "MQTT Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ctx->client == NULL) {
        ESP_LOGE(TAG, "MQTT client not connected");
        return ESP_FAIL;
    }
    
    if (topic == NULL || strlen(topic) == 0) {
        ESP_LOGE(TAG, "Invalid topic");
        return ESP_ERR_INVALID_ARG;
    }
    
    ESP_LOGI(TAG, "Unsubscribing from topic: %s", topic);
    
    int msg_id = esp_mqtt_client_unsubscribe(ctx->client, topic);
    if (msg_id < 0) {
        ESP_LOGE(TAG, "Failed to unsubscribe from topic: %s", topic);
        return ESP_FAIL;
    }
    
    ESP_LOGI(TAG, "Successfully unsubscribed from topic: %s, msg_id=%d", topic, msg_id);
    return ESP_OK;
}

// Publica un mensaje MQTT
esp_err_t mqtt_manager_publish(const char *topic, const char *data, int data_len, int qos, bool retain) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "MQTT Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (topic == NULL || strlen(topic) == 0 || data == NULL) {
        ESP_LOGE(TAG, "Invalid topic or data");
        return ESP_ERR_INVALID_ARG;
    }
    
    // Calcular longitud automáticamente si es -1
    if (data_len < 0) {
        data_len = strlen(data);
    }
    
    // Validar QoS
    if (qos < 0 || qos > 2) {
        ESP_LOGW(TAG, "Invalid QoS value %d, using QoS 1", qos);
        qos = 1;
    }
    
    // Verificar longitud del mensaje
    if (data_len > DEFAULT_BUFFER_SIZE) {
        ESP_LOGE(TAG, "Message too large: %d bytes", data_len);
        return ESP_ERR_INVALID_SIZE;
    }
    
    // Si no estamos conectados, encolar el mensaje para enviarlo cuando nos conectemos
    if (!mqtt_manager_is_connected()) {
        ESP_LOGW(TAG, "Not connected to MQTT broker, enqueueing message for topic: %s", topic);
        
        mqtt_pending_message_t pending_msg;
        strncpy(pending_msg.topic, topic, sizeof(pending_msg.topic) - 1);
        pending_msg.topic[sizeof(pending_msg.topic) - 1] = '\0';
        
        // Copiar datos con límite de tamaño
        if (data_len > sizeof(pending_msg.data) - 1) {
            data_len = sizeof(pending_msg.data) - 1;
        }
        memcpy(pending_msg.data, data, data_len);
        pending_msg.data[data_len] = '\0';
        
        pending_msg.data_len = data_len;
        pending_msg.qos = qos;
        pending_msg.retain = retain;
        
        // Intentar encolar el mensaje
        if (xQueueSend(ctx->pending_messages, &pending_msg, 0) != pdTRUE) {
            ESP_LOGE(TAG, "Failed to enqueue message, queue full");
            return ESP_ERR_NO_MEM;
        }
        
        return ESP_OK;
    }
    
    ESP_LOGI(TAG, "Publishing message to topic: %s", topic);
    ESP_LOGD(TAG, "Message data (%d bytes): %.*s", data_len, data_len, data);
    
    int msg_id = esp_mqtt_client_publish(ctx->client, topic, data, data_len, qos, retain);
    if (msg_id < 0) {
        ESP_LOGE(TAG, "Failed to publish message to topic: %s", topic);
        return ESP_FAIL;
    }
    
    ESP_LOGI(TAG, "Message published successfully, msg_id=%d", msg_id);
    
    // Actualizar timestamp de actividad
    ctx->last_activity_time = esp_timer_get_time() / 1000;
    
    return ESP_OK;
}

// Procesa los mensajes MQTT entrantes
esp_err_t mqtt_manager_loop(int timeout_ms) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "MQTT Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    // Verificar si es momento de enviar heartbeat
    int64_t current_time = esp_timer_get_time() / 1000;  // Convertir a ms
    if (mqtt_manager_is_connected() && 
        (current_time - ctx->last_heartbeat_time > DEFAULT_STATUS_INTERVAL_MS)) {
        ESP_LOGI(TAG, "Sending MQTT heartbeat");
        esp_err_t ret = mqtt_manager_send_heartbeat();
        if (ret != ESP_OK) {
            ESP_LOGW(TAG, "Failed to send heartbeat: %s", esp_err_to_name(ret));
        }
    }
    
    // Si estamos en estado de reconexión, intentar reconectar
    if (ctx->state == MQTT_MANAGER_STATE_RECONNECTING && 
        (current_time - ctx->last_reconnect_time > DEFAULT_RECONNECT_TIMEOUT_MS)) {
        
        ctx->last_reconnect_time = current_time;
        
        // Verificar si WiFi está conectado
        if (wifi_manager_is_connected()) {
            ctx->reconnect_attempts++;
            
            if (ctx->reconnect_attempts <= DEFAULT_MAX_RETRIES) {
                ESP_LOGI(TAG, "Attempting MQTT reconnection %d/%d", 
                        ctx->reconnect_attempts, DEFAULT_MAX_RETRIES);
                
                // Reintentar conexión
                mqtt_manager_connect();
            } else {
                ESP_LOGE(TAG, "Max MQTT reconnection attempts reached");
                ctx->state = MQTT_MANAGER_STATE_ERROR;
                
                if (ctx->state_callback) {
                    ctx->state_callback(ctx->state, ctx->state_user_data);
                }
                
                // Reiniciar contador de intentos tras un período más largo
                if (current_time - ctx->last_reconnect_time > DEFAULT_RECONNECT_TIMEOUT_MS * 2) {
                    ctx->reconnect_attempts = 0;
                }
            }
        } else {
            ESP_LOGW(TAG, "WiFi not connected, delaying MQTT reconnection");
        }
    }
    
    // Si WiFi está conectado pero MQTT no, y no estamos en proceso de reconexión,
    // intentar iniciar la conexión
    if (wifi_manager_is_connected() && 
        !mqtt_manager_is_connected() && 
        ctx->state != MQTT_MANAGER_STATE_CONNECTING && 
        ctx->state != MQTT_MANAGER_STATE_RECONNECTING) {
        
        // Solo intentar reconectar cada cierto tiempo
        if (current_time - ctx->last_reconnect_time > DEFAULT_RECONNECT_TIMEOUT_MS) {
            ESP_LOGI(TAG, "WiFi connected but MQTT disconnected, attempting connection");
            ctx->last_reconnect_time = current_time;
            mqtt_manager_connect();
        }
    }
    
    return ESP_OK;
}

// Establece el callback para mensajes recibidos
esp_err_t mqtt_manager_set_message_callback(mqtt_manager_message_callback_t callback, void *user_data) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "MQTT Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    // Adquirir mutex para proteger la actualización del callback
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        ctx->message_callback = callback;
        ctx->message_user_data = user_data;
        xSemaphoreGive(ctx->mutex);
    }
    
    return ESP_OK;
}

// Establece el callback para cambios de estado
esp_err_t mqtt_manager_set_state_callback(mqtt_manager_state_callback_t callback, void *user_data) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "MQTT Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    // Adquirir mutex para proteger la actualización del callback
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        ctx->state_callback = callback;
        ctx->state_user_data = user_data;
        xSemaphoreGive(ctx->mutex);
    }
    
    return ESP_OK;
}

// Obtiene el estado actual del MQTT Manager
mqtt_manager_state_t mqtt_manager_get_state(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return MQTT_MANAGER_STATE_INIT;
    }
    
    return ctx->state;
}

// Envia información de red y estado del dispositivo (VERSIÓN OPTIMIZADA Y ACTUALIZADA)
esp_err_t mqtt_manager_send_network_info(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "MQTT Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (strlen(ctx->esp32_id) == 0) {
        ESP_LOGE(TAG, "ESP32 ID not set");
        return ESP_ERR_INVALID_STATE;
    }
    
    // Usar buffer estático para evitar stack allocation
    static char json_buffer[512];
    char ip_address[16] = "0.0.0.0";
    
    wifi_manager_get_ip(ip_address, sizeof(ip_address));
    
    // Obtener timestamp real en formato adecuado
    char timestamp_str[32];
    if (time_manager_is_synchronized()) {
        // Usar tiempo real si está sincronizado
        time_manager_get_timestamp(timestamp_str, sizeof(timestamp_str));
        
        // También obtener un timestamp legible para registros
        char time_str[32];
        time_manager_get_lima_time_str(time_str, sizeof(time_str));
        ESP_LOGI(TAG, "Using synchronized time: %s", time_str);
        
        // Construir JSON directamente sin cJSON, incluyendo el tiempo formateado
        int len = snprintf(json_buffer, sizeof(json_buffer),
                          "{\"esp32_id\":\"%s\",\"MAC\":\"%s\",\"IP\":\"%s\","
                          "\"status\":\"ONLINE\",\"timestamp\":%s,\"time\":\"%s\"}",
                          ctx->esp32_id,
                          ctx->mac_address,
                          ip_address,
                          timestamp_str,
                          time_str);
                          
        if (len < 0 || len >= sizeof(json_buffer)) {
            ESP_LOGE(TAG, "JSON buffer too small");
            return ESP_ERR_NO_MEM;
        }
    } else {
        // Usar el timestamp del sistema como fallback
        snprintf(timestamp_str, sizeof(timestamp_str), "%lld", (long long)(esp_timer_get_time() / 1000));
        ESP_LOGW(TAG, "Time not synchronized, using system timestamp");
        
        // Generar fecha/hora actual del sistema aunque no esté sincronizada
        time_t now = time(NULL);
        struct tm timeinfo;
        localtime_r(&now, &timeinfo);
        char sys_time_str[32];
        strftime(sys_time_str, sizeof(sys_time_str), "%d/%m/%Y, %H:%M:%S", &timeinfo);
        
        // Construir JSON INCLUYENDO el campo time aunque no esté sincronizado
        int len = snprintf(json_buffer, sizeof(json_buffer),
                        "{\"esp32_id\":\"%s\",\"MAC\":\"%s\",\"IP\":\"%s\","
                        "\"status\":\"ONLINE\",\"timestamp\":%s,\"time\":\"%s\"}",
                        ctx->esp32_id,
                        ctx->mac_address,
                        ip_address,
                        timestamp_str,
                        sys_time_str);
                          
        if (len < 0 || len >= sizeof(json_buffer)) {
            ESP_LOGE(TAG, "JSON buffer too small");
            return ESP_ERR_NO_MEM;
        }
    }
    
    // Publicar con QoS 0 para reducir overhead
    return mqtt_manager_publish("esp32/network_info", json_buffer, strlen(json_buffer), 0, false);
}

// Envía un heartbeat al broker (VERSIÓN OPTIMIZADA Y ACTUALIZADA CON TIMESTAMP REAL)
esp_err_t mqtt_manager_send_heartbeat(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "MQTT Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (strlen(ctx->esp32_id) == 0) {
        ESP_LOGE(TAG, "ESP32 ID not set");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (!mqtt_manager_is_connected()) {
        ESP_LOGW(TAG, "Not connected to MQTT broker, cannot send heartbeat");
        return ESP_FAIL;
    }
    
    // Usar buffer estático
    static char json_buffer[512];
    char message_id[32];
    
    // Generar ID único para el mensaje
    generate_message_id(message_id, sizeof(message_id));
    
    // Crear tópico
    char topic[TOPIC_BUFFER_SIZE];
    snprintf(topic, sizeof(topic), "system/status/%s", ctx->esp32_id);
    
    // Obtener timestamp real
    char timestamp_str[32];
    char time_str[64] = {0}; // Para mostrar en logs y añadir al mensaje
    
    if (time_manager_is_synchronized()) {
        // Usar tiempo real
        time_manager_get_timestamp(timestamp_str, sizeof(timestamp_str));
        time_manager_get_lima_time_str(time_str, sizeof(time_str));
        ESP_LOGI(TAG, "Heartbeat with Lima time: %s", time_str);
    } else {
        // Usar el timestamp del sistema como fallback
        snprintf(timestamp_str, sizeof(timestamp_str), "%lld", (long long)(esp_timer_get_time() / 1000));
        ESP_LOGW(TAG, "Time not synchronized, using system timestamp");
    }
    
    // Crear JSON string directamente
    int len;
    
    if (strlen(ctx->client_panel_id) > 0 && strlen(ctx->panel_id) > 0) {
        // Si tenemos tiempo sincronizado, incluir campo 'time'
        if (time_str[0] != 0) {
            len = snprintf(json_buffer, sizeof(json_buffer),
                          "{\"esp32_id\":\"%s\",\"status\":\"ONLINE\","
                          "\"timestamp\":%s,\"type\":\"heartbeat\","
                          "\"message_id\":\"%s\",\"client_id\":\"%s\","
                          "\"panel_id\":\"%s\",\"time\":\"%s\"}",
                          ctx->esp32_id,
                          timestamp_str,
                          message_id,
                          ctx->client_panel_id,
                          ctx->panel_id,
                          time_str);
        } else {
            // Sin campo 'time' si no hay tiempo sincronizado
            len = snprintf(json_buffer, sizeof(json_buffer),
                          "{\"esp32_id\":\"%s\",\"status\":\"ONLINE\","
                          "\"timestamp\":%s,\"type\":\"heartbeat\","
                          "\"message_id\":\"%s\",\"client_id\":\"%s\","
                          "\"panel_id\":\"%s\"}",
                          ctx->esp32_id,
                          timestamp_str,
                          message_id,
                          ctx->client_panel_id,
                          ctx->panel_id);
        }
    } else {
        // Si tenemos tiempo sincronizado, incluir campo 'time'
        if (time_str[0] != 0) {
            len = snprintf(json_buffer, sizeof(json_buffer),
                          "{\"esp32_id\":\"%s\",\"status\":\"ONLINE\","
                          "\"timestamp\":%s,\"type\":\"heartbeat\","
                          "\"message_id\":\"%s\",\"time\":\"%s\"}",
                          ctx->esp32_id,
                          timestamp_str,
                          message_id,
                          time_str);
        } else {
            // Sin campo 'time' si no hay tiempo sincronizado
            len = snprintf(json_buffer, sizeof(json_buffer),
                          "{\"esp32_id\":\"%s\",\"status\":\"ONLINE\","
                          "\"timestamp\":%s,\"type\":\"heartbeat\","
                          "\"message_id\":\"%s\"}",
                          ctx->esp32_id,
                          timestamp_str,
                          message_id);
        }
    }
    
    if (len < 0 || len >= sizeof(json_buffer)) {
        ESP_LOGE(TAG, "JSON buffer too small");
        return ESP_ERR_NO_MEM;
    }
    
    // Publicar heartbeat
    esp_err_t ret = mqtt_manager_publish(topic, json_buffer, len, 1, false);
    
    if (ret == ESP_OK) {
        ESP_LOGI(TAG, "Heartbeat sent successfully");
        ctx->last_heartbeat_time = esp_timer_get_time() / 1000;
    } else {
        ESP_LOGE(TAG, "Failed to send heartbeat");
    }
    
    return ret;
}

/* Restaurar las advertencias del compilador al final del archivo */
#pragma GCC diagnostic pop