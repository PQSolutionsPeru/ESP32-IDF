#include <stdio.h>
#include <inttypes.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_system.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "config_manager.h"
#include "esp32_id_manager.h"
#include "wifi_manager.h"
#include "wifi_captive_portal.h"
#include "mqtt_manager.h"
#include "time_manager.h"
#include "watchdog_manager.h"
#include "relay_manager.h"
#include "esp_heap_caps.h"
#include "esp_task_wdt.h"
#include "config_processor.h"
#include "esp_timer.h"
#include "cJSON.h"

static const char *TAG = "HDDESP32";

static bool g_system_initialized = false;
static bool g_wifi_connected = false;
static bool g_mqtt_connected = false;
static bool g_watchdog_available = false;
static bool g_relay_manager_initialized = false;
static bool g_need_reregister = false;

static char g_relay_json_buffer[256];
static char g_mqtt_temp_topic[128];
static char g_mqtt_temp_data[400];
static char g_esp32_id_buffer[ESP32_ID_LENGTH + 1];

static void print_memory_info_simple(void) {
    size_t free_heap = esp_get_free_heap_size();
    size_t min_heap = esp_get_minimum_free_heap_size();
    
    ESP_LOGI(TAG, "Memory: free=%zu min=%zu", free_heap, min_heap);
    
    if (g_watchdog_available) {
        watchdog_manager_report_activity(WATCHDOG_CHECK_MEMORY);
    }
}

static void watchdog_event_callback(watchdog_health_status_t status, watchdog_check_type_t check_type, void *user_data) {
    const char* status_str = "UNKNOWN";
    const char* check_str = "UNKNOWN";
    
    switch (status) {
        case WATCHDOG_HEALTH_GOOD: status_str = "GOOD"; break;
        case WATCHDOG_HEALTH_WARNING: status_str = "WARNING"; break;
        case WATCHDOG_HEALTH_CRITICAL: status_str = "CRITICAL"; break;
        case WATCHDOG_HEALTH_ERROR: status_str = "ERROR"; break;
    }
    
    switch (check_type) {
        case WATCHDOG_CHECK_MEMORY: check_str = "MEMORY"; break;
        case WATCHDOG_CHECK_WIFI: check_str = "WIFI"; break;
        case WATCHDOG_CHECK_MQTT: check_str = "MQTT"; break;
        case WATCHDOG_CHECK_TASKS: check_str = "TASKS"; break;
    }
    
    ESP_LOGW(TAG, "Watchdog event: %s - %s", check_str, status_str);
    
    if (status == WATCHDOG_HEALTH_CRITICAL) {
        switch (check_type) {
            case WATCHDOG_CHECK_MEMORY:
                ESP_LOGE(TAG, "Critical memory situation detected");
                break;
                
            case WATCHDOG_CHECK_WIFI:
                ESP_LOGE(TAG, "Critical WiFi failure detected");
                if (!wifi_manager_is_connected()) {
                    char ap_ssid[33];
                    esp_err_t ret = wifi_manager_generate_ap_ssid(ap_ssid, sizeof(ap_ssid), "FirePanel");
                    if (ret == ESP_OK) {
                        ESP_LOGI(TAG, "Starting emergency AP mode: %s", ap_ssid);
                        wifi_manager_start_ap_mode(ap_ssid, "firepanel");
                    }
                }
                break;
                
            case WATCHDOG_CHECK_MQTT:
                ESP_LOGE(TAG, "Critical MQTT failure detected");
                if (esp32_id_manager_get_id(g_esp32_id_buffer, sizeof(g_esp32_id_buffer)) == ESP_OK) {
                    mqtt_manager_set_esp32_id(g_esp32_id_buffer);
                    mqtt_manager_connect();
                }
                break;
                
            case WATCHDOG_CHECK_TASKS:
                ESP_LOGE(TAG, "Critical task failure detected");
                break;
        }
    } else if (status == WATCHDOG_HEALTH_WARNING) {
        ESP_LOGW(TAG, "System degradation detected in %s subsystem", check_str);
    }
}

