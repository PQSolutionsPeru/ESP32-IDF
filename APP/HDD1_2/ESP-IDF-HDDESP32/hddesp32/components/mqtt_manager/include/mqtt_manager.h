#ifndef MQTT_MANAGER_H
#define MQTT_MANAGER_H

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"
#include "esp_event.h"

#ifdef __cplusplus
extern "C" {
#endif

#define MQTT_BROKER_MAX_LENGTH 128
#define MQTT_CLIENT_ID_MAX_LENGTH 64
#define MQTT_USERNAME_MAX_LENGTH 64
#define MQTT_PASSWORD_MAX_LENGTH 64
#define MQTT_TOPIC_MAX_LENGTH 256
#define MQTT_LWT_MESSAGE_MAX_LENGTH 256

typedef enum {
    MQTT_MANAGER_STATE_INIT,
    MQTT_MANAGER_STATE_CONNECTING,
    MQTT_MANAGER_STATE_CONNECTED,
    MQTT_MANAGER_STATE_DISCONNECTED,
    MQTT_MANAGER_STATE_RECONNECTING,
    MQTT_MANAGER_STATE_ERROR,
    MQTT_MANAGER_STATE_DEGRADED
} mqtt_manager_state_t;

typedef enum {
    MQTT_RESOURCE_NORMAL,
    MQTT_RESOURCE_WARNING,
    MQTT_RESOURCE_CRITICAL,
    MQTT_RESOURCE_EMERGENCY
} mqtt_resource_status_t;

typedef enum {
    CONNECTIVITY_MSG_WIFI_LOST,
    CONNECTIVITY_MSG_WIFI_RECOVERED,
    CONNECTIVITY_MSG_INTERNET_LOST,
    CONNECTIVITY_MSG_INTERNET_RECOVERED,
    CONNECTIVITY_MSG_MQTT_LOST,
    CONNECTIVITY_MSG_MQTT_RECOVERED
} mqtt_connectivity_message_type_t;

typedef struct {
    mqtt_connectivity_message_type_t type;
    int64_t start_time_ms;
    int64_t end_time_ms;
    char ssid[33];
    char panel_name[64];
} mqtt_connectivity_message_t;

typedef struct {
    uint32_t total_messages_sent;
    uint32_t total_messages_received;
    uint32_t pending_messages_count;
    uint32_t buffer_pool_usage;
    uint32_t memory_usage_bytes;
    uint32_t connection_failures;
    uint32_t emergency_cleanups;
    int64_t last_message_time;
    int64_t last_cleanup_time;
    mqtt_resource_status_t resource_status;
} mqtt_manager_stats_t;

typedef struct {
    uint32_t max_pending_messages;
    uint32_t message_rate_limit_ms;
    uint32_t memory_threshold_bytes;
    uint32_t emergency_threshold_bytes;
    bool enable_backpressure;
    bool enable_emergency_mode;
    bool enable_resource_monitoring;
} mqtt_flow_control_config_t;

typedef void (*mqtt_manager_message_callback_t)(const char *topic, const char *data, int data_len, void *user_data);
typedef void (*mqtt_manager_state_callback_t)(mqtt_manager_state_t state, void *user_data);
typedef void (*mqtt_manager_resource_callback_t)(mqtt_resource_status_t status, const mqtt_manager_stats_t *stats, void *user_data);

esp_err_t mqtt_manager_init(void);
esp_err_t mqtt_manager_deinit(void);
esp_err_t mqtt_manager_set_esp32_id(const char *esp32_id);
esp_err_t mqtt_manager_connect(void);
esp_err_t mqtt_manager_disconnect(void);
bool mqtt_manager_is_connected(void);
esp_err_t mqtt_manager_subscribe(const char *topic, int qos);
esp_err_t mqtt_manager_unsubscribe(const char *topic);
esp_err_t mqtt_manager_publish(const char *topic, const char *data, int data_len, int qos, bool retain);
esp_err_t mqtt_manager_publish_json(const char *topic, const char *json_data, int qos, bool retain);
esp_err_t mqtt_manager_publish_with_flow_control(const char *topic, const char *data, int data_len, int qos, bool retain);
esp_err_t mqtt_manager_loop(int timeout_ms);
esp_err_t mqtt_manager_set_message_callback(mqtt_manager_message_callback_t callback, void *user_data);
esp_err_t mqtt_manager_set_state_callback(mqtt_manager_state_callback_t callback, void *user_data);
esp_err_t mqtt_manager_set_resource_callback(mqtt_manager_resource_callback_t callback, void *user_data);
mqtt_manager_state_t mqtt_manager_get_state(void);
esp_err_t mqtt_manager_send_network_info(void);
esp_err_t mqtt_manager_set_panel_config(const char *client_id, const char *panel_id);
esp_err_t mqtt_manager_get_panel_topic(char *topic, size_t size, const char *suffix);
esp_err_t mqtt_manager_send_config_response(bool success, const char *message);
esp_err_t mqtt_manager_clear_panel_config(void);
esp_err_t mqtt_manager_setup_panel_subscriptions(void);
esp_err_t mqtt_manager_cleanup_panel_subscriptions(void);
esp_err_t mqtt_manager_emergency_memory_cleanup(void);
esp_err_t mqtt_manager_send_heartbeat(void);
esp_err_t mqtt_manager_get_stats(mqtt_manager_stats_t *stats);
esp_err_t mqtt_manager_get_resource_status(mqtt_resource_status_t *status);
esp_err_t mqtt_manager_set_flow_control_config(const mqtt_flow_control_config_t *config);
esp_err_t mqtt_manager_get_flow_control_config(mqtt_flow_control_config_t *config);
esp_err_t mqtt_manager_pause_processing(void);
esp_err_t mqtt_manager_resume_processing(void);
bool mqtt_manager_is_processing_paused(void);
esp_err_t mqtt_manager_force_buffer_cleanup(void);
uint32_t mqtt_manager_get_pending_message_count(void);
bool mqtt_manager_is_under_memory_pressure(void);
esp_err_t mqtt_manager_send_connectivity_message(const mqtt_connectivity_message_t *msg);
esp_err_t mqtt_manager_send_connectivity_status_message(const char *status, const char *details);

#ifdef __cplusplus
}
#endif

#endif /* MQTT_MANAGER_H */