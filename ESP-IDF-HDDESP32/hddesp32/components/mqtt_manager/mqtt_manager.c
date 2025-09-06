#include "mqtt_manager.h"
#include "esp32_id_manager.h"
#include <string.h>
#include <stdio.h>
#include <inttypes.h>
#include <errno.h>
#include <time.h>
#include <sys/socket.h>
#include <netdb.h>
#include <lwip/netdb.h>
#include <lwip/sockets.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "freertos/semphr.h"
#include "freertos/queue.h"
#include "custom_logging.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "mqtt_client.h"
#include "esp_tls.h"
#include "mqtt_ssl_setup.h"
#include "esp_random.h"
#include "config_manager.h"
#include "wifi_manager.h"
#include "time_manager.h"
#include "connectivity_monitor.h"

#define TAG "MQTT_MGR"

#define MQTT_CONNECTED_BIT BIT0
#define MQTT_DISCONNECTED_BIT BIT1
#define MQTT_ERROR_BIT BIT2

#define DEFAULT_BROKER "node02.myqtthub.com"
#define DEFAULT_PORT 8883
#define DEFAULT_KEEPALIVE 120
#define DEFAULT_RECONNECT_TIMEOUT_MS 30000
#define DEFAULT_BUFFER_SIZE 1024
#define DEFAULT_MAX_QUEUE_SIZE 12
#define MAX_TOPIC_LENGTH 128
#define MAX_MESSAGE_LENGTH 1024
#define MIN_MESSAGE_INTERVAL_MS 100

typedef struct {
    char topic[MAX_TOPIC_LENGTH];
    char data[DEFAULT_BUFFER_SIZE];
    int data_len;
    int qos;
    bool retain;
} mqtt_pending_message_t;

typedef struct {
    mqtt_manager_state_t state;
    esp_mqtt_client_handle_t client;
    EventGroupHandle_t event_group;
    SemaphoreHandle_t mutex;
    SemaphoreHandle_t buffer_mutex;
    QueueHandle_t pending_messages;
    uint32_t event_group_operations;
    
    char broker_url[MQTT_BROKER_MAX_LENGTH];
    int port;
    bool use_ssl;
    char client_id[MQTT_CLIENT_ID_MAX_LENGTH];
    char username[MQTT_USERNAME_MAX_LENGTH];
    char password[MQTT_PASSWORD_MAX_LENGTH];
    char lwt_topic[MQTT_TOPIC_MAX_LENGTH];
    char lwt_message[MQTT_LWT_MESSAGE_MAX_LENGTH];
    int lwt_qos;
    bool lwt_retain;
    
    char esp32_id[ESP32_ID_BUFFER_SIZE];
    char mac_address[ESP32_MAC_BUFFER_SIZE];
    
    bool was_connected;
    int reconnect_attempts;
    int64_t last_reconnect_time;
    int64_t last_activity_time;
    int64_t last_message_time;
    
    mqtt_manager_message_callback_t message_callback;
    void *message_user_data;
    mqtt_manager_state_callback_t state_callback;
    void *state_user_data;
    
    char panel_id[32];        
    char client_panel_id[32]; 
    
    char config_topic[MQTT_TOPIC_MAX_LENGTH];
    
    char *buffer_pool[3];
    bool buffer_in_use[3];
    SemaphoreHandle_t pool_mutex;
} mqtt_manager_context_t;

static mqtt_manager_context_t s_mqtt_manager_ctx = {0};

static char *format_time_range(int64_t start_ms, int64_t end_ms, char *buffer, size_t size) {
    return time_manager_format_monotonic_time_range(start_ms, end_ms, buffer, size);
}

static esp_err_t recreate_mqtt_event_group(mqtt_manager_context_t *ctx) {
    EventBits_t current_bits = 0;
    
    if (ctx->event_group) {
        current_bits = xEventGroupGetBits(ctx->event_group);
        vEventGroupDelete(ctx->event_group);
    }
    
    ctx->event_group = xEventGroupCreate();
    
    if (ctx->event_group == NULL) {
        LOG_E(TAG, "Failed to recreate MQTT event group");
        return ESP_ERR_NO_MEM;
    }
    
    if (current_bits != 0) {
        xEventGroupSetBits(ctx->event_group, current_bits);
    }
    
    ctx->event_group_operations = 0;
    LOG_I(TAG, "MQTT event group recreated");
    return ESP_OK;
}

static void safe_mqtt_set_bits(mqtt_manager_context_t *ctx, EventBits_t bits) {
    ctx->event_group_operations++;
    
    if (ctx->event_group_operations >= 30000) {
        recreate_mqtt_event_group(ctx);
    }
    
    if (ctx->event_group) {
        xEventGroupSetBits(ctx->event_group, bits);
    }
}

static void safe_mqtt_clear_bits(mqtt_manager_context_t *ctx, EventBits_t bits) {
    ctx->event_group_operations++;
    
    if (ctx->event_group_operations >= 30000) {
        recreate_mqtt_event_group(ctx);
    }
    
    if (ctx->event_group) {
        xEventGroupClearBits(ctx->event_group, bits);
    }
}

static char* mqtt_get_buffer(mqtt_manager_context_t *ctx) {
    if (xSemaphoreTake(ctx->buffer_mutex, pdMS_TO_TICKS(50)) == pdTRUE) {
        for (int i = 0; i < 3; i++) {
            if (!ctx->buffer_in_use[i] && ctx->buffer_pool[i]) {
                ctx->buffer_in_use[i] = true;
                memset(ctx->buffer_pool[i], 0, MAX_MESSAGE_LENGTH);
                xSemaphoreGive(ctx->buffer_mutex);
                return ctx->buffer_pool[i];
            }
        }
        xSemaphoreGive(ctx->buffer_mutex);
        
        size_t free_heap = esp_get_free_heap_size();
        if (free_heap < 40000) {
            LOG_W(TAG, "Low memory during buffer allocation: %zu bytes", free_heap);
            mqtt_manager_emergency_memory_cleanup();
        }
    }
    return NULL;
}

