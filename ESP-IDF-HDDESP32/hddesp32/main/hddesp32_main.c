#include <stdio.h>
#include <inttypes.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_system.h"
#include "custom_logging.h"
#include "nvs_flash.h"
#include "config_manager.h"
#include "esp32_id_manager.h"
#include "wifi_manager.h"
#include "wifi_captive_portal.h"
#include "mqtt_manager.h"
#include "time_manager.h"
#include "watchdog_manager.h"
#include "relay_manager.h"
#include "connectivity_monitor.h"
#include "esp_heap_caps.h"
#include "esp_task_wdt.h"
#include "config_processor.h"
#include "esp_timer.h"
#include "spiffs_log.h"
#include "log_uploader.h"

static const char *TAG = "HDDESP32";

static bool g_system_initialized = false;
static bool g_wifi_connected = false;
static bool g_mqtt_connected = false;
static bool g_watchdog_available = false;
static bool g_relay_manager_initialized = false;
static bool g_need_reregister = false;
static bool g_connectivity_monitor_started = false;

static char g_mqtt_temp_topic[128];
static char g_esp32_id_buffer[ESP32_ID_LENGTH + 1];

static char g_pending_relay_configs[5][400];
static int g_pending_config_count = 0;
static bool g_system_ready_for_relays = false;

static SemaphoreHandle_t g_callback_mutex = NULL;
static SemaphoreHandle_t g_global_state_mutex = NULL;

typedef struct {
    char topic[128];
    char data[1024];
    int data_len;
    bool pending;
} deferred_mqtt_message_t;

static QueueHandle_t g_mqtt_message_queue = NULL;

static void debug_connectivity_events(void) {
    int count = connectivity_monitor_get_pending_event_count();
    bool has_events = connectivity_monitor_has_pending_ram_events();
    
    size_t total_events, pending_events, memory_used;
    esp_err_t stats_ret = connectivity_monitor_get_ram_usage_stats(&total_events, &pending_events, &memory_used);
    
    if (stats_ret != ESP_OK) {
        LOG_E(TAG, "CRITICAL: Failed to get connectivity stats - possible memory corruption");
        watchdog_manager_force_reset("connectivity_stats_failure");
        return;
    }
    
    bool critical_failure = false;
    
    if (count > 0 && !has_events) {
        critical_failure = true;
        LOG_E(TAG, "CRITICAL: Event count mismatch - count=%d but no events found", count);
    }
    
    if (has_events && count == 0) {
        critical_failure = true;
        LOG_E(TAG, "CRITICAL: Event detection mismatch - events found but count=0");
    }
    
    if (pending_events > 20) {
        critical_failure = true;
        LOG_E(TAG, "CRITICAL: Event queue overflow - pending=%zu (max=20)", pending_events);
    }
    
    if (memory_used > 8192) {
        critical_failure = true;
        LOG_E(TAG, "CRITICAL: Connectivity memory leak - using %zu bytes", memory_used);
    }
    
    if (count > 0 || has_events || total_events > 10) {
        LOG_D(TAG, "Connectivity activity: events=%zu, pending=%zu, memory=%zu", 
              total_events, pending_events, memory_used);
    }
    
    if (critical_failure) {
        LOG_E(TAG, "CRITICAL: Connectivity system failure detected - forcing restart");
        watchdog_manager_force_reset("connectivity_critical_failure");
    }
}

