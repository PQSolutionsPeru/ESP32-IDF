#ifndef MQTT_MANAGER_H
#define MQTT_MANAGER_H

#include <stdbool.h>
#include "esp_err.h"
#include "esp32_id_manager.h"

#ifdef __cplusplus
extern "C" {
#endif

#define MQTT_TOPIC_MAX_LENGTH 256
#define MQTT_BROKER_MAX_LENGTH 128
#define MQTT_CLIENT_ID_MAX_LENGTH 64
#define MQTT_USERNAME_MAX_LENGTH 64
#define MQTT_PASSWORD_MAX_LENGTH 64
#define MQTT_LWT_MESSAGE_MAX_LENGTH 512
#define MQTT_URI_MAX_LENGTH 256
#define MQTT_TIMESTAMP_MAX_LENGTH 64
#define MQTT_JSON_BUFFER_MAX_LENGTH 1024

typedef enum {
    MQTT_MANAGER_STATE_INIT,
    MQTT_MANAGER_STATE_DISCONNECTED,
    MQTT_MANAGER_STATE_CONNECTING,
    MQTT_MANAGER_STATE_CONNECTED,
    MQTT_MANAGER_STATE_RECONNECTING,
    MQTT_MANAGER_STATE_ERROR
} mqtt_manager_state_t;

typedef void (*mqtt_manager_message_callback_t)(const char *topic, const char *data, int data_len, void *user_data);
typedef void (*mqtt_manager_state_callback_t)(mqtt_manager_state_t state, void *user_data);

esp_err_t mqtt_manager_init(void);
esp_err_t mqtt_manager_set_esp32_id(const char *esp32_id);
esp_err_t mqtt_manager_connect(void);
esp_err_t mqtt_manager_disconnect(void);
bool mqtt_manager_is_connected(void);
esp_err_t mqtt_manager_subscribe(const char *topic, int qos);
esp_err_t mqtt_manager_unsubscribe(const char *topic);
esp_err_t mqtt_manager_publish(const char *topic, const char *data, int data_len, int qos, bool retain);
esp_err_t mqtt_manager_publish_json(const char *topic, const char *json_data, int qos, bool retain);
esp_err_t mqtt_manager_loop(int timeout_ms);
esp_err_t mqtt_manager_set_message_callback(mqtt_manager_message_callback_t callback, void *user_data);
esp_err_t mqtt_manager_set_state_callback(mqtt_manager_state_callback_t callback, void *user_data);
mqtt_manager_state_t mqtt_manager_get_state(void);
esp_err_t mqtt_manager_send_network_info(void);
esp_err_t mqtt_manager_send_heartbeat(void);
esp_err_t mqtt_manager_emergency_memory_cleanup(void);
esp_err_t mqtt_manager_set_panel_config(const char *client_id, const char *panel_id);
esp_err_t mqtt_manager_get_panel_topic(char *topic, size_t size, const char *suffix);
esp_err_t mqtt_manager_send_config_response(bool success, const char *message);
esp_err_t mqtt_manager_clear_panel_config(void);

#ifdef __cplusplus
}
#endif

#endif