static void mqtt_release_buffer(mqtt_manager_context_t *ctx, char *buffer) {
    if (!buffer) return;
    
    if (xSemaphoreTake(ctx->buffer_mutex, pdMS_TO_TICKS(50)) == pdTRUE) {
        for (int i = 0; i < 3; i++) {
            if (ctx->buffer_pool[i] == buffer) {
                ctx->buffer_in_use[i] = false;
                break;
            }
        }
        xSemaphoreGive(ctx->buffer_mutex);
    }
}

static esp_err_t validate_esp32_id(const char *esp32_id) {
    if (esp32_id == NULL) {
        LOG_E(TAG, "ESP32 ID is NULL");
        return ESP_ERR_INVALID_ARG;
    }
    
    size_t len = strlen(esp32_id);
    if (len == 0 || len > ESP32_ID_LENGTH) {
        LOG_E(TAG, "ESP32 ID invalid length: %zu", len);
        return ESP_ERR_INVALID_ARG;
    }
    
    for (size_t i = 0; i < len; i++) {
        char c = esp32_id[i];
        if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
            LOG_E(TAG, "ESP32 ID contains invalid character: '%c'", c);
            return ESP_ERR_INVALID_ARG;
        }
    }
    
    return ESP_OK;
}

static bool should_process_message(int64_t current_time) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (current_time - ctx->last_message_time < MIN_MESSAGE_INTERVAL_MS) {
        return false;
    }
    
    ctx->last_message_time = current_time;
    return true;
}

esp_err_t mqtt_manager_setup_panel_subscriptions(void) {
    if (!mqtt_manager_is_connected()) {
        LOG_W(TAG, "MQTT not connected, cannot setup panel subscriptions");
        return ESP_ERR_INVALID_STATE;
    }
    
    char saved_client_id[32] = {0};
    char saved_panel_id[32] = {0};
    
    if (config_manager_get_str("client_id", saved_client_id, sizeof(saved_client_id)) != ESP_OK ||
        config_manager_get_str("panel_id", saved_panel_id, sizeof(saved_panel_id)) != ESP_OK ||
        strlen(saved_client_id) == 0 || strlen(saved_panel_id) == 0) {
        LOG_I(TAG, "No saved panel config found, skipping panel subscriptions");
        return ESP_ERR_NOT_FOUND;
    }
    
    mqtt_manager_set_panel_config(saved_client_id, saved_panel_id);
    
    char topic[MQTT_TOPIC_MAX_LENGTH];
    
    esp_err_t ret = mqtt_manager_get_panel_topic(topic, sizeof(topic), "relay_config");
    if (ret == ESP_OK) {
        mqtt_manager_subscribe(topic, 2);
        LOG_I(TAG, "Subscribed to panel relay_config: %s", topic);
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    
    ret = mqtt_manager_get_panel_topic(topic, sizeof(topic), "relays");
    if (ret == ESP_OK) {
        mqtt_manager_subscribe(topic, 2);
        LOG_I(TAG, "Subscribed to panel relays: %s", topic);
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    
    ret = mqtt_manager_get_panel_topic(topic, sizeof(topic), "command");
    if (ret == ESP_OK) {
        mqtt_manager_subscribe(topic, 1);
        LOG_I(TAG, "Subscribed to panel command: %s", topic);
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    
    ret = mqtt_manager_get_panel_topic(topic, sizeof(topic), "status");
    if (ret == ESP_OK) {
        mqtt_manager_subscribe(topic, 1);
        LOG_I(TAG, "Subscribed to panel status: %s", topic);
    }
    
    return ESP_OK;
}

esp_err_t mqtt_manager_cleanup_panel_subscriptions(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (!mqtt_manager_is_connected()) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (strlen(ctx->client_panel_id) == 0 || strlen(ctx->panel_id) == 0) {
        return ESP_OK;
    }
    
    char topic[MQTT_TOPIC_MAX_LENGTH];
    
    esp_err_t ret = mqtt_manager_get_panel_topic(topic, sizeof(topic), "relay_config");
    if (ret == ESP_OK) {
        mqtt_manager_unsubscribe(topic);
        LOG_I(TAG, "Unsubscribed from panel relay_config: %s", topic);
    }
    
    ret = mqtt_manager_get_panel_topic(topic, sizeof(topic), "relays");
    if (ret == ESP_OK) {
        mqtt_manager_unsubscribe(topic);
        LOG_I(TAG, "Unsubscribed from panel relays: %s", topic);
    }
    
    ret = mqtt_manager_get_panel_topic(topic, sizeof(topic), "command");
    if (ret == ESP_OK) {
        mqtt_manager_unsubscribe(topic);
        LOG_I(TAG, "Unsubscribed from panel command: %s", topic);
    }
    
    ret = mqtt_manager_get_panel_topic(topic, sizeof(topic), "status");
    if (ret == ESP_OK) {
        mqtt_manager_unsubscribe(topic);
        LOG_I(TAG, "Unsubscribed from panel status: %s", topic);
    }
    
    return ESP_OK;
}

static void mqtt_event_handler(void *handler_args, esp_event_base_t base, int32_t event_id, void *event_data) {
    esp_mqtt_event_handle_t event = (esp_mqtt_event_handle_t)event_data;
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    int64_t current_time = esp_timer_get_time() / 1000;
    ctx->last_activity_time = current_time;
    
    switch (event->event_id) {
        case MQTT_EVENT_CONNECTED:
            LOG_I(TAG, "MQTT connected");
            
            if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
                ctx->state = MQTT_MANAGER_STATE_CONNECTED;
                ctx->was_connected = true;
                ctx->reconnect_attempts = 0;
                xSemaphoreGive(ctx->mutex);
            }
            
            safe_mqtt_clear_bits(ctx, MQTT_DISCONNECTED_BIT | MQTT_ERROR_BIT);
            safe_mqtt_set_bits(ctx, MQTT_CONNECTED_BIT);
            connectivity_monitor_report_mqtt_status(true);
            
            if (ctx->state_callback) {
                ctx->state_callback(ctx->state, ctx->state_user_data);
            }
            
            if (strlen(ctx->esp32_id) > 0) {
                snprintf(ctx->config_topic, sizeof(ctx->config_topic), "esp32/config/%s", ctx->esp32_id);
                mqtt_manager_subscribe(ctx->config_topic, 2);
                
                char notify_topic[MQTT_TOPIC_MAX_LENGTH];
                snprintf(notify_topic, sizeof(notify_topic), "esp32/notify/%s/deleted", ctx->esp32_id);
                mqtt_manager_subscribe(notify_topic, 2);
                
                mqtt_manager_setup_panel_subscriptions();
            }
            
            mqtt_pending_message_t pending_msg;
            int pending_count = 0;
            while (xQueueReceive(ctx->pending_messages, &pending_msg, 0) == pdTRUE && pending_count < 10) {
                esp_mqtt_client_publish(event->client, pending_msg.topic, pending_msg.data, 
                                        pending_msg.data_len, pending_msg.qos, pending_msg.retain);
                pending_count++;
                vTaskDelay(pdMS_TO_TICKS(50));
            }
            break;
            
        case MQTT_EVENT_DISCONNECTED:
            LOG_W(TAG, "MQTT disconnected");
            
            if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
                ctx->state = MQTT_MANAGER_STATE_RECONNECTING;
                ctx->reconnect_attempts++;
                xSemaphoreGive(ctx->mutex);
            }
            
            safe_mqtt_clear_bits(ctx, MQTT_CONNECTED_BIT);
            safe_mqtt_set_bits(ctx, MQTT_DISCONNECTED_BIT);
            connectivity_monitor_report_mqtt_status(false);
            
            if (ctx->state_callback) {
                ctx->state_callback(ctx->state, ctx->state_user_data);
            }
            break;
            
        case MQTT_EVENT_SUBSCRIBED:
            LOG_I(TAG, "MQTT subscribed, msg_id=%d", event->msg_id);
            break;
            
        case MQTT_EVENT_PUBLISHED:
            break;
            
        case MQTT_EVENT_DATA:
            if (ctx->message_callback && 
                event->topic_len > 0 && event->topic_len < MAX_TOPIC_LENGTH &&
                event->data_len > 0 && event->data_len < MAX_MESSAGE_LENGTH) {
                
                if (!should_process_message(current_time)) {
                    LOG_D(TAG, "Message rate limited, skipping");
                    break;
                }
                
                char *topic_buffer = mqtt_get_buffer(ctx);
                char *message_buffer = mqtt_get_buffer(ctx);
                
                if (topic_buffer && message_buffer) {
                    memcpy(topic_buffer, event->topic, event->topic_len);
                    topic_buffer[event->topic_len] = '\0';
                    
                    memcpy(message_buffer, event->data, event->data_len);
                    message_buffer[event->data_len] = '\0';
                    
                    ctx->message_callback(topic_buffer, message_buffer, event->data_len, ctx->message_user_data);
                    
                    mqtt_release_buffer(ctx, topic_buffer);
                    mqtt_release_buffer(ctx, message_buffer);
                } else {
                    if (topic_buffer) mqtt_release_buffer(ctx, topic_buffer);
                    if (message_buffer) mqtt_release_buffer(ctx, message_buffer);
                }
            }
            break;
            
        case MQTT_EVENT_ERROR:
            LOG_E(TAG, "MQTT error");
            
            if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(50)) == pdTRUE) {
                ctx->state = MQTT_MANAGER_STATE_RECONNECTING;
                ctx->reconnect_attempts++;
                xSemaphoreGive(ctx->mutex);
            }
            
            safe_mqtt_clear_bits(ctx, MQTT_CONNECTED_BIT);
            safe_mqtt_set_bits(ctx, MQTT_ERROR_BIT);

            connectivity_monitor_report_mqtt_status(false);
            
            if (ctx->state_callback) {
                ctx->state_callback(ctx->state, ctx->state_user_data);
            }
            break;
            
        default:
            break;
    }
}

