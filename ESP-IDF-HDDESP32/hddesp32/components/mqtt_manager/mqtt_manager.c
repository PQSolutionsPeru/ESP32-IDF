#include "mqtt_manager.h"
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
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "mqtt_client.h"
#include "esp_tls.h"
#include "mqtt_ssl_setup.h"
#include "esp_random.h"
#include "config_manager.h"
#include "esp32_id_manager.h"
#include "wifi_manager.h"
#include "time_manager.h"
#include "esp_task_wdt.h"
#include "esp_heap_caps.h"

#define TAG "MQTT_MGR"

#define MQTT_CONNECTED_BIT BIT0
#define MQTT_DISCONNECTED_BIT BIT1
#define MQTT_ERROR_BIT BIT2

#define DEFAULT_BROKER "node02.myqtthub.com"
#define DEFAULT_PORT 8883
#define DEFAULT_KEEPALIVE 120
#define DEFAULT_RECONNECT_TIMEOUT_MS 10000
#define DEFAULT_MAX_RETRIES 3
#define DEFAULT_BUFFER_SIZE 512
#define DEFAULT_MAX_QUEUE_SIZE 5  
#define DEFAULT_STATUS_INTERVAL_MS 300000

typedef struct {
    char topic[MQTT_TOPIC_MAX_LENGTH];
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
    QueueHandle_t pending_messages;
    
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
    int64_t last_heartbeat_time;
    
    mqtt_manager_message_callback_t message_callback;
    void *message_user_data;
    mqtt_manager_state_callback_t state_callback;
    void *state_user_data;
    
    char panel_id[32];        
    char client_panel_id[32]; 
    
    char config_topic[MQTT_TOPIC_MAX_LENGTH];
} mqtt_manager_context_t;

static mqtt_manager_context_t s_mqtt_manager_ctx = {0};

static esp_err_t validate_esp32_id(const char *esp32_id) {
    if (esp32_id == NULL) {
        ESP_LOGE(TAG, "ESP32 ID is NULL");
        return ESP_ERR_INVALID_ARG;
    }
    
    size_t len = strlen(esp32_id);
    if (len == 0) {
        ESP_LOGE(TAG, "ESP32 ID is empty");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (len > ESP32_ID_LENGTH) {
        ESP_LOGE(TAG, "ESP32 ID too long: %zu chars (max %d)", len, ESP32_ID_LENGTH);
        return ESP_ERR_INVALID_ARG;
    }
    
    for (size_t i = 0; i < len; i++) {
        char c = esp32_id[i];
        if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
            ESP_LOGE(TAG, "ESP32 ID contains invalid character: '%c' at position %zu", c, i);
            return ESP_ERR_INVALID_ARG;
        }
    }
    
    return ESP_OK;
}

static void generate_message_id(char *buffer, size_t size) {
    uint32_t random = esp_random();
    snprintf(buffer, size, "msg_%" PRIu32, random);
}