static void relay_state_change_callback(const relay_event_t *event, void *user_data) {
    if (!g_mqtt_connected || !event) {
        return;
    }
    
    if (g_watchdog_available) {
        watchdog_manager_report_activity(WATCHDOG_CHECK_TASKS);
    }
    
    if (esp32_id_manager_get_id(g_esp32_id_buffer, sizeof(g_esp32_id_buffer)) != ESP_OK) {
        ESP_LOGE(TAG, "Could not get ESP32 ID for relay event");
        return;
    }
    
    memset(g_relay_json_buffer, 0, sizeof(g_relay_json_buffer));
    
    int64_t timestamp_ms = event->timestamp / 1000;
    
    int len = snprintf(g_relay_json_buffer, sizeof(g_relay_json_buffer),
                      "{"
                      "\"relay\":\"%.16s\","
                      "\"status\":\"%s\","
                      "\"timestamp\":%lld,"
                      "\"contact_type\":\"%s\","
                      "\"old_status\":\"%s\""
                      "}",
                      event->relay_id,
                      (event->new_state == RELAY_STATE_OK) ? "OK" : "DISC",
                      (long long)timestamp_ms,
                      (event->contact_type == RELAY_CONTACT_NC) ? "NC" : "NO",
                      (event->old_state == RELAY_STATE_OK) ? "OK" : "DISC");
    
    if (len > 0 && len < sizeof(g_relay_json_buffer)) {
        char relay_topic[MQTT_TOPIC_MAX_LENGTH];
        esp_err_t topic_ret = mqtt_manager_get_panel_topic(relay_topic, sizeof(relay_topic), "relays");
        
        if (topic_ret == ESP_OK) {
            esp_err_t ret = mqtt_manager_publish_json(relay_topic, g_relay_json_buffer, 1, false);
            if (ret == ESP_OK) {
                ESP_LOGI(TAG, "Relay %s state change published: %s -> %s", 
                        event->relay_id,
                        (event->old_state == RELAY_STATE_OK) ? "OK" : "DISC",
                        (event->new_state == RELAY_STATE_OK) ? "OK" : "DISC");
            }
        } else {
            snprintf(relay_topic, sizeof(relay_topic), "esp32/%.8s/relays", g_esp32_id_buffer);
            mqtt_manager_publish_json(relay_topic, g_relay_json_buffer, 1, false);
        }
    }
}

static esp_err_t relay_mqtt_command_callback(const char *topic, const char *command_json, void *user_data) {
    ESP_LOGI(TAG, "Processing relay MQTT command from topic: %s", topic);
    
    if (g_watchdog_available) {
        watchdog_manager_report_activity(WATCHDOG_CHECK_MQTT);
    }
    
    return ESP_OK;
}

static void mqtt_state_callback(mqtt_manager_state_t state, void *user_data) {
    switch (state) {
        case MQTT_MANAGER_STATE_CONNECTED:
            ESP_LOGI(TAG, "MQTT connected");
            g_mqtt_connected = true;
            
            if (!g_relay_manager_initialized) {
                ESP_LOGI(TAG, "Initializing Relay Manager after MQTT connection");
                esp_err_t ret = relay_manager_init();
                if (ret == ESP_OK) {
                    relay_manager_set_state_callback(relay_state_change_callback, NULL);
                    relay_manager_set_mqtt_callback(relay_mqtt_command_callback, NULL);
                    relay_manager_check_all_states(true);
                    g_relay_manager_initialized = true;
                    ESP_LOGI(TAG, "Relay Manager initialized successfully");
                } else {
                    ESP_LOGE(TAG, "Failed to initialize Relay Manager: %s", esp_err_to_name(ret));
                }
            }
            
            if (g_watchdog_available) {
                watchdog_manager_report_activity(WATCHDOG_CHECK_MQTT);
                
                if (g_wifi_connected && g_mqtt_connected) {
                    watchdog_manager_set_mode(WATCHDOG_MODE_RUNNING);
                }
            }
            break;
            
        case MQTT_MANAGER_STATE_DISCONNECTED:
            ESP_LOGI(TAG, "MQTT disconnected");
            g_mqtt_connected = false;
            
            if (g_watchdog_available) {
                esp_err_t ret = watchdog_manager_set_mode(WATCHDOG_MODE_CONFIG);
                if (ret != ESP_OK) {
                    ESP_LOGD(TAG, "Could not set watchdog to config mode");
                }
            }
            break;
            
        case MQTT_MANAGER_STATE_ERROR:
            ESP_LOGW(TAG, "MQTT error");
            g_mqtt_connected = false;
            break;
            
        default:
            break;
    }
}