esp_err_t mqtt_manager_init(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group != NULL) {
        LOG_W(TAG, "Already initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    LOG_I(TAG, "Initializing MQTT Manager");
    
    memset(ctx, 0, sizeof(mqtt_manager_context_t));
    
    ctx->state = MQTT_MANAGER_STATE_INIT;
    
    ctx->event_group = xEventGroupCreate();
    if (ctx->event_group == NULL) {
        LOG_E(TAG, "Failed to create event group");
        return ESP_ERR_NO_MEM;
    }
    
    ctx->mutex = xSemaphoreCreateMutex();
    if (ctx->mutex == NULL) {
        vEventGroupDelete(ctx->event_group);
        ctx->event_group = NULL;
        LOG_E(TAG, "Failed to create mutex");
        return ESP_ERR_NO_MEM;
    }
    
    ctx->buffer_mutex = xSemaphoreCreateMutex();
    if (ctx->buffer_mutex == NULL) {
        vSemaphoreDelete(ctx->mutex);
        vEventGroupDelete(ctx->event_group);
        ctx->mutex = NULL;
        ctx->event_group = NULL;
        LOG_E(TAG, "Failed to create buffer mutex");
        return ESP_ERR_NO_MEM;
    }
    
    for (int i = 0; i < 3; i++) {
        ctx->buffer_pool[i] = heap_caps_malloc(MAX_MESSAGE_LENGTH, MALLOC_CAP_8BIT | MALLOC_CAP_INTERNAL);
        if (ctx->buffer_pool[i] == NULL) {
            for (int j = 0; j < i; j++) {
                free(ctx->buffer_pool[j]);
                ctx->buffer_pool[j] = NULL;
            }
            vSemaphoreDelete(ctx->buffer_mutex);
            vSemaphoreDelete(ctx->mutex);
            vEventGroupDelete(ctx->event_group);
            return ESP_ERR_NO_MEM;
        }
        ctx->buffer_in_use[i] = false;
    }
    
    ctx->pending_messages = xQueueCreate(DEFAULT_MAX_QUEUE_SIZE, sizeof(mqtt_pending_message_t));
    if (ctx->pending_messages == NULL) {
        for (int i = 0; i < 3; i++) {
            free(ctx->buffer_pool[i]);
            ctx->buffer_pool[i] = NULL;
        }
        vSemaphoreDelete(ctx->buffer_mutex);
        vSemaphoreDelete(ctx->mutex);
        vEventGroupDelete(ctx->event_group);
        ctx->mutex = NULL;
        ctx->event_group = NULL;
        LOG_E(TAG, "Failed to create message queue");
        return ESP_ERR_NO_MEM;
    }
    
    strncpy(ctx->broker_url, DEFAULT_BROKER, sizeof(ctx->broker_url) - 1);
    ctx->broker_url[sizeof(ctx->broker_url) - 1] = '\0';
    ctx->port = DEFAULT_PORT;
    ctx->use_ssl = true;
    ctx->lwt_qos = 1;
    ctx->lwt_retain = false;
    
    ctx->state = MQTT_MANAGER_STATE_DISCONNECTED;
    safe_mqtt_set_bits(ctx, MQTT_DISCONNECTED_BIT);
    
    LOG_I(TAG, "MQTT Manager initialized");
    return ESP_OK;
}

esp_err_t mqtt_manager_set_esp32_id(const char *esp32_id) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        LOG_E(TAG, "Not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    esp_err_t validation_result = validate_esp32_id(esp32_id);
    if (validation_result != ESP_OK) {
        return validation_result;
    }
    
    LOG_I(TAG, "Setting credentials for ESP32 ID: %s", esp32_id);
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        strncpy(ctx->esp32_id, esp32_id, sizeof(ctx->esp32_id) - 1);
        ctx->esp32_id[sizeof(ctx->esp32_id) - 1] = '\0';
        
        esp32_id_manager_get_mac(ctx->mac_address, sizeof(ctx->mac_address));
        
        strncpy(ctx->client_id, esp32_id, sizeof(ctx->client_id) - 1);
        ctx->client_id[sizeof(ctx->client_id) - 1] = '\0';
        
        strncpy(ctx->username, esp32_id, sizeof(ctx->username) - 1);
        ctx->username[sizeof(ctx->username) - 1] = '\0';
        
        strncpy(ctx->password, esp32_id, sizeof(ctx->password) - 1);
        ctx->password[sizeof(ctx->password) - 1] = '\0';
        
        snprintf(ctx->lwt_topic, sizeof(ctx->lwt_topic), "system/status/%s", esp32_id);
        
        char timestamp_str[20];
        if (time_manager_is_synchronized()) {
            time_manager_get_timestamp(timestamp_str, sizeof(timestamp_str));
        } else {
            snprintf(timestamp_str, sizeof(timestamp_str), "%lld", (long long)(esp_timer_get_time() / 1000));
        }
        
        snprintf(ctx->lwt_message, sizeof(ctx->lwt_message),
                "{\"esp32_id\":\"%.8s\",\"status\":\"OFFLINE\",\"timestamp\":%.15s,\"type\":\"lwt\"}",
                esp32_id, timestamp_str);
        
        xSemaphoreGive(ctx->mutex);
    }
    
    LOG_I(TAG, "Credentials configured");
    return ESP_OK;
}