static void mqtt_event_handler(void *handler_args, esp_event_base_t base, int32_t event_id, void *event_data) {
    esp_mqtt_event_handle_t event = (esp_mqtt_event_handle_t)event_data;
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    ctx->last_activity_time = esp_timer_get_time() / 1000;
    
    switch (event->event_id) {
        case MQTT_EVENT_CONNECTED:
            ESP_LOGI(TAG, "MQTT connected");
            
            if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
                ctx->state = MQTT_MANAGER_STATE_CONNECTED;
                ctx->was_connected = true;
                ctx->reconnect_attempts = 0;
                xSemaphoreGive(ctx->mutex);
            }
            
            xEventGroupClearBits(ctx->event_group, MQTT_DISCONNECTED_BIT | MQTT_ERROR_BIT);
            xEventGroupSetBits(ctx->event_group, MQTT_CONNECTED_BIT);
            
            if (ctx->state_callback) {
                ctx->state_callback(ctx->state, ctx->state_user_data);
            }
            
            if (strlen(ctx->esp32_id) > 0) {
                snprintf(ctx->config_topic, sizeof(ctx->config_topic), "esp32/config/%s", ctx->esp32_id);
                mqtt_manager_subscribe(ctx->config_topic, 0);
                
                char reset_topic[MQTT_TOPIC_MAX_LENGTH];
                snprintf(reset_topic, sizeof(reset_topic), "esp32/config/%s/reset", ctx->esp32_id);
                mqtt_manager_subscribe(reset_topic, 0);
                
                char deleted_topic[MQTT_TOPIC_MAX_LENGTH];
                snprintf(deleted_topic, sizeof(deleted_topic), "esp32/notify/%s/deleted", ctx->esp32_id);
                mqtt_manager_subscribe(deleted_topic, 1);
                ESP_LOGI(TAG, "Subscribed to deletion notifications: %s", deleted_topic);
            }
            
            mqtt_pending_message_t pending_msg;
            int pending_count = 0;
            while (xQueueReceive(ctx->pending_messages, &pending_msg, 0) == pdTRUE && pending_count < 3) {
                esp_mqtt_client_publish(event->client, pending_msg.topic, pending_msg.data, 
                                        pending_msg.data_len, pending_msg.qos, pending_msg.retain);
                pending_count++;
                vTaskDelay(pdMS_TO_TICKS(50));
            }
            break;
            
        case MQTT_EVENT_DISCONNECTED:
            ESP_LOGW(TAG, "MQTT disconnected");
            
            if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
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
            break;
            
        case MQTT_EVENT_DATA:
            if (ctx->message_callback && event->topic_len < MQTT_TOPIC_MAX_LENGTH && event->data_len < DEFAULT_BUFFER_SIZE) {
                char *topic_buf = heap_caps_malloc(MQTT_TOPIC_MAX_LENGTH, MALLOC_CAP_8BIT);
                char *data_buf = heap_caps_malloc(DEFAULT_BUFFER_SIZE, MALLOC_CAP_8BIT);
                
                if (topic_buf && data_buf) {
                    memcpy(topic_buf, event->topic, event->topic_len);
                    topic_buf[event->topic_len] = '\0';
                    
                    memcpy(data_buf, event->data, event->data_len);
                    data_buf[event->data_len] = '\0';
                    
                    ctx->message_callback(topic_buf, data_buf, event->data_len, ctx->message_user_data);
                }
                
                if (topic_buf) {
                    free(topic_buf);
                }
                if (data_buf) {
                    free(data_buf);
                }
            }
            break;
            
        case MQTT_EVENT_ERROR:
            ESP_LOGE(TAG, "MQTT error");
            
            if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(5)) == pdTRUE) {
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
            break;
    }
}

esp_err_t mqtt_manager_init(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group != NULL) {
        ESP_LOGW(TAG, "Already initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Initializing MQTT Manager");
    
    ctx->state = MQTT_MANAGER_STATE_INIT;
    ctx->was_connected = false;
    ctx->reconnect_attempts = 0;
    ctx->last_activity_time = 0;
    ctx->last_heartbeat_time = 0;
    ctx->last_reconnect_time = 0;
    
    ctx->event_group = xEventGroupCreate();
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "Failed to create event group");
        return ESP_ERR_NO_MEM;
    }
    
    ctx->mutex = xSemaphoreCreateMutex();
    if (ctx->mutex == NULL) {
        vEventGroupDelete(ctx->event_group);
        ctx->event_group = NULL;
        ESP_LOGE(TAG, "Failed to create mutex");
        return ESP_ERR_NO_MEM;
    }
    
    ctx->pending_messages = xQueueCreate(DEFAULT_MAX_QUEUE_SIZE, sizeof(mqtt_pending_message_t));
    if (ctx->pending_messages == NULL) {
        vSemaphoreDelete(ctx->mutex);
        vEventGroupDelete(ctx->event_group);
        ctx->mutex = NULL;
        ctx->event_group = NULL;
        ESP_LOGE(TAG, "Failed to create message queue");
        return ESP_ERR_NO_MEM;
    }
    
    strncpy(ctx->broker_url, DEFAULT_BROKER, sizeof(ctx->broker_url) - 1);
    ctx->broker_url[sizeof(ctx->broker_url) - 1] = '\0';
    ctx->port = DEFAULT_PORT;
    ctx->use_ssl = true;
    ctx->lwt_qos = 1;
    ctx->lwt_retain = false;
    
    ctx->state = MQTT_MANAGER_STATE_DISCONNECTED;
    xEventGroupSetBits(ctx->event_group, MQTT_DISCONNECTED_BIT);
    
    ESP_LOGI(TAG, "MQTT Manager initialized");
    return ESP_OK;
}