static bool get_wifi_connected(void) {
    bool result = false;
    if (xSemaphoreTake(g_global_state_mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
        result = g_wifi_connected;
        xSemaphoreGive(g_global_state_mutex);
    }
    return result;
}

static void set_wifi_connected(bool connected) {
    if (xSemaphoreTake(g_global_state_mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
        g_wifi_connected = connected;
        xSemaphoreGive(g_global_state_mutex);
    }
}

static bool get_mqtt_connected(void) {
    bool result = false;
    if (xSemaphoreTake(g_global_state_mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
        result = g_mqtt_connected;
        xSemaphoreGive(g_global_state_mutex);
    }
    return result;
}

static void set_mqtt_connected(bool connected) {
    if (xSemaphoreTake(g_global_state_mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
        g_mqtt_connected = connected;
        xSemaphoreGive(g_global_state_mutex);
    }
}

static bool get_relay_manager_initialized(void) {
    bool result = false;
    if (xSemaphoreTake(g_global_state_mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
        result = g_relay_manager_initialized;
        xSemaphoreGive(g_global_state_mutex);
    }
    return result;
}

static void set_relay_manager_initialized(bool initialized) {
    if (xSemaphoreTake(g_global_state_mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
        g_relay_manager_initialized = initialized;
        xSemaphoreGive(g_global_state_mutex);
    }
}

static void connectivity_event_callback(const connectivity_event_t *event, void *user_data) {
    if (!event) {
        return;
    }

    LOG_I(TAG, "Connectivity event detected: type=%d, panel=%s, stored_in_ram", 
          event->type, event->panel_name);
}

static void start_connectivity_monitoring_if_ready(void) {
    if (g_connectivity_monitor_started) {
        return;
    }
    
    if (!get_wifi_connected() || !get_mqtt_connected() || !get_relay_manager_initialized()) {
        return;
    }
    
    LOG_I(TAG, "System stable - starting connectivity monitoring");
    
    esp_err_t ret = connectivity_monitor_start();
    if (ret == ESP_OK) {
        g_connectivity_monitor_started = true;
        LOG_I(TAG, "Connectivity monitoring started successfully");
    } else {
        LOG_E(TAG, "Failed to start connectivity monitoring: %s", esp_err_to_name(ret));
    }
}

static void print_memory_info_simple(void) {
    size_t free_heap = esp_get_free_heap_size();
    size_t min_heap = esp_get_minimum_free_heap_size();
    
    LOG_I(TAG, "Memory: free=%zu min=%zu", free_heap, min_heap);
    
    if (g_watchdog_available) {
        watchdog_manager_report_activity(WATCHDOG_CHECK_MEMORY);
    }
}

static char* find_json_value(const char *json, const char *key, char *value_buf, size_t buf_size) {
    char search_key[64];
    snprintf(search_key, sizeof(search_key), "\"%s\":", key);
    
    char *start = strstr(json, search_key);
    if (!start) {
        return NULL;
    }
    
    start += strlen(search_key);
    while (*start == ' ' || *start == '\t') start++;
    
    if (*start == '"') {
        start++;
        char *end = strchr(start, '"');
        if (!end) return NULL;
        
        size_t len = end - start;
        if (len >= buf_size) len = buf_size - 1;
        
        memcpy(value_buf, start, len);
        value_buf[len] = '\0';
        return value_buf;
    } else {
        char *end = start;
        while (*end && *end != ',' && *end != '}' && *end != ' ') end++;
        
        size_t len = end - start;
        if (len >= buf_size) len = buf_size - 1;
        
        memcpy(value_buf, start, len);
        value_buf[len] = '\0';
        return value_buf;
    }
}

static bool parse_json_bool(const char *json, const char *key) {
    char value_buf[16];
    char *value = find_json_value(json, key, value_buf, sizeof(value_buf));
    if (!value) return false;
    
    return (strcmp(value, "true") == 0 || strcmp(value, "1") == 0);
}

static void process_pending_relay_configs(void) {
    if (g_pending_config_count == 0 || !get_relay_manager_initialized()) {
        return;
    }
    
    LOG_I(TAG, "Processing %d pending relay configurations", g_pending_config_count);
    
    bool relay_processed[RELAY_MANAGER_MAX_RELAYS] = {false};
    
    for (int i = 0; i < g_pending_config_count; i++) {
        if (g_watchdog_available) {
            watchdog_manager_feed();
        }
        
        char command_val[32], relay_id_val[32];
        
        if (find_json_value(g_pending_relay_configs[i], "command", command_val, sizeof(command_val)) &&
            find_json_value(g_pending_relay_configs[i], "relay_id", relay_id_val, sizeof(relay_id_val)) &&
            strcmp(command_val, "update_config") == 0) {
            
            int relay_num = -1;
            if (sscanf(relay_id_val, "relay_%d", &relay_num) == 1 && 
                relay_num >= 1 && relay_num <= RELAY_MANAGER_MAX_RELAYS) {
                
                int relay_index = relay_num - 1;
                
                if (relay_processed[relay_index]) {
                    LOG_W(TAG, "Relay %s already processed - skipping", relay_id_val);
                    continue;
                }
                
                relay_processed[relay_index] = true;
            }
            
            bool is_active = parse_json_bool(g_pending_relay_configs[i], "is_active");
            esp_err_t ret = relay_manager_set_active(relay_id_val, is_active);
            if (ret == ESP_OK) {
                LOG_I(TAG, "Relay %s %s", relay_id_val, is_active ? "ACTIVATED" : "DEACTIVATED");
                vTaskDelay(pdMS_TO_TICKS(50));
                
                char contact_type_val[16];
                if (find_json_value(g_pending_relay_configs[i], "contact_type", contact_type_val, sizeof(contact_type_val))) {
                    relay_contact_type_t type = (strcmp(contact_type_val, "NC") == 0) ? 
                                              RELAY_CONTACT_NC : RELAY_CONTACT_NO;
                    relay_manager_set_contact_type(relay_id_val, type);
                    LOG_I(TAG, "Relay %s contact type: %s", relay_id_val, contact_type_val);
                }
            } else {
                LOG_E(TAG, "Failed to activate relay %s", relay_id_val);
            }
        }
        vTaskDelay(pdMS_TO_TICKS(50));
    }
    
    g_pending_config_count = 0;
    
    vTaskDelay(pdMS_TO_TICKS(200));
    
    LOG_I(TAG, "Force reconfiguring interrupts after all relay activations");
    esp_err_t force_result = relay_manager_force_reconfigure_interrupts();
    if (force_result != ESP_OK) {
        LOG_W(TAG, "Force reconfiguration failed, but continuing...");
    }
    
    vTaskDelay(pdMS_TO_TICKS(100));
    relay_manager_report_initial_states();
    
    LOG_I(TAG, "Pending configurations processed");
    
    uint32_t total_interrupts = 0;
    relay_manager_get_interrupt_stats(&total_interrupts, NULL, NULL);
    LOG_I(TAG, "Total interrupts received after config: %lu", total_interrupts);
}

static void watchdog_event_callback(const watchdog_event_context_t *context, void *user_data) {
    if (!context) {
        return;
    }
    
    watchdog_health_status_t status = context->status;
    watchdog_check_type_t check_type = context->check_type;
    
    const char* status_str = "UNKNOWN";
    const char* check_str = "UNKNOWN";
    
    switch (status) {
        case WATCHDOG_HEALTH_GOOD: status_str = "GOOD"; break;
        case WATCHDOG_HEALTH_WARNING: status_str = "WARNING"; break;
        case WATCHDOG_HEALTH_CRITICAL: status_str = "CRITICAL"; break;
        case WATCHDOG_HEALTH_ERROR: status_str = "ERROR"; break;
        case WATCHDOG_HEALTH_EMERGENCY: status_str = "EMERGENCY"; break;
    }
    
    switch (check_type) {
        case WATCHDOG_CHECK_MEMORY: check_str = "MEMORY"; break;
        case WATCHDOG_CHECK_WIFI: check_str = "WIFI"; break;
        case WATCHDOG_CHECK_MQTT: check_str = "MQTT"; break;
        case WATCHDOG_CHECK_TASKS: check_str = "TASKS"; break;
        case WATCHDOG_CHECK_SYSTEM: check_str = "SYSTEM"; break;
    }
    
    LOG_W(TAG, "Watchdog event: %s - %s (mem: %lu, feeds: %lu)", 
          check_str, status_str, context->current_memory, context->feed_failures);
    
    if (context->additional_info) {
        LOG_I(TAG, "Additional info: %s", context->additional_info);
    }
    
    if (status == WATCHDOG_HEALTH_CRITICAL || status == WATCHDOG_HEALTH_EMERGENCY) {
        if (get_relay_manager_initialized()) {
            LOG_I(TAG, "Saving relay states before potential system restart");
            relay_manager_save_current_states();
            config_manager_force_commit();
        }
        
        switch (check_type) {
            case WATCHDOG_CHECK_MEMORY:
                LOG_E(TAG, "Critical memory situation detected");
                break;
                
            case WATCHDOG_CHECK_WIFI:
                LOG_E(TAG, "Critical WiFi failure detected");
                if (!wifi_manager_is_connected()) {
                    char ap_ssid[33];
                    esp_err_t ret = wifi_manager_generate_ap_ssid(ap_ssid, sizeof(ap_ssid), "FirePanel");
                    if (ret == ESP_OK) {
                        LOG_I(TAG, "Starting emergency AP mode: %s", ap_ssid);
                        wifi_manager_start_ap_mode(ap_ssid, "firepanel");
                    }
                }
                break;
                
            case WATCHDOG_CHECK_MQTT:
                LOG_E(TAG, "Critical MQTT failure detected");
                if (esp32_id_manager_get_id(g_esp32_id_buffer, sizeof(g_esp32_id_buffer)) == ESP_OK) {
                    mqtt_manager_set_esp32_id(g_esp32_id_buffer);
                    mqtt_manager_connect();
                }
                break;
                
            case WATCHDOG_CHECK_TASKS:
                LOG_E(TAG, "Critical task failure detected");
                break;
                
            case WATCHDOG_CHECK_SYSTEM:
                LOG_E(TAG, "Critical system failure detected");
                break;
        }
    } else if (status == WATCHDOG_HEALTH_WARNING) {
        LOG_W(TAG, "System degradation detected in %s subsystem", check_str);
    }
}

static void relay_state_change_callback(const relay_event_t *event, void *user_data) {
    if (!get_mqtt_connected() || !event) {
        return;
    }

    if (xSemaphoreTake(g_callback_mutex, pdMS_TO_TICKS(50)) != pdTRUE) {
        return;
    }
    
    if (g_watchdog_available) {
        watchdog_manager_report_activity(WATCHDOG_CHECK_TASKS);
    }
    
    char simple_json[120];
    int len = snprintf(simple_json, sizeof(simple_json),
                      "{"
                      "\"relay\":\"%s\","
                      "\"status\":\"%s\","
                      "\"timestamp\":%lld,"
                      "\"contact_type\":\"%s\","
                      "\"old_status\":\"%s\""
                      "}",
                      event->relay_id,
                      (event->new_state == RELAY_STATE_OK) ? "OK" : "DISC",
                      (long long)event->timestamp,
                      (event->contact_type == RELAY_CONTACT_NC) ? "NC" : "NO",
                      (event->old_state == RELAY_STATE_OK) ? "OK" : "DISC");
    
    if (len > 0 && len < sizeof(simple_json)) {
        char relay_topic[88];
        esp_err_t topic_ret = mqtt_manager_get_panel_topic(relay_topic, sizeof(relay_topic), "relays");
        
        if (topic_ret == ESP_OK) {
            mqtt_manager_publish_json(relay_topic, simple_json, 1, false);
            LOG_I(TAG, "Relay %s state: %s -> %s", 
                  event->relay_id,
                  (event->old_state == RELAY_STATE_OK) ? "OK" : "DISC",
                  (event->new_state == RELAY_STATE_OK) ? "OK" : "DISC");
        }
    }
    
    xSemaphoreGive(g_callback_mutex);
}

static esp_err_t relay_mqtt_command_callback(const char *topic, const char *command_json, void *user_data) {
    LOG_I(TAG, "Processing relay MQTT command from topic: %s", topic);
    
    if (g_watchdog_available) {
        watchdog_manager_report_activity(WATCHDOG_CHECK_MQTT);
    }
    
    return ESP_OK;
}

static void process_deferred_mqtt_message(const char *topic, const char *data, int data_len) {
    if (esp32_id_manager_get_id(g_esp32_id_buffer, sizeof(g_esp32_id_buffer)) != ESP_OK) {
        LOG_E(TAG, "Could not get ESP32 ID");
        return;
    }
    
    snprintf(g_mqtt_temp_topic, sizeof(g_mqtt_temp_topic), "esp32/notify/%s/deleted", g_esp32_id_buffer);
    
    if (strcmp(topic, g_mqtt_temp_topic) == 0) {
        LOG_W(TAG, "Deletion notification received");
        
        config_manager_erase_key("client_id");
        config_manager_erase_key("panel_id");
        config_manager_erase_key("panel_name");
        config_manager_erase_key("location");
        mqtt_manager_clear_panel_config();
        mqtt_manager_cleanup_panel_subscriptions();
        
        if (get_relay_manager_initialized()) {
            relay_mgr_state_t state = relay_manager_get_mgr_state();
            if (state == RELAY_MGR_STATE_RUNNING) {
                relay_manager_deinit();
                set_relay_manager_initialized(false);
                vTaskDelay(pdMS_TO_TICKS(1000));
            }
        }
        
        time_manager_reset_network_info_sent();
        g_need_reregister = true;
        vTaskDelay(pdMS_TO_TICKS(500));
        mqtt_manager_send_network_info();
        return;
    }
    
    snprintf(g_mqtt_temp_topic, sizeof(g_mqtt_temp_topic), "esp32/config/%s", g_esp32_id_buffer);
    
    if (strcmp(topic, g_mqtt_temp_topic) == 0) {
        LOG_I(TAG, "Configuration message received");
        
        esp_err_t ret = process_esp32_configuration(data);
        
        if (ret == ESP_OK) {
            LOG_I(TAG, "Configuration accepted");
            
            if (!get_relay_manager_initialized()) {
                vTaskDelay(pdMS_TO_TICKS(200));
                LOG_I(TAG, "Initializing Relay Manager");
                ret = relay_manager_init();
                if (ret == ESP_OK) {
                    relay_manager_set_state_callback(relay_state_change_callback, NULL);
                    relay_manager_set_mqtt_callback(relay_mqtt_command_callback, NULL);
                    set_relay_manager_initialized(true);
                }
            }
        }
        return;
    }
    
    if (strstr(topic, "/relay_config")) {
        char expected_topic[MQTT_TOPIC_MAX_LENGTH];
        esp_err_t topic_ret = mqtt_manager_get_panel_topic(expected_topic, sizeof(expected_topic), "relay_config");
        
        if (topic_ret != ESP_OK || strcmp(topic, expected_topic) != 0) {
            LOG_W(TAG, "Relay config not for our panel - ignoring. Expected: %s, Got: %s", 
                  (topic_ret == ESP_OK) ? expected_topic : "UNKNOWN", topic);
            return;
        }
        
        static char last_relay_config[400] = {0};
        static int64_t last_relay_config_time = 0;
        int64_t current_time = esp_timer_get_time() / 1000;
        
        if (data_len < 400 && 
            strcmp(data, last_relay_config) == 0 && 
            (current_time - last_relay_config_time) < 2000) {
            LOG_W(TAG, "Duplicate relay config detected - ignoring");
            return;
        }
        
        if (data_len < 400) {
            memcpy(last_relay_config, data, data_len);
            last_relay_config[data_len] = '\0';
            last_relay_config_time = current_time;
        }
        
        if (!get_relay_manager_initialized() || !g_system_ready_for_relays) {
            LOG_W(TAG, "Relay config received but system not ready - queuing for later");
            
            if (g_pending_config_count < 5 && data_len < 400) {
                memcpy(g_pending_relay_configs[g_pending_config_count], data, data_len);
                g_pending_relay_configs[g_pending_config_count][data_len] = '\0';
                g_pending_config_count++;
                LOG_I(TAG, "Queued relay config %d/5", g_pending_config_count);
            } else {
                LOG_W(TAG, "Config queue full or message too large, dropping");
            }
            return;
        }
        
        relay_mgr_state_t mgr_state = relay_manager_get_mgr_state();
        if (mgr_state != RELAY_MGR_STATE_RUNNING) {
            LOG_W(TAG, "Relay manager not in running state (%d), delaying config", mgr_state);
            vTaskDelay(pdMS_TO_TICKS(500));
            
            if (relay_manager_get_mgr_state() != RELAY_MGR_STATE_RUNNING) {
                LOG_E(TAG, "Relay manager still not ready, ignoring config");
                return;
            }
        }
        
        if (data_len > 2000) {
            LOG_W(TAG, "Relay config message too large, ignoring");
            return;
        }
        
        char command_val[32], relay_id_val[32], is_active_val[16];
        char contact_type_val[16], custom_name_val[64];
        
        if (find_json_value(data, "command", command_val, sizeof(command_val)) &&
            find_json_value(data, "relay_id", relay_id_val, sizeof(relay_id_val)) &&
            strcmp(command_val, "update_config") == 0) {
            
            LOG_I(TAG, "Processing config for relay %s", relay_id_val);
            
            if (g_watchdog_available) {
                watchdog_manager_feed();
            }
            
            if (find_json_value(data, "is_active", is_active_val, sizeof(is_active_val))) {
                bool is_active = parse_json_bool(data, "is_active");
                
                esp_err_t ret = relay_manager_set_active(relay_id_val, is_active);
                if (ret == ESP_OK) {
                    LOG_I(TAG, "Relay %s %s", relay_id_val, is_active ? "ACTIVATED" : "DEACTIVATED");
                    vTaskDelay(pdMS_TO_TICKS(50));
                    
                    if (find_json_value(data, "contact_type", contact_type_val, sizeof(contact_type_val))) {
                        relay_contact_type_t type = (strcmp(contact_type_val, "NC") == 0) ? 
                                                  RELAY_CONTACT_NC : RELAY_CONTACT_NO;
                        esp_err_t type_ret = relay_manager_set_contact_type(relay_id_val, type);
                        if (type_ret == ESP_OK) {
                            LOG_I(TAG, "Relay %s contact type set to %s", relay_id_val, contact_type_val);
                            vTaskDelay(pdMS_TO_TICKS(50));
                        } else {
                            LOG_E(TAG, "Failed to set contact type for %s: %s", relay_id_val, esp_err_to_name(type_ret));
                        }
                    }
                    
                    if (find_json_value(data, "custom_name", custom_name_val, sizeof(custom_name_val))) {
                        if (strlen(custom_name_val) > 0 && strlen(custom_name_val) < 32) {
                            relay_manager_set_name(relay_id_val, custom_name_val);
                            LOG_I(TAG, "Relay %s name set to '%.30s'", relay_id_val, custom_name_val);
                        }
                    }
                    
                    vTaskDelay(pdMS_TO_TICKS(100));
                    relay_manager_report_initial_states();
                    
                } else {
                    LOG_E(TAG, "Failed to set active state for relay %s: %s", relay_id_val, esp_err_to_name(ret));
                    vTaskDelay(pdMS_TO_TICKS(500));
                    esp_err_t retry_ret = relay_manager_set_active(relay_id_val, is_active);
                    if (retry_ret == ESP_OK) {
                        LOG_I(TAG, "Retry successful for relay %s", relay_id_val);
                    } else {
                        LOG_E(TAG, "Retry failed for relay %s", relay_id_val);
                    }
                }
            }
        }
    }
}

static void mqtt_message_callback(const char *topic, const char *data, int data_len, void *user_data) {
    if (!topic || !data || data_len <= 0 || data_len >= 1024) {
        return;
    }
    
    if (g_watchdog_available) {
        watchdog_manager_report_activity(WATCHDOG_CHECK_MQTT);
    }
    
    deferred_mqtt_message_t msg = {0};
    strncpy(msg.topic, topic, sizeof(msg.topic) - 1);
    msg.topic[sizeof(msg.topic) - 1] = '\0';
    
    memcpy(msg.data, data, data_len);
    msg.data[data_len] = '\0';
    msg.data_len = data_len;
    msg.pending = true;
    
    if (xQueueSend(g_mqtt_message_queue, &msg, 0) != pdTRUE) {
        deferred_mqtt_message_t dummy;
        if (xQueueReceive(g_mqtt_message_queue, &dummy, 0) == pdTRUE) {
            xQueueSend(g_mqtt_message_queue, &msg, 0);
        }
    }
}

static void mqtt_state_callback(mqtt_manager_state_t state, void *user_data) {
    switch (state) {
        case MQTT_MANAGER_STATE_CONNECTED:
            LOG_I(TAG, "MQTT connected");
            set_mqtt_connected(true);
            
            if (!get_relay_manager_initialized()) {
                LOG_I(TAG, "Initializing Relay Manager after MQTT connection");
                esp_err_t ret = relay_manager_init();
                if (ret == ESP_OK) {
                    relay_manager_set_state_callback(relay_state_change_callback, NULL);
                    relay_manager_set_mqtt_callback(relay_mqtt_command_callback, NULL);
                    set_relay_manager_initialized(true);
                    
                    vTaskDelay(pdMS_TO_TICKS(1000));
                    g_system_ready_for_relays = true;
                    
                    process_pending_relay_configs();
                    
                    vTaskDelay(pdMS_TO_TICKS(500));
                    relay_manager_detect_post_restart_changes();
                    
                    LOG_I(TAG, "Relay Manager initialized and ready for configurations");
                } else {
                    LOG_E(TAG, "Failed to initialize Relay Manager: %s", esp_err_to_name(ret));
                }
            } else {
                g_system_ready_for_relays = true;
                process_pending_relay_configs();
            }
            
            start_connectivity_monitoring_if_ready();
            
            vTaskDelay(pdMS_TO_TICKS(2000));
            
            LOG_I(TAG, "Checking for pending RAM connectivity events after MQTT connection");
            
            int event_count = connectivity_monitor_get_pending_event_count();
            bool has_events = connectivity_monitor_has_pending_ram_events();
            
            LOG_I(TAG, "Event count: %d, has_events: %s", event_count, has_events ? "true" : "false");
            
            if (has_events || event_count > 0) {
                LOG_I(TAG, "Processing stored RAM connectivity events (count: %d)", event_count);
                
                for (int retry = 0; retry < 3; retry++) {
                    esp_err_t ram_ret = connectivity_monitor_process_pending_events();
                    if (ram_ret == ESP_OK) {
                        LOG_I(TAG, "RAM connectivity events processed successfully on attempt %d", retry + 1);
                        break;
                    } else {
                        LOG_W(TAG, "Failed to process RAM events on attempt %d: %s", retry + 1, esp_err_to_name(ram_ret));
                        if (retry < 2) {
                            vTaskDelay(pdMS_TO_TICKS(1000));
                        }
                    }
                }
                
                int remaining_count = connectivity_monitor_get_pending_event_count();
                LOG_I(TAG, "Events remaining after processing: %d", remaining_count);
            } else {
                LOG_I(TAG, "No RAM connectivity events to process");
            }
            
            vTaskDelay(pdMS_TO_TICKS(1000));
            mqtt_manager_send_network_info();
            
            if (g_watchdog_available) {
                watchdog_manager_report_activity(WATCHDOG_CHECK_MQTT);
                
                if (get_wifi_connected() && get_mqtt_connected()) {
                    static bool watchdog_configured = false;
                    if (!watchdog_configured) {
                        vTaskDelay(pdMS_TO_TICKS(3000));
                        
                        if (get_relay_manager_initialized()) {
                            esp_err_t result = watchdog_manager_set_mode(WATCHDOG_MODE_RUNNING);
                            if (result == ESP_OK) {
                                watchdog_configured = true;
                                LOG_I(TAG, "Watchdog safely transitioned to running mode");
                            }
                        }
                    }
                }
            }
            
            if (strlen(g_esp32_id_buffer) > 0) {
                snprintf(g_mqtt_temp_topic, sizeof(g_mqtt_temp_topic), "esp32/config/%s", g_esp32_id_buffer);
                mqtt_manager_subscribe(g_mqtt_temp_topic, 2);
                
                snprintf(g_mqtt_temp_topic, sizeof(g_mqtt_temp_topic), "esp32/notify/%s/deleted", g_esp32_id_buffer);
                mqtt_manager_subscribe(g_mqtt_temp_topic, 2);
                
                LOG_I(TAG, "Suscrito a topics generales del ESP32");
            }
            
            wifi_manager_state_t wifi_state = wifi_manager_get_state();
            if (wifi_state == WIFI_MANAGER_STATE_STA_AP_MODE && 
                wifi_manager_is_connected() && mqtt_manager_is_connected()) {
                
                vTaskDelay(pdMS_TO_TICKS(5000));
                
                if (wifi_manager_is_connected() && mqtt_manager_is_connected()) {
                    LOG_I(TAG, "WiFi and MQTT stable - switching to STA mode only");
                    wifi_manager_set_sta_mode();
                }
            }
            break;
            
        case MQTT_MANAGER_STATE_DISCONNECTED:
            LOG_I(TAG, "MQTT disconnected");
            set_mqtt_connected(false);
            
            if (g_watchdog_available) {
                esp_err_t ret = watchdog_manager_set_mode(WATCHDOG_MODE_CONFIG);
                if (ret != ESP_OK) {
                    LOG_D(TAG, "Could not set watchdog to config mode");
                }
            }
            break;
            
        case MQTT_MANAGER_STATE_ERROR:
            LOG_W(TAG, "MQTT error");
            set_mqtt_connected(false);
            break;
            
        default:
            break;
    }
}

static void wifi_state_callback(wifi_manager_state_t state, void *user_data) {
    switch (state) {
        case WIFI_MANAGER_STATE_CONNECTED:
            LOG_I(TAG, "WiFi connected");
            set_wifi_connected(true);
            
            if (g_watchdog_available) {
                watchdog_manager_report_activity(WATCHDOG_CHECK_WIFI);
            }
            
            time_manager_sync_time();
            
            if (esp32_id_manager_get_id(g_esp32_id_buffer, sizeof(g_esp32_id_buffer)) == ESP_OK) {
                if (mqtt_manager_set_esp32_id(g_esp32_id_buffer) == ESP_OK) {
                    mqtt_manager_connect();
                }
            }
            
            start_connectivity_monitoring_if_ready();
            break;
            
        case WIFI_MANAGER_STATE_DISCONNECTED:
            LOG_I(TAG, "WiFi disconnected");
            set_wifi_connected(false);
            set_mqtt_connected(false);
            
            if (g_watchdog_available) {
                esp_err_t ret = watchdog_manager_set_mode(WATCHDOG_MODE_CONFIG);
                if (ret != ESP_OK) {
                    LOG_D(TAG, "Could not set watchdog to config mode");
                }
            }
            break;
            
        case WIFI_MANAGER_STATE_AP_MODE:
            LOG_I(TAG, "WiFi AP mode active");
            set_wifi_connected(false);
            
            if (g_watchdog_available) {
                esp_err_t ret = watchdog_manager_set_mode(WATCHDOG_MODE_CONFIG);
                if (ret != ESP_OK) {
                    LOG_D(TAG, "Could not set watchdog to config mode");
                }
            }
            break;
            
        default:
            break;
    }
}

static void on_wifi_connect_callback(void *user_data) {
    LOG_I(TAG, "WiFi configured via captive portal");
}

static void system_monitor_task(void *pvParameters) {
    LOG_I(TAG, "System monitor task started");
    
    bool task_registered = false;
    int registration_attempts = 0;
    
    vTaskDelay(pdMS_TO_TICKS(2000));
    
    while (!task_registered && registration_attempts < 5) {
        if (g_watchdog_available) {
            esp_err_t wd_ret = watchdog_manager_register_task(NULL, "sys_monitor");
            if (wd_ret == ESP_OK) {
                LOG_I(TAG, "System monitor task registered in watchdog manager");
                task_registered = true;
                break;
            }
        }
        
        registration_attempts++;
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
    
    uint32_t cycle_count = 0;
    bool portal_config_verified = false;
    int64_t connection_stable_start = 0;
    const int64_t VERIFICATION_DELAY_MS = 30000;
    static uint32_t uptime_hours = 0;
    const uint32_t QUARTERLY_RESTART_HOURS = 2160;
    uint32_t watchdog_feed_counter = 0;
    
    while (1) {
        if (task_registered) {
            esp_err_t feed_ret = watchdog_manager_feed();
            if (feed_ret != ESP_OK) {
                LOG_D(TAG, "Watchdog feed failed: %s", esp_err_to_name(feed_ret));
            }
        }
        
        cycle_count++;
        watchdog_feed_counter++;
        
        deferred_mqtt_message_t msg;
        BaseType_t msg_result = xQueueReceive(g_mqtt_message_queue, &msg, pdMS_TO_TICKS(100));
        
        if (watchdog_feed_counter % 2 == 0 && task_registered) {
            watchdog_manager_feed();
        }
        
        if (msg_result == pdTRUE) {
            process_deferred_mqtt_message(msg.topic, msg.data, msg.data_len);
            LOG_I(TAG, "Processed 1 MQTT message from queue");
        }
        
        if (cycle_count % 1800 == 0 && get_relay_manager_initialized()) {
            if (task_registered) {
                watchdog_manager_feed();
            }
            relay_manager_check_all_states(false);
            if (task_registered) {
                watchdog_manager_feed();
            }
            relay_manager_verify_all_states();
        }
        
        if (cycle_count % 900 == 0 && get_relay_manager_initialized()) {
            LOG_D(TAG, "Performing scheduled relay system check");
            esp_err_t interrupt_check = relay_manager_verify_and_repair_interrupts();
            if (interrupt_check == ESP_FAIL) {
                LOG_W(TAG, "Relay system check reported issues");
            }
        }
        
        if (cycle_count % 30 == 0 && mqtt_manager_is_connected()) {
            int pending_count = connectivity_monitor_get_pending_event_count();
            bool has_pending = connectivity_monitor_has_pending_ram_events();
            
            if (has_pending || pending_count > 0) {
                LOG_I(TAG, "Found %d pending RAM connectivity events - processing", pending_count);
                esp_err_t ram_ret = connectivity_monitor_process_pending_events();
                if (ram_ret == ESP_OK) {
                    LOG_I(TAG, "RAM connectivity events processed successfully");
                } else {
                    LOG_W(TAG, "Failed to process RAM events: %s", esp_err_to_name(ram_ret));
                }
                
                int remaining = connectivity_monitor_get_pending_event_count();
                if (remaining > 0) {
                    LOG_W(TAG, "Still %d events pending after processing", remaining);
                }
            }
        }
        
        if (cycle_count % 1800 == 0) {
            debug_connectivity_events();
        }
        
        if (time_manager_should_send_network_info() || g_need_reregister) {
            LOG_I(TAG, "Sending network info");
            esp_err_t ret = mqtt_manager_send_network_info();
            if (ret == ESP_OK) {
                time_manager_mark_network_info_sent();
                g_need_reregister = false;
            }
        }
        
        if (cycle_count % 1800 == 0) {
            print_memory_info_simple();
        }
        
        if (cycle_count % 1800 == 0) {
            uptime_hours++;
            
            if (uptime_hours >= QUARTERLY_RESTART_HOURS) {
                LOG_I(TAG, "QUARTERLY RESTART: System has run %lu hours", uptime_hours);
                
                if (get_relay_manager_initialized()) {
                    relay_manager_save_current_states();
                    config_manager_force_commit();
                }
                
                vTaskDelay(pdMS_TO_TICKS(3000));
                esp_restart();
            }
        }
        
        bool wifi_connected = wifi_manager_is_connected();
        bool mqtt_connected = mqtt_manager_is_connected();
        bool time_synced = time_manager_is_synchronized();
        
        set_wifi_connected(wifi_connected);
        set_mqtt_connected(mqtt_connected);
        
        if (wifi_connected) {
            if (g_watchdog_available) {
                watchdog_manager_report_activity(WATCHDOG_CHECK_WIFI);
            }
            
            wifi_mode_t mode;
            if (esp_wifi_get_mode(&mode) == ESP_OK && mode == WIFI_MODE_APSTA) {
                if (mqtt_connected && time_synced && !portal_config_verified) {
                    if (connection_stable_start == 0) {
                        connection_stable_start = esp_timer_get_time() / 1000;
                        LOG_I(TAG, "Full connectivity detected, starting verification timer");
                    } else {
                        int64_t elapsed = (esp_timer_get_time() / 1000) - connection_stable_start;
                        if (elapsed >= VERIFICATION_DELAY_MS) {
                            LOG_I(TAG, "Configuration complete - restarting to STA mode");
                            vTaskDelay(pdMS_TO_TICKS(2000));
                            esp_restart();
                        } else {
                            int64_t remaining = (VERIFICATION_DELAY_MS - elapsed) / 1000;
                            if (cycle_count % 12 == 0) {
                                LOG_I(TAG, "Restart in %lld seconds", remaining);
                            }
                        }
                    }
                } else if (!mqtt_connected || !time_synced) {
                    connection_stable_start = 0;
                }
            }
            
            if (!time_manager_is_synchronized()) {
                time_manager_check_sync();
            }
            
            mqtt_manager_loop(100);
            
            if (mqtt_connected) {
                if (g_watchdog_available) {
                    watchdog_manager_report_activity(WATCHDOG_CHECK_MQTT);
                }
                
                if (cycle_count % 1800 == 0) {
                    LOG_I(TAG, "System OK - WiFi+MQTT connected");
                }
            } else {
                if (cycle_count % 36 == 0) {
                    if (cycle_count % 1800 == 0) {
                        LOG_I(TAG, "Retrying MQTT connection");
                    }
                    if (esp32_id_manager_get_id(g_esp32_id_buffer, sizeof(g_esp32_id_buffer)) == ESP_OK) {
                        mqtt_manager_set_esp32_id(g_esp32_id_buffer);
                        mqtt_manager_connect();
                    }
                }
            }
        } else {
            connection_stable_start = 0;
            
            if (cycle_count % 10 == 0) {
                LOG_I(TAG, "WiFi disconnected - running recovery cycle");
                wifi_manager_handle_disconnection("FirePanel", "firepanel", 10);
            }
        }
        
        if (g_watchdog_available && cycle_count % 90 == 0) {
            watchdog_health_status_t health = watchdog_manager_check_system_health();
            if (health > WATCHDOG_HEALTH_WARNING) {
                LOG_W(TAG, "System health degraded: %d", health);
            }
        }
        
        vTaskDelay(pdMS_TO_TICKS(2000));
    }
}

void app_main(void)
{
    ESP_ERROR_CHECK(log_storage_init());
    
    size_t log_size, prev_log_size;
    if (log_storage_get_info(&log_size, &prev_log_size) == ESP_OK) {
        ESP_LOGI(TAG, "Log system ready - current: %zu bytes, previous: %zu bytes", 
                 log_size, prev_log_size);
    }
    
    esp_log_set_vprintf(spiffs_vprintf);

    LOG_I(TAG, "Starting HDD ESP32 Monitor v2.0 (ESP-IDF v5.4.1)");

    LOG_I(TAG, "Phase 1: Basic initialization");

    g_callback_mutex = xSemaphoreCreateMutex();
    g_global_state_mutex = xSemaphoreCreateMutex();
    
    g_mqtt_message_queue = xQueueCreate(8, sizeof(deferred_mqtt_message_t));
    if (g_mqtt_message_queue == NULL) {
        LOG_E(TAG, "Failed to create MQTT message queue");
        esp_restart();
    }
    
    esp_err_t watchdog_ret = watchdog_manager_init();
    if (watchdog_ret != ESP_OK) {
        LOG_E(TAG, "Failed to initialize watchdog manager: %s", esp_err_to_name(watchdog_ret));
        
        LOG_W(TAG, "Retrying watchdog initialization after delay...");
        vTaskDelay(pdMS_TO_TICKS(3000));
        
        watchdog_ret = watchdog_manager_init();
        if (watchdog_ret != ESP_OK) {
            LOG_E(TAG, "Watchdog initialization failed twice: %s", esp_err_to_name(watchdog_ret));
            LOG_E(TAG, "CRITICAL: System cannot function safely without watchdog");
            vTaskDelay(pdMS_TO_TICKS(5000));
            esp_restart();
        }
    }
    
    LOG_I(TAG, "Watchdog manager initialized successfully");
    g_watchdog_available = true;
    
    watchdog_manager_set_event_callback(watchdog_event_callback, NULL);
    
    vTaskDelay(pdMS_TO_TICKS(1000));
    
    bool main_task_registered = false;
    int main_reg_attempts = 0;
    while (!main_task_registered && main_reg_attempts < 5) {
        esp_err_t reg_ret = watchdog_manager_register_task(NULL, "app_main");
        if (reg_ret == ESP_OK) {
            LOG_I(TAG, "Main task registered in watchdog manager");
            main_task_registered = true;
        } else {
            LOG_W(TAG, "Main task registration attempt %d failed: %s", 
                    main_reg_attempts + 1, esp_err_to_name(reg_ret));
            main_reg_attempts++;
            vTaskDelay(pdMS_TO_TICKS(1000));
        }
    }
    
    if (!main_task_registered) {
        LOG_E(TAG, "CRITICAL: Could not register main task in watchdog");
        esp_restart();
    }
    
    ESP_ERROR_CHECK(config_manager_init());
    if (main_task_registered) {
        esp_err_t feed_ret = watchdog_manager_feed();
        if (feed_ret != ESP_OK) {
            LOG_W(TAG, "Initial watchdog feed failed: %s", esp_err_to_name(feed_ret));
        }
    }
    
    ESP_ERROR_CHECK(esp32_id_manager_init());
    if (main_task_registered) {
        watchdog_manager_feed();
    }
    
    ESP_ERROR_CHECK(esp32_id_manager_get_id(g_esp32_id_buffer, sizeof(g_esp32_id_buffer)));
    LOG_I(TAG, "ESP32 ID: %s", g_esp32_id_buffer);
    log_uploader_start(g_esp32_id_buffer);
    
    char mac_address[ESP32_MAC_STR_LENGTH + 1];
    ESP_ERROR_CHECK(esp32_id_manager_get_mac(mac_address, sizeof(mac_address)));
    LOG_I(TAG, "MAC: %s", mac_address);
    
    if (main_task_registered) {
        watchdog_manager_feed();
    }
    
    LOG_I(TAG, "Phase 2: Manager initialization");
    
    ESP_ERROR_CHECK(wifi_manager_init());
    if (main_task_registered) {
        watchdog_manager_feed();
    }
    
    ESP_ERROR_CHECK(time_manager_init());
    if (main_task_registered) {
        watchdog_manager_feed();
    }
    
    ESP_ERROR_CHECK(mqtt_manager_init());
    if (main_task_registered) {
        watchdog_manager_feed();
    }
    
    esp_err_t conn_ret = connectivity_monitor_init();
    if (conn_ret != ESP_OK) {
        LOG_E(TAG, "Failed to initialize connectivity monitor: %s", esp_err_to_name(conn_ret));
    } else {
        LOG_I(TAG, "Connectivity monitor initialized");
        connectivity_monitor_set_event_callback(connectivity_event_callback, NULL);
    }
    
    if (main_task_registered) {
        watchdog_manager_feed();
    }
    
    LOG_I(TAG, "Phase 3: Callback configuration");
    
    ESP_ERROR_CHECK(wifi_manager_set_state_callback(wifi_state_callback, NULL));
    ESP_ERROR_CHECK(mqtt_manager_set_state_callback(mqtt_state_callback, NULL));
    ESP_ERROR_CHECK(mqtt_manager_set_message_callback(mqtt_message_callback, NULL));
    ESP_ERROR_CHECK(mqtt_manager_set_esp32_id(g_esp32_id_buffer));
    
    if (main_task_registered) {
        watchdog_manager_feed();
    }
    
    char saved_client_id[32] = {0};
    char saved_panel_id[32] = {0};

    if (config_manager_get_str("client_id", saved_client_id, sizeof(saved_client_id)) == ESP_OK &&
        config_manager_get_str("panel_id", saved_panel_id, sizeof(saved_panel_id)) == ESP_OK &&
        strlen(saved_client_id) > 0 && strlen(saved_panel_id) > 0) {
        
        LOG_I(TAG, "Found saved configuration: client=%s, panel=%s", saved_client_id, saved_panel_id);
        mqtt_manager_set_panel_config(saved_client_id, saved_panel_id);
    }
    
    LOG_I(TAG, "Phase 4: WiFi connection");

    esp_err_t wifi_ret = wifi_manager_connect_saved();
    if (wifi_ret != ESP_OK) {
        char ap_ssid[33];
        ESP_ERROR_CHECK(wifi_manager_generate_ap_ssid(ap_ssid, sizeof(ap_ssid), "FirePanel"));
        
        LOG_I(TAG, "No saved credentials - starting captive portal: %s", ap_ssid);
        ESP_ERROR_CHECK(wifi_captive_portal_start(ap_ssid, "firepanel"));
        ESP_ERROR_CHECK(wifi_captive_portal_set_on_connect_callback(on_wifi_connect_callback, NULL));
        
        if (g_watchdog_available) {
            watchdog_manager_set_mode(WATCHDOG_MODE_CONFIG);
        }
    } else {
        LOG_I(TAG, "WiFi connection initiated - will connect in background");
    }
    
    LOG_I(TAG, "Phase 5: Creating system monitor task");
    
    TaskHandle_t monitor_task_handle = NULL;
    BaseType_t xReturned = xTaskCreate(
        system_monitor_task,
        "sys_monitor",
        12288,
        NULL,
        5,
        &monitor_task_handle
    );
    
    if (xReturned != pdPASS || monitor_task_handle == NULL) {
        LOG_E(TAG, "Failed to create system monitor task");
        LOG_E(TAG, "CRITICAL: System cannot function without monitor task");
        
        if (g_watchdog_available) {
            watchdog_manager_force_reset("monitor_task_creation_failed");
        } else {
            esp_restart();
        }
    }
    
    vTaskDelay(pdMS_TO_TICKS(2000));
    
    g_system_initialized = true;
    LOG_I(TAG, "System initialization completed successfully");
    
    uint32_t main_cycle = 0;
    uint32_t main_feed_errors = 0;
    
    while (1) {
        if (main_task_registered) {
            esp_err_t feed_ret = watchdog_manager_feed();
            if (feed_ret != ESP_OK) {
                main_feed_errors++;
                LOG_W(TAG, "Main task watchdog feed failed: %s (count: %lu)", 
                        esp_err_to_name(feed_ret), main_feed_errors);
                
                if (main_feed_errors >= 50) {
                    LOG_E(TAG, "Too many main feed failures, attempting re-registration");
                    watchdog_manager_unregister_task(NULL);
                    vTaskDelay(pdMS_TO_TICKS(1000));
                    
                    esp_err_t reg_ret = watchdog_manager_register_task(NULL, "app_main");
                    if (reg_ret == ESP_OK) {
                        LOG_I(TAG, "Main task re-registered successfully");
                        main_feed_errors = 0;
                    } else {
                        LOG_E(TAG, "CRITICAL: Cannot re-register main task");
                        esp_restart();
                    }
                }
            } else {
                if (main_feed_errors > 0) {
                    main_feed_errors = 0;
                }
            }
        }
        
        main_cycle++;
        
        if (main_cycle % 20 == 0) {
            if (g_watchdog_available) {
                uint32_t feed_count, error_count;
                const char *last_reset_reason;
                
                if (watchdog_manager_get_stats(&feed_count, &error_count, &last_reset_reason) == ESP_OK) {
                    if (error_count > 200) {
                        LOG_W(TAG, "High watchdog error count: %lu (feeds: %lu, last reset: %s)", 
                                error_count, feed_count, last_reset_reason ? last_reset_reason : "none");
                    }
                }
                
                watchdog_mode_t current_mode = watchdog_manager_get_mode();
                if (current_mode == WATCHDOG_MODE_CRITICAL) {
                    LOG_W(TAG, "System running in CRITICAL mode");
                }
            }
        }
        
        if (main_cycle % 450 == 0) {
            size_t free_heap = esp_get_free_heap_size();
            size_t min_heap = esp_get_minimum_free_heap_size();
            LOG_I(TAG, "Main task: heap free=%zu min=%zu", free_heap, min_heap);
        }
        
        vTaskDelay(pdMS_TO_TICKS(8000));
    }
    
    if (get_relay_manager_initialized()) {
        LOG_I(TAG, "Cleaning up Relay Manager");
        relay_manager_deinit();
        set_relay_manager_initialized(false);
    }
    
    if (g_connectivity_monitor_started) {
        connectivity_monitor_stop();
        connectivity_monitor_deinit();
    }
}