esp_err_t mqtt_manager_connect(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    char temp_buffer[MQTT_BROKER_MAX_LENGTH + 32];
    
    if (ctx->event_group == NULL) {
        LOG_E(TAG, "Not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (strlen(ctx->esp32_id) == 0) {
        LOG_E(TAG, "ESP32 ID not set");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (mqtt_manager_is_connected() || ctx->state == MQTT_MANAGER_STATE_CONNECTING) {
        LOG_W(TAG, "Already connected or connecting");
        return ESP_OK;
    }
    
    if (!wifi_manager_is_connected()) {
        LOG_E(TAG, "WiFi not connected");
        return ESP_ERR_INVALID_STATE;
    }
    
    LOG_I(TAG, "Connecting to MQTT broker");
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        ctx->state = MQTT_MANAGER_STATE_CONNECTING;
        safe_mqtt_clear_bits(ctx, MQTT_CONNECTED_BIT | MQTT_ERROR_BIT);
        
        if (ctx->client != NULL) {
            esp_mqtt_client_unregister_event(ctx->client, ESP_EVENT_ANY_ID, mqtt_event_handler);
            esp_err_t stop_ret = esp_mqtt_client_stop(ctx->client);
            if (stop_ret == ESP_OK) {
                vTaskDelay(pdMS_TO_TICKS(1000));
            }
            esp_mqtt_client_destroy(ctx->client);
            ctx->client = NULL;
            vTaskDelay(pdMS_TO_TICKS(500));
        }
        
        char cleaned_broker[MQTT_BROKER_MAX_LENGTH];
        strncpy(cleaned_broker, ctx->broker_url, sizeof(cleaned_broker) - 1);
        cleaned_broker[sizeof(cleaned_broker) - 1] = '\0';
        
        char *clean_start = cleaned_broker;
        while (*clean_start && (*clean_start == ':' || *clean_start == '/' || *clean_start <= ' ')) {
            clean_start++;
        }
        
        if (strlen(clean_start) == 0) {
            LOG_E(TAG, "Broker URL is empty");
            ctx->state = MQTT_MANAGER_STATE_ERROR;
            safe_mqtt_set_bits(ctx, MQTT_ERROR_BIT);
            xSemaphoreGive(ctx->mutex);
            return ESP_ERR_INVALID_ARG;
        }
        
        struct addrinfo hints, *result;
        memset(&hints, 0, sizeof(hints));
        hints.ai_family = AF_INET;
        hints.ai_socktype = SOCK_STREAM;
        
        LOG_I(TAG, "Testing DNS resolution for: %s", clean_start);
        int dns_result = getaddrinfo(clean_start, NULL, &hints, &result);
        if (dns_result != 0) {
            LOG_E(TAG, "DNS resolution failed for %s", clean_start);
            ctx->state = MQTT_MANAGER_STATE_ERROR;
            safe_mqtt_set_bits(ctx, MQTT_ERROR_BIT);
            xSemaphoreGive(ctx->mutex);
            return ESP_FAIL;
        } else {
            LOG_I(TAG, "DNS resolution successful for %s", clean_start);
            freeaddrinfo(result);
        }
        
        snprintf(temp_buffer, sizeof(temp_buffer), "%s://%s:%d", 
                ctx->use_ssl ? "mqtts" : "mqtt", clean_start, ctx->port);
        
        LOG_I(TAG, "Connecting to URI: %s", temp_buffer);
        
        esp_mqtt_client_config_t mqtt_cfg = {0};
        mqtt_cfg.broker.address.uri = temp_buffer;
        mqtt_cfg.credentials.client_id = ctx->client_id;
        mqtt_cfg.credentials.username = ctx->username;
        mqtt_cfg.credentials.authentication.password = ctx->password;
        mqtt_cfg.session.keepalive = 60;
        mqtt_cfg.session.disable_clean_session = false;
        mqtt_cfg.buffer.size = 1536;
        mqtt_cfg.buffer.out_size = 1536;

        mqtt_cfg.network.timeout_ms = 15000;
        mqtt_cfg.network.reconnect_timeout_ms = 10000;
        mqtt_cfg.task.stack_size = 16384;
        
        if (time_manager_is_synchronized()) {
            char timestamp_str[20];
            time_manager_get_timestamp(timestamp_str, sizeof(timestamp_str));
            snprintf(ctx->lwt_message, sizeof(ctx->lwt_message),
                    "{\"esp32_id\":\"%s\",\"status\":\"OFFLINE\",\"timestamp\":%s,\"type\":\"lwt\"}",
                    ctx->esp32_id, timestamp_str);
        }
        
        mqtt_cfg.session.last_will.topic = ctx->lwt_topic;
        mqtt_cfg.session.last_will.msg = ctx->lwt_message;
        mqtt_cfg.session.last_will.msg_len = strlen(ctx->lwt_message);
        mqtt_cfg.session.last_will.qos = ctx->lwt_qos;
        mqtt_cfg.session.last_will.retain = ctx->lwt_retain;
        
        if (ctx->use_ssl) {
            esp_err_t ssl_ret = mqtt_ssl_setup_minimal_config(&mqtt_cfg);
            if (ssl_ret != ESP_OK) {
                ctx->state = MQTT_MANAGER_STATE_ERROR;
                safe_mqtt_set_bits(ctx, MQTT_ERROR_BIT);
                xSemaphoreGive(ctx->mutex);
                LOG_E(TAG, "SSL config failed");
                return ssl_ret;
            }
        }
        
        ctx->client = esp_mqtt_client_init(&mqtt_cfg);
        if (ctx->client == NULL) {
            ctx->state = MQTT_MANAGER_STATE_ERROR;
            safe_mqtt_set_bits(ctx, MQTT_ERROR_BIT);
            xSemaphoreGive(ctx->mutex);
            LOG_E(TAG, "Failed to create MQTT client");
            return ESP_FAIL;
        }
        
        esp_mqtt_client_register_event(ctx->client, ESP_EVENT_ANY_ID, mqtt_event_handler, NULL);
        
        esp_err_t ret = esp_mqtt_client_start(ctx->client);
        if (ret != ESP_OK) {
            LOG_E(TAG, "Failed to start MQTT client");
            esp_mqtt_client_destroy(ctx->client);
            ctx->client = NULL;
            ctx->state = MQTT_MANAGER_STATE_ERROR;
            safe_mqtt_set_bits(ctx, MQTT_ERROR_BIT);
            xSemaphoreGive(ctx->mutex);
            return ret;
        }
        
        xSemaphoreGive(ctx->mutex);
    }
    
    if (ctx->state_callback) {
        ctx->state_callback(ctx->state, ctx->state_user_data);
    }
    
    EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                          MQTT_CONNECTED_BIT | MQTT_ERROR_BIT,
                                          pdFALSE, pdFALSE, pdMS_TO_TICKS(60000));
    
    if (bits & MQTT_CONNECTED_BIT) {
        LOG_I(TAG, "MQTT connected successfully");
        return ESP_OK;
    } else if (bits & MQTT_ERROR_BIT) {
        LOG_E(TAG, "MQTT connection error");
        return ESP_FAIL;
    } else {
        LOG_W(TAG, "MQTT connection timeout");
        return ESP_ERR_TIMEOUT;
    }
}