esp_err_t mqtt_manager_set_esp32_id(const char *esp32_id) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "Not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    esp_err_t validation_result = validate_esp32_id(esp32_id);
    if (validation_result != ESP_OK) {
        return validation_result;
    }
    
    ESP_LOGI(TAG, "Setting credentials for ESP32 ID: %s", esp32_id);
    
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
        
        char timestamp_str[MQTT_TIMESTAMP_MAX_LENGTH];
        if (time_manager_is_synchronized()) {
            time_manager_get_timestamp(timestamp_str, sizeof(timestamp_str));
        } else {
            snprintf(timestamp_str, sizeof(timestamp_str), "%lld", (long long)(esp_timer_get_time() / 1000));
        }
        
        snprintf(ctx->lwt_message, sizeof(ctx->lwt_message),
                "{\"esp32_id\":\"%.8s\",\"status\":\"OFFLINE\",\"timestamp\":%.32s}",
                esp32_id, timestamp_str);
        
        xSemaphoreGive(ctx->mutex);
    }
    
    ESP_LOGI(TAG, "Credentials configured");
    return ESP_OK;
}

esp_err_t mqtt_manager_connect(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "Not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (strlen(ctx->esp32_id) == 0) {
        ESP_LOGE(TAG, "ESP32 ID not set");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (mqtt_manager_is_connected() || ctx->state == MQTT_MANAGER_STATE_CONNECTING) {
        ESP_LOGW(TAG, "Already connected or connecting");
        return ESP_OK;
    }
    
    if (!wifi_manager_is_connected()) {
        ESP_LOGE(TAG, "WiFi not connected");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Connecting to MQTT broker");
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        ctx->state = MQTT_MANAGER_STATE_CONNECTING;
        xEventGroupClearBits(ctx->event_group, MQTT_CONNECTED_BIT | MQTT_ERROR_BIT);
        
        if (ctx->client != NULL) {
            esp_mqtt_client_destroy(ctx->client);
            ctx->client = NULL;
        }
        
        for (int i = 0; i < 3; i++) {
            heap_caps_check_integrity_all(true);
            vTaskDelay(pdMS_TO_TICKS(100));
        }
        
        #ifdef CONFIG_ESP_TASK_WDT_EN
        esp_task_wdt_reset();
        #endif
        
        char cleaned_broker[MQTT_BROKER_MAX_LENGTH];
        strncpy(cleaned_broker, ctx->broker_url, sizeof(cleaned_broker) - 1);
        cleaned_broker[sizeof(cleaned_broker) - 1] = '\0';
        
        char *clean_start = cleaned_broker;
        while (*clean_start && (*clean_start == ':' || *clean_start == '/' || *clean_start <= ' ')) {
            clean_start++;
        }
        
        if (strlen(clean_start) == 0) {
            ESP_LOGE(TAG, "Broker URL is empty after cleaning");
            ctx->state = MQTT_MANAGER_STATE_ERROR;
            xEventGroupSetBits(ctx->event_group, MQTT_ERROR_BIT);
            xSemaphoreGive(ctx->mutex);
            return ESP_ERR_INVALID_ARG;
        }
        
        struct addrinfo hints, *result;
        memset(&hints, 0, sizeof(hints));
        hints.ai_family = AF_INET;
        hints.ai_socktype = SOCK_STREAM;
        
        ESP_LOGI(TAG, "Testing DNS resolution for: %s", clean_start);
        int dns_result = getaddrinfo(clean_start, NULL, &hints, &result);
        if (dns_result != 0) {
            ESP_LOGE(TAG, "DNS resolution failed for %s: error %d", clean_start, dns_result);
            ctx->state = MQTT_MANAGER_STATE_ERROR;
            xEventGroupSetBits(ctx->event_group, MQTT_ERROR_BIT);
            xSemaphoreGive(ctx->mutex);
            return ESP_FAIL;
        } else {
            ESP_LOGI(TAG, "DNS resolution successful for %s", clean_start);
            freeaddrinfo(result);
        }
        
        char uri[MQTT_URI_MAX_LENGTH];
        int uri_len = snprintf(uri, sizeof(uri), "%s://%s:%d", 
                              ctx->use_ssl ? "mqtts" : "mqtt", 
                              clean_start, 
                              ctx->port);
        
        if (uri_len < 0 || uri_len >= sizeof(uri)) {
            ESP_LOGE(TAG, "URI too long or formatting error");
            ctx->state = MQTT_MANAGER_STATE_ERROR;
            xEventGroupSetBits(ctx->event_group, MQTT_ERROR_BIT);
            xSemaphoreGive(ctx->mutex);
            return ESP_ERR_INVALID_SIZE;
        }
        
        ESP_LOGI(TAG, "Connecting to URI: %s", uri);
        
        esp_mqtt_client_config_t mqtt_cfg = {0};
        mqtt_cfg.broker.address.uri = uri;
        mqtt_cfg.credentials.client_id = ctx->client_id;
        mqtt_cfg.credentials.username = ctx->username;
        mqtt_cfg.credentials.authentication.password = ctx->password;
        mqtt_cfg.session.keepalive = DEFAULT_KEEPALIVE;
        mqtt_cfg.session.disable_clean_session = false;
        
        mqtt_cfg.network.timeout_ms = 60000;
        mqtt_cfg.network.reconnect_timeout_ms = 30000;
        
        if (time_manager_is_synchronized()) {
            char timestamp_str[MQTT_TIMESTAMP_MAX_LENGTH];
            time_manager_get_timestamp(timestamp_str, sizeof(timestamp_str));
            snprintf(ctx->lwt_message, sizeof(ctx->lwt_message),
                    "{\"esp32_id\":\"%s\",\"status\":\"OFFLINE\",\"timestamp\":%s}",
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
                xEventGroupSetBits(ctx->event_group, MQTT_ERROR_BIT);
                xSemaphoreGive(ctx->mutex);
                ESP_LOGE(TAG, "SSL config failed");
                return ssl_ret;
            }
        }
        
        ctx->client = esp_mqtt_client_init(&mqtt_cfg);
        if (ctx->client == NULL) {
            ctx->state = MQTT_MANAGER_STATE_ERROR;
            xEventGroupSetBits(ctx->event_group, MQTT_ERROR_BIT);
            xSemaphoreGive(ctx->mutex);
            ESP_LOGE(TAG, "Failed to create MQTT client");
            return ESP_FAIL;
        }
        
        esp_mqtt_client_register_event(ctx->client, ESP_EVENT_ANY_ID, mqtt_event_handler, NULL);
        
        esp_err_t ret = esp_mqtt_client_start(ctx->client);
        if (ret != ESP_OK) {
            ESP_LOGE(TAG, "Failed to start MQTT client");
            esp_mqtt_client_destroy(ctx->client);
            ctx->client = NULL;
            ctx->state = MQTT_MANAGER_STATE_ERROR;
            xEventGroupSetBits(ctx->event_group, MQTT_ERROR_BIT);
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
        ESP_LOGI(TAG, "MQTT connected successfully");
        return ESP_OK;
    } else if (bits & MQTT_ERROR_BIT) {
        ESP_LOGE(TAG, "MQTT connection error");
        return ESP_FAIL;
    } else {
        ESP_LOGW(TAG, "MQTT connection timeout");
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
    
    ESP_LOGI(TAG, "Disconnecting from MQTT");
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        esp_err_t ret = esp_mqtt_client_stop(ctx->client);
        if (ret != ESP_OK) {
            xSemaphoreGive(ctx->mutex);
            return ret;
        }
        
        ctx->state = MQTT_MANAGER_STATE_DISCONNECTED;
        ctx->was_connected = false;
        xEventGroupClearBits(ctx->event_group, MQTT_CONNECTED_BIT | MQTT_ERROR_BIT);
        xEventGroupSetBits(ctx->event_group, MQTT_DISCONNECTED_BIT);
        
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
    
    if (qos < 0 || qos > 2) {
        qos = 1;
    }
    
    if (data_len > DEFAULT_BUFFER_SIZE) {
        ESP_LOGE(TAG, "Message too large: %d bytes", data_len);
        return ESP_ERR_INVALID_SIZE;
    }
    
    if (!mqtt_manager_is_connected()) {
        mqtt_pending_message_t pending_msg;
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
            ESP_LOGE(TAG, "Message queue full");
            return ESP_ERR_NO_MEM;
        }
        return ESP_OK;
    }
    
    int msg_id = esp_mqtt_client_publish(ctx->client, topic, data, data_len, qos, retain);
    if (msg_id < 0) {
        ESP_LOGE(TAG, "Failed to publish to: %s", topic);
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
    if (mqtt_manager_is_connected() && 
        (current_time - ctx->last_heartbeat_time > DEFAULT_STATUS_INTERVAL_MS)) {
        mqtt_manager_send_heartbeat();
    }
    
    if (ctx->state == MQTT_MANAGER_STATE_RECONNECTING && 
        (current_time - ctx->last_reconnect_time > DEFAULT_RECONNECT_TIMEOUT_MS)) {
        
        ctx->last_reconnect_time = current_time;
        
        if (wifi_manager_is_connected()) {
            ctx->reconnect_attempts++;
            
            if (ctx->reconnect_attempts <= DEFAULT_MAX_RETRIES) {
                ESP_LOGI(TAG, "MQTT reconnect attempt %d/%d", ctx->reconnect_attempts, DEFAULT_MAX_RETRIES);
                mqtt_manager_connect();
            } else {
                ESP_LOGE(TAG, "Max MQTT reconnect attempts reached");
                ctx->state = MQTT_MANAGER_STATE_ERROR;
                
                if (ctx->state_callback) {
                    ctx->state_callback(ctx->state, ctx->state_user_data);
                }
                
                if (current_time - ctx->last_reconnect_time > DEFAULT_RECONNECT_TIMEOUT_MS * 2) {
                    ctx->reconnect_attempts = 0;
                }
            }
        }
    }
    
    if (wifi_manager_is_connected() && 
        !mqtt_manager_is_connected() && 
        ctx->state != MQTT_MANAGER_STATE_CONNECTING && 
        ctx->state != MQTT_MANAGER_STATE_RECONNECTING) {
        
        if (current_time - ctx->last_reconnect_time > DEFAULT_RECONNECT_TIMEOUT_MS) {
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

esp_err_t mqtt_manager_send_network_info(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL || strlen(ctx->esp32_id) == 0) {
        return ESP_ERR_INVALID_STATE;
    }
    
    size_t free_heap = esp_get_free_heap_size();
    if (free_heap < 30000) {
        ESP_LOGW(TAG, "Insufficient memory for network info: %zu bytes", free_heap);
        return ESP_ERR_NO_MEM;
    }
    
    char *json_buffer = heap_caps_malloc(512, MALLOC_CAP_8BIT);
    if (!json_buffer) {
        ESP_LOGE(TAG, "Failed to allocate network info buffer");
        return ESP_ERR_NO_MEM;
    }
    
    char ip_address[16] = "0.0.0.0";
    wifi_manager_get_ip(ip_address, sizeof(ip_address));
    
    char timestamp_str[32];
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
                    snprintf(time_str, sizeof(time_str), "Time unavailable");
                }
            } else {
                snprintf(time_str, sizeof(time_str), "Time unavailable");
            }
        } else {
            snprintf(timestamp_str, sizeof(timestamp_str), "%lld", (long long)(esp_timer_get_time() / 1000));
            snprintf(time_str, sizeof(time_str), "System: %lld ms", (long long)(esp_timer_get_time() / 1000));
        }
    }
    
    const char *current_status = "ONLINE";
    
    if (strlen(ctx->panel_id) == 0 || strlen(ctx->client_panel_id) == 0) {
        current_status = "AWAITING_CONFIG";
        ESP_LOGI(TAG, "Sending network info with AWAITING_CONFIG status (no panel config)");
    }
    
    int len = snprintf(json_buffer, 512,
                      "{\"esp32_id\":\"%.8s\",\"MAC\":\"%.12s\",\"IP\":\"%.15s\","
                      "\"status\":\"%s\",\"timestamp\":%.20s,\"time\":\"%.31s\"}",
                      ctx->esp32_id, ctx->mac_address, ip_address, 
                      current_status,
                      timestamp_str, time_str);
    
    esp_err_t ret = ESP_FAIL;
    if (len > 0 && len < 512) {
        ret = mqtt_manager_publish("esp32/network_info", json_buffer, len, 0, false);
        
        if (ret == ESP_OK) {
            vTaskDelay(pdMS_TO_TICKS(10)); 
        }
    } else {
        ESP_LOGE(TAG, "JSON buffer overflow in network info");
        ret = ESP_ERR_NO_MEM;
    }
    
    free(json_buffer);
    
    return ret;
}