static void mqtt_message_callback(const char *topic, const char *data, int data_len, void *user_data) {
    ESP_LOGI(TAG, "MQTT message: %s (%d bytes)", topic ? topic : "NULL", data_len);
    
    if (!topic || !data || data_len <= 0) {
        ESP_LOGE(TAG, "Invalid MQTT message");
        return;
    }
    
    if (g_watchdog_available) {
        watchdog_manager_report_activity(WATCHDOG_CHECK_MQTT);
    }
    
    if (data_len > sizeof(g_mqtt_temp_data) - 1) {
        ESP_LOGW(TAG, "Message too large, truncating: %d -> %zu bytes", data_len, sizeof(g_mqtt_temp_data) - 1);
        data_len = sizeof(g_mqtt_temp_data) - 1;
    }
    
    memcpy(g_mqtt_temp_data, data, data_len);
    g_mqtt_temp_data[data_len] = '\0';
    
    if (esp32_id_manager_get_id(g_esp32_id_buffer, sizeof(g_esp32_id_buffer)) != ESP_OK) {
        ESP_LOGE(TAG, "Could not get ESP32 ID");
        return;
    }
    
    snprintf(g_mqtt_temp_topic, sizeof(g_mqtt_temp_topic), "esp32/notify/%s/deleted", g_esp32_id_buffer);
    
    if (strcmp(topic, g_mqtt_temp_topic) == 0) {
        ESP_LOGW(TAG, "Deletion notification received");
        config_manager_erase_key("client_id");
        config_manager_erase_key("panel_id");
        config_manager_erase_key("panel_name");
        config_manager_erase_key("location");
        mqtt_manager_clear_panel_config();
        
        if (g_relay_manager_initialized) {
            relay_manager_deinit();
            g_relay_manager_initialized = false;
        }
        
        time_manager_reset_network_info_sent();
        g_need_reregister = true;
        vTaskDelay(pdMS_TO_TICKS(1000));
        mqtt_manager_send_network_info();
        return;
    }
    
    snprintf(g_mqtt_temp_topic, sizeof(g_mqtt_temp_topic), "esp32/config/%s", g_esp32_id_buffer);
    
    if (strcmp(topic, g_mqtt_temp_topic) == 0) {
        ESP_LOGI(TAG, "Configuration message received");
        
        esp_err_t ret = process_esp32_configuration(g_mqtt_temp_data);
        
        if (ret == ESP_OK) {
            ESP_LOGI(TAG, "Configuration accepted");
            
            if (!g_relay_manager_initialized) {
                vTaskDelay(pdMS_TO_TICKS(500));
                ESP_LOGI(TAG, "Initializing Relay Manager");
                ret = relay_manager_init();
                if (ret == ESP_OK) {
                    relay_manager_set_state_callback(relay_state_change_callback, NULL);
                    relay_manager_set_mqtt_callback(relay_mqtt_command_callback, NULL);
                    g_relay_manager_initialized = true;
                }
            }
        }
        return;
    }
    
    if (strstr(topic, "/relay_config") && g_relay_manager_initialized) {
        if (data_len > 300) {
            ESP_LOGW(TAG, "Relay config message too large, ignoring");
            return;
        }
        
        cJSON *json = cJSON_ParseWithLength(g_mqtt_temp_data, data_len);
        if (json) {
            cJSON *command = cJSON_GetObjectItem(json, "command");
            cJSON *relay_id = cJSON_GetObjectItem(json, "relay_id");
            cJSON *is_active = cJSON_GetObjectItem(json, "is_active");
            
            if (command && relay_id && 
                cJSON_IsString(command) && cJSON_IsString(relay_id) &&
                strcmp(cJSON_GetStringValue(command), "update_config") == 0) {
                
                const char *relay_name = cJSON_GetStringValue(relay_id);
                
                if (relay_name && is_active) {
                    relay_manager_set_active(relay_name, cJSON_IsTrue(is_active));
                    ESP_LOGI(TAG, "Relay %s %s", relay_name,
                            cJSON_IsTrue(is_active) ? "ACTIVATED" : "DEACTIVATED");
                    
                    cJSON *contact_type = cJSON_GetObjectItem(json, "contact_type");
                    if (contact_type && cJSON_IsString(contact_type)) {
                        const char *type_str = cJSON_GetStringValue(contact_type);
                        if (type_str) {
                            relay_contact_type_t type = strcmp(type_str, "NC") == 0 ? 
                                                    RELAY_CONTACT_NC : RELAY_CONTACT_NO;
                            relay_manager_set_contact_type(relay_name, type);
                            ESP_LOGI(TAG, "Relay %s contact type set to %s", relay_name, type_str);
                        }
                    }
                    
                    cJSON *custom_name = cJSON_GetObjectItem(json, "custom_name");
                    if (custom_name && cJSON_IsString(custom_name)) {
                        const char *name_str = cJSON_GetStringValue(custom_name);
                        if (name_str && strlen(name_str) > 0 && strlen(name_str) < 32) {
                            relay_manager_set_name(relay_name, name_str);
                            ESP_LOGI(TAG, "Relay %s custom name set to '%.30s'", relay_name, name_str);
                        }
                    }
                }
            }
            cJSON_Delete(json);
        }
    }
}