esp_err_t mqtt_manager_disconnect(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ctx->client == NULL) {
        return ESP_OK;
    }
    
    LOG_I(TAG, "Disconnecting from MQTT");
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        esp_err_t ret = esp_mqtt_client_stop(ctx->client);
        if (ret != ESP_OK) {
            xSemaphoreGive(ctx->mutex);
            return ret;
        }
        
        ctx->state = MQTT_MANAGER_STATE_DISCONNECTED;
        ctx->was_connected = false;
        safe_mqtt_clear_bits(ctx, MQTT_CONNECTED_BIT | MQTT_ERROR_BIT);
        safe_mqtt_set_bits(ctx, MQTT_DISCONNECTED_BIT);
        
        xSemaphoreGive(ctx->mutex);
    }
    
    if (ctx->state_callback) {
        ctx->state_callback(ctx->state, ctx->state_user_data);
    }
    
    return ESP_OK;
}

bool mqtt_manager_is_connected(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return false;
    }
    
    EventBits_t bits = xEventGroupGetBits(ctx->event_group);
    return (bits & MQTT_CONNECTED_BIT) != 0;
}

esp_err_t mqtt_manager_subscribe(const char *topic, int qos) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL || ctx->client == NULL || topic == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (qos < 0 || qos > 2) {
        qos = 1;
    }
    
    int msg_id = esp_mqtt_client_subscribe(ctx->client, topic, qos);
    return (msg_id < 0) ? ESP_FAIL : ESP_OK;
}