esp_err_t mqtt_manager_send_heartbeat(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL || strlen(ctx->esp32_id) == 0) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (!mqtt_manager_is_connected()) {
        return ESP_FAIL;
    }
    
    size_t free_heap = esp_get_free_heap_size();
    if (free_heap < 25000) {
        ESP_LOGW(TAG, "Insufficient memory for heartbeat: %zu bytes", free_heap);
        return ESP_ERR_NO_MEM;
    }
    
    char *json_buffer = heap_caps_malloc(384, MALLOC_CAP_8BIT);
    if (!json_buffer) {
        ESP_LOGE(TAG, "Failed to allocate heartbeat buffer");
        return ESP_ERR_NO_MEM;
    }
    
    char message_id[16];
    generate_message_id(message_id, sizeof(message_id));
    
    char topic[64];
    snprintf(topic, sizeof(topic), "system/status/%.8s", ctx->esp32_id);
    
    char timestamp_str[20];
    char time_str[32] = {0};
    
    if (time_manager_is_synchronized()) {
        time_manager_get_timestamp(timestamp_str, sizeof(timestamp_str));
        time_manager_get_lima_time_str(time_str, sizeof(time_str));
    } else {
        snprintf(timestamp_str, sizeof(timestamp_str), "%lld", (long long)(esp_timer_get_time() / 1000));
    }
    
    int len;
    if (strlen(ctx->client_panel_id) > 0 && strlen(ctx->panel_id) > 0) {
        if (time_str[0] != 0) {
            len = snprintf(json_buffer, 384,
                          "{\"esp32_id\":\"%.8s\",\"status\":\"ONLINE\","
                          "\"timestamp\":%.15s,\"type\":\"heartbeat\","
                          "\"message_id\":\"%.15s\",\"client_id\":\"%.31s\","
                          "\"panel_id\":\"%.31s\",\"time\":\"%.31s\"}",
                          ctx->esp32_id, timestamp_str, message_id,
                          ctx->client_panel_id, ctx->panel_id, time_str);
        } else {
            len = snprintf(json_buffer, 384,
                          "{\"esp32_id\":\"%.8s\",\"status\":\"ONLINE\","
                          "\"timestamp\":%.15s,\"type\":\"heartbeat\","
                          "\"message_id\":\"%.15s\",\"client_id\":\"%.31s\","
                          "\"panel_id\":\"%.31s\"}",
                          ctx->esp32_id, timestamp_str, message_id,
                          ctx->client_panel_id, ctx->panel_id);
        }
    } else {
        if (time_str[0] != 0) {
            len = snprintf(json_buffer, 384,
                          "{\"esp32_id\":\"%.8s\",\"status\":\"ONLINE\","
                          "\"timestamp\":%.15s,\"type\":\"heartbeat\","
                          "\"message_id\":\"%.15s\",\"time\":\"%.31s\"}",
                          ctx->esp32_id, timestamp_str, message_id, time_str);
        } else {
            len = snprintf(json_buffer, 384,
                          "{\"esp32_id\":\"%.8s\",\"status\":\"ONLINE\","
                          "\"timestamp\":%.15s,\"type\":\"heartbeat\","
                          "\"message_id\":\"%.15s\"}",
                          ctx->esp32_id, timestamp_str, message_id);
        }
    }
    
    esp_err_t ret = ESP_FAIL;
    if (len > 0 && len < 384) {
        ret = mqtt_manager_publish(topic, json_buffer, len, 1, false);
        
        if (ret == ESP_OK) {
            ctx->last_heartbeat_time = esp_timer_get_time() / 1000;
            vTaskDelay(pdMS_TO_TICKS(5));
        }
    } else {
        ESP_LOGE(TAG, "JSON buffer overflow in heartbeat");
        ret = ESP_ERR_NO_MEM;
    }
    
    free(json_buffer);
    
    return ret;
}