static void wifi_state_callback(wifi_manager_state_t state, void *user_data) {
    switch (state) {
        case WIFI_MANAGER_STATE_CONNECTED:
            ESP_LOGI(TAG, "WiFi connected");
            g_wifi_connected = true;
            
            if (g_watchdog_available) {
                watchdog_manager_report_activity(WATCHDOG_CHECK_WIFI);
            }
            
            time_manager_sync_time();
            
            if (esp32_id_manager_get_id(g_esp32_id_buffer, sizeof(g_esp32_id_buffer)) == ESP_OK) {
                if (mqtt_manager_set_esp32_id(g_esp32_id_buffer) == ESP_OK) {
                    mqtt_manager_connect();
                }
            }
            break;
            
        case WIFI_MANAGER_STATE_DISCONNECTED:
            ESP_LOGI(TAG, "WiFi disconnected");
            g_wifi_connected = false;
            g_mqtt_connected = false;
            
            if (g_watchdog_available) {
                esp_err_t ret = watchdog_manager_set_mode(WATCHDOG_MODE_CONFIG);
                if (ret != ESP_OK) {
                    ESP_LOGD(TAG, "Could not set watchdog to config mode");
                }
            }
            break;
            
        case WIFI_MANAGER_STATE_AP_MODE:
            ESP_LOGI(TAG, "WiFi AP mode active");
            g_wifi_connected = false;
            
            if (g_watchdog_available) {
                esp_err_t ret = watchdog_manager_set_mode(WATCHDOG_MODE_CONFIG);
                if (ret != ESP_OK) {
                    ESP_LOGD(TAG, "Could not set watchdog to config mode");
                }
            }
            break;
            
        default:
            break;
    }
}