esp_err_t mqtt_manager_unsubscribe(const char *topic) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL || ctx->client == NULL || topic == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    int msg_id = esp_mqtt_client_unsubscribe(ctx->client, topic);
    return (msg_id < 0) ? ESP_FAIL : ESP_OK;
}

esp_err_t mqtt_manager_publish(const char *topic, const char *data, int data_len, int qos, bool retain) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL || topic == NULL || data == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (data_len < 0) {
        data_len = strlen(data);
    }
    
    if (data_len > DEFAULT_BUFFER_SIZE) {
        LOG_E(TAG, "Message too large: %d bytes (max %d)", data_len, DEFAULT_BUFFER_SIZE);
        return ESP_ERR_INVALID_SIZE;
    }
    
    if (qos < 0 || qos > 2) {
        qos = 1;
    }
    
    if (!mqtt_manager_is_connected()) {
        mqtt_pending_message_t pending_msg;
        size_t topic_len = strlen(topic);
        if (topic_len >= sizeof(pending_msg.topic)) {
            LOG_E(TAG, "Topic too long: %zu", topic_len);
            return ESP_ERR_INVALID_SIZE;
        }
        
        strncpy(pending_msg.topic, topic, sizeof(pending_msg.topic) - 1);
        pending_msg.topic[sizeof(pending_msg.topic) - 1] = '\0';
        
        if (data_len > sizeof(pending_msg.data) - 1) {
            data_len = sizeof(pending_msg.data) - 1;
        }
        memcpy(pending_msg.data, data, data_len);
        pending_msg.data[data_len] = '\0';
        
        pending_msg.data_len = data_len;
        pending_msg.qos = qos;
        pending_msg.retain = retain;
        
        if (xQueueSend(ctx->pending_messages, &pending_msg, 0) != pdTRUE) {
            mqtt_pending_message_t dummy;
            if (xQueueReceive(ctx->pending_messages, &dummy, 0) == pdTRUE) {
                if (xQueueSend(ctx->pending_messages, &pending_msg, 0) != pdTRUE) {
                    LOG_E(TAG, "Message queue full, dropping message");
                    return ESP_ERR_NO_MEM;
                }
            }
        }
        return ESP_OK;
    }
    
    int msg_id = esp_mqtt_client_publish(ctx->client, topic, data, data_len, qos, retain);
    if (msg_id < 0) {
        LOG_E(TAG, "Failed to publish to: %s", topic);
        return ESP_FAIL;
    }
    
    ctx->last_activity_time = esp_timer_get_time() / 1000;
    return ESP_OK;
}

esp_err_t mqtt_manager_publish_json(const char *topic, const char *json_data, int qos, bool retain) {
    if (topic == NULL || json_data == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    return mqtt_manager_publish(topic, json_data, strlen(json_data), qos, retain);
}

esp_err_t mqtt_manager_loop(int timeout_ms) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    int64_t current_time = esp_timer_get_time() / 1000;
    
    if (ctx->state == MQTT_MANAGER_STATE_RECONNECTING && 
        (current_time - ctx->last_reconnect_time > 30000)) {
        
        ctx->last_reconnect_time = current_time;
        
        if (wifi_manager_is_connected()) {
            LOG_I(TAG, "MQTT reconnect attempt %d (infinite retries enabled)", ctx->reconnect_attempts);
            mqtt_manager_connect();
        }
    }
    
    if (wifi_manager_is_connected() && 
        !mqtt_manager_is_connected() && 
        ctx->state != MQTT_MANAGER_STATE_CONNECTING && 
        ctx->state != MQTT_MANAGER_STATE_RECONNECTING) {
        
        if (current_time - ctx->last_reconnect_time > 30000) {
            ctx->last_reconnect_time = current_time;
            mqtt_manager_connect();
        }
    }
    
    return ESP_OK;
}

esp_err_t mqtt_manager_set_message_callback(mqtt_manager_message_callback_t callback, void *user_data) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        ctx->message_callback = callback;
        ctx->message_user_data = user_data;
        xSemaphoreGive(ctx->mutex);
    }
    
    return ESP_OK;
}

esp_err_t mqtt_manager_set_state_callback(mqtt_manager_state_callback_t callback, void *user_data) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        ctx->state_callback = callback;
        ctx->state_user_data = user_data;
        xSemaphoreGive(ctx->mutex);
    }
    
    return ESP_OK;
}

mqtt_manager_state_t mqtt_manager_get_state(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return MQTT_MANAGER_STATE_INIT;
    }
    
    return ctx->state;
}

static char network_info_buffer[1024];
static SemaphoreHandle_t network_buffer_mutex = NULL;