esp_err_t mqtt_manager_emergency_memory_cleanup(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGW(TAG, "Performing emergency MQTT memory cleanup");
    
    if (ctx->pending_messages) {
        mqtt_pending_message_t dummy_msg;
        int cleared = 0;
        while (xQueueReceive(ctx->pending_messages, &dummy_msg, 0) == pdTRUE) {
            cleared++;
        }
        if (cleared > 0) {
            ESP_LOGI(TAG, "Cleared %d pending messages", cleared);
        }
    }
    
    for (int i = 0; i < 5; i++) {
        heap_caps_check_integrity_all(true);
        vTaskDelay(pdMS_TO_TICKS(50));
    }
    
    size_t free_after = esp_get_free_heap_size();
    ESP_LOGI(TAG, "Memory after MQTT cleanup: %zu bytes", free_after);
    
    return ESP_OK;
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
        
        ESP_LOGI(TAG, "Panel config set: client=%s, panel=%s", client_id, panel_id);
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
    
    if (strlen(ctx->esp32_id) == 0) {
        return ESP_ERR_INVALID_STATE;
    }
    
    char topic[MQTT_TOPIC_MAX_LENGTH];
    snprintf(topic, sizeof(topic), "esp32/config/%s/response", ctx->esp32_id);
    
    char json_buffer[256];
    snprintf(json_buffer, sizeof(json_buffer),
             "{\"esp32_id\":\"%s\",\"status\":\"%s\",\"message\":\"%s\",\"timestamp\":%lld}",
             ctx->esp32_id,
             success ? "CONFIG_ACCEPTED" : "CONFIG_ERROR",
             message ? message : (success ? "Configuration applied successfully" : "Configuration failed"),
             (long long)(esp_timer_get_time() / 1000));
    
    return mqtt_manager_publish(topic, json_buffer, strlen(json_buffer), 1, false);
}

esp_err_t mqtt_manager_clear_panel_config(void) {
    mqtt_manager_context_t *ctx = &s_mqtt_manager_ctx;
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        memset(ctx->client_panel_id, 0, sizeof(ctx->client_panel_id));
        memset(ctx->panel_id, 0, sizeof(ctx->panel_id));
        
        xSemaphoreGive(ctx->mutex);
        
        ESP_LOGI(TAG, "Panel configuration cleared");
        return ESP_OK;
    }
    
    return ESP_ERR_TIMEOUT;
}