static void on_wifi_connect_callback(void *user_data) {
    ESP_LOGI(TAG, "WiFi configured via captive portal");
    
    if (wifi_captive_portal_is_active()) {
        wifi_captive_portal_stop();
        esp_wifi_set_mode(WIFI_MODE_STA);
    }
}

static void system_monitor_task(void *pvParameters) {
    ESP_LOGI(TAG, "System monitor task started");
    
    bool task_registered = false;
    int registration_attempts = 0;
    
    vTaskDelay(pdMS_TO_TICKS(2000));
    
    while (!task_registered && registration_attempts < 5) {
        if (g_watchdog_available) {
            esp_err_t wd_ret = watchdog_manager_register_task(NULL, "sys_monitor");
            if (wd_ret == ESP_OK) {
                ESP_LOGI(TAG, "System monitor task registered in watchdog manager");
                task_registered = true;
                break;
            }
        }
        
        registration_attempts++;
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
    
    uint32_t cycle_count = 0;
    
    while (1) {
        if (task_registered) {
            esp_err_t feed_ret = watchdog_manager_feed();
            if (feed_ret != ESP_OK) {
                ESP_LOGD(TAG, "Watchdog feed failed: %s", esp_err_to_name(feed_ret));
            }
        }
        
        cycle_count++;
        
        if (cycle_count % 60 == 0 && g_relay_manager_initialized) {
            relay_manager_check_all_states(false);
        }
        
        if (time_manager_should_send_network_info() || g_need_reregister) {
            ESP_LOGI(TAG, "Sending network info");
            esp_err_t ret = mqtt_manager_send_network_info();
            if (ret == ESP_OK) {
                time_manager_mark_network_info_sent();
                g_need_reregister = false;
            }
        }
        
        if (cycle_count % 120 == 0) {
            print_memory_info_simple();
        }
        
        bool wifi_connected = wifi_manager_is_connected();
        bool mqtt_connected = mqtt_manager_is_connected();
        
        g_wifi_connected = wifi_connected;
        g_mqtt_connected = mqtt_connected;
        
        if (wifi_connected) {
            if (g_watchdog_available) {
                watchdog_manager_report_activity(WATCHDOG_CHECK_WIFI);
            }
            
            wifi_mode_t mode;
            if (esp_wifi_get_mode(&mode) == ESP_OK && mode == WIFI_MODE_APSTA) {
                esp_wifi_set_mode(WIFI_MODE_STA);
            }
            
            if (!time_manager_is_synchronized()) {
                time_manager_check_sync();
            }
            
            mqtt_manager_loop(0);
            
            if (mqtt_connected) {
                if (g_watchdog_available) {
                    watchdog_manager_report_activity(WATCHDOG_CHECK_MQTT);
                }
                
                if (cycle_count % 24 == 0) {
                    ESP_LOGI(TAG, "System OK - WiFi+MQTT connected");
                }
            } else {
                if (cycle_count % 24 == 0) {
                    ESP_LOGI(TAG, "Retrying MQTT connection");
                    if (esp32_id_manager_get_id(g_esp32_id_buffer, sizeof(g_esp32_id_buffer)) == ESP_OK) {
                        mqtt_manager_set_esp32_id(g_esp32_id_buffer);
                        mqtt_manager_connect();
                    }
                }
            }
        } else {
            if (cycle_count % 12 == 0) {
                ESP_LOGI(TAG, "WiFi disconnected - attempting recovery");
                wifi_manager_handle_disconnection("FirePanel", "firepanel", 3);
            }
        }
        
        if (g_watchdog_available && cycle_count % 60 == 0) {
            watchdog_health_status_t health = watchdog_manager_check_system_health();
            if (health > WATCHDOG_HEALTH_WARNING) {
                ESP_LOGW(TAG, "System health degraded: %d", health);
            }
        }
        
        vTaskDelay(pdMS_TO_TICKS(5000));
    }
}

void app_main(void)
{
    ESP_LOGI(TAG, "Starting HDD ESP32 Monitor v2.0 (ESP-IDF v5.4.1)");

    ESP_LOGI(TAG, "Phase 1: Basic initialization");
    
    esp_err_t watchdog_ret = watchdog_manager_init();
    if (watchdog_ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to initialize watchdog manager: %s", esp_err_to_name(watchdog_ret));
        
        ESP_LOGW(TAG, "Retrying watchdog initialization after delay...");
        vTaskDelay(pdMS_TO_TICKS(3000));
        
        watchdog_ret = watchdog_manager_init();
        if (watchdog_ret != ESP_OK) {
            ESP_LOGE(TAG, "Watchdog initialization failed twice: %s", esp_err_to_name(watchdog_ret));
            ESP_LOGE(TAG, "CRITICAL: System cannot function safely without watchdog");
            vTaskDelay(pdMS_TO_TICKS(5000));
            esp_restart();
        }
    }
    
    ESP_LOGI(TAG, "Watchdog manager initialized successfully");
    g_watchdog_available = true;
    
    watchdog_manager_set_event_callback(watchdog_event_callback, NULL);
    
    vTaskDelay(pdMS_TO_TICKS(1000));
    
    bool main_task_registered = false;
    int main_reg_attempts = 0;
    while (!main_task_registered && main_reg_attempts < 5) {
        esp_err_t reg_ret = watchdog_manager_register_task(NULL, "app_main");
        if (reg_ret == ESP_OK) {
            ESP_LOGI(TAG, "Main task registered in watchdog manager");
            main_task_registered = true;
        } else {
            ESP_LOGW(TAG, "Main task registration attempt %d failed: %s", 
                    main_reg_attempts + 1, esp_err_to_name(reg_ret));
            main_reg_attempts++;
            vTaskDelay(pdMS_TO_TICKS(1000));
        }
    }
    
    if (!main_task_registered) {
        ESP_LOGE(TAG, "CRITICAL: Could not register main task in watchdog");
        esp_restart();
    }
    
    ESP_ERROR_CHECK(config_manager_init());
    if (main_task_registered) {
        esp_err_t feed_ret = watchdog_manager_feed();
        if (feed_ret != ESP_OK) {
            ESP_LOGW(TAG, "Initial watchdog feed failed: %s", esp_err_to_name(feed_ret));
        }
    }
    
    ESP_ERROR_CHECK(esp32_id_manager_init());
    if (main_task_registered) {
        watchdog_manager_feed();
    }
    
    ESP_ERROR_CHECK(esp32_id_manager_get_id(g_esp32_id_buffer, sizeof(g_esp32_id_buffer)));
    ESP_LOGI(TAG, "ESP32 ID: %s", g_esp32_id_buffer);
    
    char mac_address[ESP32_MAC_STR_LENGTH + 1];
    ESP_ERROR_CHECK(esp32_id_manager_get_mac(mac_address, sizeof(mac_address)));
    ESP_LOGI(TAG, "MAC: %s", mac_address);
    
    if (main_task_registered) {
        watchdog_manager_feed();
    }
    
    ESP_LOGI(TAG, "Phase 2: Manager initialization");
    
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
    
    ESP_LOGI(TAG, "Phase 3: Callback configuration");
    
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
        
        ESP_LOGI(TAG, "Found saved configuration: client=%s, panel=%s", saved_client_id, saved_panel_id);
        mqtt_manager_set_panel_config(saved_client_id, saved_panel_id);
    }
    
    ESP_LOGI(TAG, "Phase 4: WiFi connection");
    
    esp_err_t wifi_ret = wifi_manager_connect_saved();
    if (wifi_ret != ESP_OK) {
        char ap_ssid[33];
        ESP_ERROR_CHECK(wifi_manager_generate_ap_ssid(ap_ssid, sizeof(ap_ssid), "FirePanel"));
        
        ESP_LOGI(TAG, "Starting captive portal: %s", ap_ssid);
        ESP_ERROR_CHECK(wifi_captive_portal_start(ap_ssid, "firepanel"));
        ESP_ERROR_CHECK(wifi_captive_portal_set_on_connect_callback(on_wifi_connect_callback, NULL));
        
        if (g_watchdog_available) {
            watchdog_manager_set_mode(WATCHDOG_MODE_CONFIG);
        }
    } else {
        if (wifi_manager_is_connected()) {
            g_wifi_connected = true;
            time_manager_sync_time();
            mqtt_manager_connect();
        }
    }
    
    if (main_task_registered) {
        watchdog_manager_feed();
    }
    
    ESP_LOGI(TAG, "Phase 5: Creating system monitor task");
    
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
        ESP_LOGE(TAG, "Failed to create system monitor task");
        ESP_LOGE(TAG, "CRITICAL: System cannot function without monitor task");
        
        if (g_watchdog_available) {
            watchdog_manager_force_reset("monitor_task_creation_failed");
        } else {
            esp_restart();
        }
    }
    
    vTaskDelay(pdMS_TO_TICKS(2000));
    
    g_system_initialized = true;
    ESP_LOGI(TAG, "System initialization completed successfully");
    
    uint32_t main_cycle = 0;
    uint32_t main_feed_errors = 0;
    
    while (1) {
        if (main_task_registered) {
            esp_err_t feed_ret = watchdog_manager_feed();
            if (feed_ret != ESP_OK) {
                main_feed_errors++;
                ESP_LOGW(TAG, "Main task watchdog feed failed: %s (count: %lu)", 
                        esp_err_to_name(feed_ret), main_feed_errors);
                
                if (main_feed_errors >= 30) {
                    ESP_LOGE(TAG, "Too many main feed failures, attempting re-registration");
                    watchdog_manager_unregister_task(NULL);
                    vTaskDelay(pdMS_TO_TICKS(1000));
                    
                    esp_err_t reg_ret = watchdog_manager_register_task(NULL, "app_main");
                    if (reg_ret == ESP_OK) {
                        ESP_LOGI(TAG, "Main task re-registered successfully");
                        main_feed_errors = 0;
                    } else {
                        ESP_LOGE(TAG, "CRITICAL: Cannot re-register main task");
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
        
        if (main_cycle % 12 == 0) {
            if (g_watchdog_available) {
                uint32_t feed_count, error_count;
                const char *last_reset_reason;
                
                if (watchdog_manager_get_stats(&feed_count, &error_count, &last_reset_reason) == ESP_OK) {
                    if (error_count > 100) {
                        ESP_LOGW(TAG, "High watchdog error count: %lu (feeds: %lu, last reset: %s)", 
                                error_count, feed_count, last_reset_reason ? last_reset_reason : "none");
                    }
                }
                
                watchdog_mode_t current_mode = watchdog_manager_get_mode();
                if (current_mode == WATCHDOG_MODE_CRITICAL) {
                    ESP_LOGW(TAG, "System running in CRITICAL mode");
                }
            }
        }
        
        if (main_cycle % 60 == 0) {
            size_t free_heap = esp_get_free_heap_size();
            size_t min_heap = esp_get_minimum_free_heap_size();
            ESP_LOGI(TAG, "Main task: heap free=%zu min=%zu", free_heap, min_heap);
        }
        
        vTaskDelay(pdMS_TO_TICKS(5000));
    }
    
    if (g_relay_manager_initialized) {
        ESP_LOGI(TAG, "Cleaning up Relay Manager");
        relay_manager_deinit();
        g_relay_manager_initialized = false;
    }
}