esp_err_t mqtt_manager_send_network_info(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL || strlen(ctx->esp32_id) == 0) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (network_buffer_mutex == NULL) {
        network_buffer_mutex = xSemaphoreCreateMutex();
        if (network_buffer_mutex == NULL) {
            return ESP_ERR_NO_MEM;
        }
    }
    
    if (xSemaphoreTake(network_buffer_mutex, pdMS_TO_TICKS(1000)) != pdTRUE) {
        return ESP_ERR_TIMEOUT;
    }
    
    char ip_address[16] = "0.0.0.0";
    wifi_manager_get_ip(ip_address, sizeof(ip_address));
    
    char timestamp_str[16];
    char time_str[32];
    
    if (time_manager_is_synchronized()) {
        time_manager_get_timestamp(timestamp_str, sizeof(timestamp_str));
        time_manager_get_lima_time_str(time_str, sizeof(time_str));
    } else {
        time_t now = time(NULL);
        if (now > 1577836800) {
            snprintf(timestamp_str, sizeof(timestamp_str), "%ld", (long)now);
            struct tm *timeinfo = gmtime(&now);
            if (timeinfo) {
                time_t peru_time = now - (5 * 3600);
                struct tm *peru_timeinfo = gmtime(&peru_time);
                if (peru_timeinfo) {
                    strftime(time_str, sizeof(time_str), "%d/%m/%Y, %H:%M:%S", peru_timeinfo);
                } else {
                    strcpy(time_str, "Time unavailable");
                }
            } else {
                strcpy(time_str, "Time unavailable");
            }
        } else {
            snprintf(timestamp_str, sizeof(timestamp_str), "%lld", (long long)(esp_timer_get_time() / 1000));
            snprintf(time_str, sizeof(time_str), "System: %lld ms", (long long)(esp_timer_get_time() / 1000));
        }
    }
    
    const char *current_status = "ONLINE";
    if (strlen(ctx->panel_id) == 0 || strlen(ctx->client_panel_id) == 0) {
        current_status = "AWAITING_CONFIG";
    }
    
    int len = snprintf(network_info_buffer, sizeof(network_info_buffer),
                      "{\"esp32_id\":\"%.8s\",\"MAC\":\"%.12s\",\"IP\":\"%.15s\","
                      "\"status\":\"%s\",\"timestamp\":%.12s,\"time\":\"%.25s\"",
                      ctx->esp32_id, ctx->mac_address, ip_address, 
                      current_status, timestamp_str, time_str);
    
    if (strlen(ctx->client_panel_id) > 0 && strlen(ctx->panel_id) > 0 && len > 0) {
        int remaining_space = sizeof(network_info_buffer) - len - 2;
        if (remaining_space > 50) {
            int additional_len = snprintf(network_info_buffer + len, remaining_space,
                                        ",\"client_id\":\"%.20s\",\"panel_id\":\"%.20s\"",
                                        ctx->client_panel_id, ctx->panel_id);
            if (additional_len > 0 && additional_len < remaining_space) {
                len += additional_len;
            }
        }
    }
    
    if (len > 0 && len < sizeof(network_info_buffer) - 1) {
        network_info_buffer[len] = '}';
        network_info_buffer[len + 1] = '\0';
        len++;
    } else {
        LOG_E(TAG, "Network info buffer overflow, len=%d", len);
        xSemaphoreGive(network_buffer_mutex);
        return ESP_ERR_NO_MEM;
    }
    
    if (len <= 2) {
        LOG_E(TAG, "Invalid JSON length: %d", len);
        xSemaphoreGive(network_buffer_mutex);
        return ESP_ERR_INVALID_ARG;
    }
    
    LOG_I(TAG, "Sending network info to esp32/network_info (len=%d)", len);
    
    esp_err_t result = mqtt_manager_publish("esp32/network_info", network_info_buffer, len, 0, false);
    
    xSemaphoreGive(network_buffer_mutex);
    
    return result;
}

esp_err_t mqtt_manager_set_panel_config(const char *client_id, const char *panel_id) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (!client_id || !panel_id) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        strncpy(ctx->client_panel_id, client_id, sizeof(ctx->client_panel_id) - 1);
        ctx->client_panel_id[sizeof(ctx->client_panel_id) - 1] = '\0';
        
        strncpy(ctx->panel_id, panel_id, sizeof(ctx->panel_id) - 1);
        ctx->panel_id[sizeof(ctx->panel_id) - 1] = '\0';
        
        xSemaphoreGive(ctx->mutex);
        
        LOG_I(TAG, "Panel config set: client=%s, panel=%s", client_id, panel_id);
        return ESP_OK;
    }
    
    return ESP_ERR_TIMEOUT;
}

esp_err_t mqtt_manager_get_panel_topic(char *topic, size_t size, const char *suffix) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (!topic || !suffix || size == 0) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (strlen(ctx->client_panel_id) == 0 || strlen(ctx->panel_id) == 0) {
        return ESP_ERR_INVALID_STATE;
    }
    
    int ret = snprintf(topic, size, "clients/%s/panels/%s/%s", 
                      ctx->client_panel_id, ctx->panel_id, suffix);
    
    return (ret > 0 && ret < size) ? ESP_OK : ESP_ERR_INVALID_SIZE;
}

esp_err_t mqtt_manager_send_config_response(bool success, const char *message) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    char response_topic[MQTT_TOPIC_MAX_LENGTH];
    char response_data[256];
    
    if (strlen(ctx->esp32_id) == 0 || !mqtt_manager_is_connected()) {
        return ESP_ERR_INVALID_STATE;
    }
    
    snprintf(response_topic, sizeof(response_topic), "esp32/config/%s/response", ctx->esp32_id);
    
    snprintf(response_data, sizeof(response_data),
             "{\"esp32_id\":\"%s\",\"status\":\"%s\",\"message\":\"%.50s\",\"timestamp\":%lld}",
             ctx->esp32_id,
             success ? "CONFIG_ACCEPTED" : "CONFIG_ERROR",
             message ? message : (success ? "Configuration applied" : "Configuration failed"),
             (long long)(esp_timer_get_time() / 1000));
    
    return mqtt_manager_publish(response_topic, response_data, strlen(response_data), 1, false);
}

esp_err_t mqtt_manager_clear_panel_config(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        memset(ctx->client_panel_id, 0, sizeof(ctx->client_panel_id));
        memset(ctx->panel_id, 0, sizeof(ctx->panel_id));
        
        xSemaphoreGive(ctx->mutex);
        
        LOG_I(TAG, "Panel configuration cleared");
        return ESP_OK;
    }
    
    return ESP_ERR_TIMEOUT;
}

esp_err_t mqtt_manager_emergency_memory_cleanup(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    LOG_W(TAG, "Performing emergency memory cleanup");
    
    if (ctx->pending_messages) {
        mqtt_pending_message_t dummy_msg;
        int cleared = 0;
        while (xQueueReceive(ctx->pending_messages, &dummy_msg, 0) == pdTRUE && cleared < 8) {
            cleared++;
        }
        if (cleared > 0) {
            LOG_I(TAG, "Cleared %d pending messages", cleared);
        }
    }
    
    if (xSemaphoreTake(ctx->buffer_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        for (int i = 0; i < 3; i++) {
            if (ctx->buffer_in_use[i]) {
                LOG_W(TAG, "Force releasing buffer %d", i);
                ctx->buffer_in_use[i] = false;
            }
        }
        xSemaphoreGive(ctx->buffer_mutex);
    }
    
    if (ctx->event_group_operations > 20000) {
        recreate_mqtt_event_group(ctx);
    }
    
    size_t free_after = esp_get_free_heap_size();
    LOG_I(TAG, "Memory cleanup completed, free heap: %zu", free_after);
    
    return ESP_OK;
}

esp_err_t mqtt_manager_send_heartbeat(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (!mqtt_manager_is_connected() || strlen(ctx->esp32_id) == 0) {
        return ESP_ERR_INVALID_STATE;
    }
    
    char heartbeat_topic[MQTT_TOPIC_MAX_LENGTH];
    char heartbeat_data[256];
    
    snprintf(heartbeat_topic, sizeof(heartbeat_topic), "esp32/heartbeat/%s", ctx->esp32_id);
    
    int64_t timestamp_ms;
    if (time_manager_is_synchronized()) {
        timestamp_ms = (int64_t)time_manager_get_time() * 1000;
    } else {
        timestamp_ms = esp_timer_get_time() / 1000;
    }
    
    size_t free_heap = esp_get_free_heap_size();
    size_t min_heap = esp_get_minimum_free_heap_size();
    
    snprintf(heartbeat_data, sizeof(heartbeat_data),
             "{\"esp32_id\":\"%s\",\"timestamp\":%lld,\"free_heap\":%zu,\"min_heap\":%zu,\"uptime\":%lld}",
             ctx->esp32_id, (long long)timestamp_ms, free_heap, min_heap, 
             (long long)(esp_timer_get_time() / 1000000));
    
    return mqtt_manager_publish(heartbeat_topic, heartbeat_data, strlen(heartbeat_data), 1, false);
}

esp_err_t mqtt_manager_send_connectivity_message(const mqtt_connectivity_message_t *msg) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    static char message_buffer[1024];
    
    if (!msg || strlen(ctx->esp32_id) == 0) {
        return ESP_ERR_INVALID_ARG;
    }
    if (!mqtt_manager_is_connected()) {
        return ESP_ERR_INVALID_STATE;
    }
    
    char topic[MQTT_TOPIC_MAX_LENGTH];
    char time_range[64];
    
    snprintf(topic, sizeof(topic), "esp32/connectivity/%s", ctx->esp32_id);
    format_time_range(msg->start_time_ms, msg->end_time_ms, time_range, sizeof(time_range));
    
    const char *event_type = "";
    const char *status_type = "";
    
    switch (msg->type) {
        case CONNECTIVITY_MSG_WIFI_LOST:
            event_type = "WIFI_DISCONNECTED";
            status_type = "wifi_lost";
            break;
        case CONNECTIVITY_MSG_WIFI_RECOVERED:
            event_type = "WIFI_RECONNECTED";
            status_type = "wifi_recovered";
            break;
        case CONNECTIVITY_MSG_INTERNET_LOST:
            event_type = "INTERNET_LOST";
            status_type = "internet_lost";
            break;
        case CONNECTIVITY_MSG_INTERNET_RECOVERED:
            event_type = "INTERNET_RECOVERED";
            status_type = "internet_recovered";
            break;
        case CONNECTIVITY_MSG_MQTT_LOST:
            event_type = "MQTT_DISCONNECTED";
            status_type = "mqtt_lost";
            break;
        case CONNECTIVITY_MSG_MQTT_RECOVERED:
            event_type = "MQTT_RECONNECTED";
            status_type = "mqtt_recovered";
            break;
        default:
            return ESP_ERR_INVALID_ARG;
    }
    
    time_t start_lima = time_manager_monotonic_to_lima_time(msg->start_time_ms);
    time_t end_lima = time_manager_monotonic_to_lima_time(msg->end_time_ms);
    
    int len = snprintf(message_buffer, sizeof(message_buffer),
             "{"
             "\"esp32_id\":\"%s\","
             "\"type\":\"%s\","
             "\"status\":\"%s\","
             "\"panel_name\":\"%s\","
             "\"ssid\":\"%s\","
             "\"time_range\":\"%s\","
             "\"start_time\":%lld,"
             "\"end_time\":%lld,"
             "\"start_time_lima\":%lld,"
             "\"end_time_lima\":%lld,"
             "\"timestamp\":%lld"
             "}",
             ctx->esp32_id, event_type, status_type, msg->panel_name, msg->ssid, time_range,
             (long long)msg->start_time_ms, (long long)msg->end_time_ms,
             (long long)start_lima * 1000, (long long)end_lima * 1000,
             (long long)(esp_timer_get_time() / 1000));
    
    if (len > 0 && len < sizeof(message_buffer)) {
        return mqtt_manager_publish(topic, message_buffer, len, 1, false);
    }
    return ESP_ERR_INVALID_SIZE;
}

esp_err_t mqtt_manager_send_connectivity_status_message(const char *status, const char *details) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (!status || strlen(ctx->esp32_id) == 0) {
        return ESP_ERR_INVALID_ARG;
    }
    
    char topic[MQTT_TOPIC_MAX_LENGTH];
    char message_buffer[256];
    
    snprintf(topic, sizeof(topic), "esp32/status/%s", ctx->esp32_id);
    
    snprintf(message_buffer, sizeof(message_buffer),
             "{"
             "\"esp32_id\":\"%s\","
             "\"status\":\"%s\","
             "\"details\":\"%s\","
             "\"timestamp\":%lld,"
             "\"type\":\"connectivity\""
             "}",
             ctx->esp32_id,
             status,
             details ? details : "",
             (long long)(esp_timer_get_time() / 1000));
    
    return mqtt_manager_publish(topic, message_buffer, strlen(message_buffer), 1, false);
}