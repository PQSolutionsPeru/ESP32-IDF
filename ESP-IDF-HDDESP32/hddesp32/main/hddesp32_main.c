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

static const char *TAG = "HDDESP32";

static bool g_system_initialized = false;
static bool g_wifi_connected = false;
static bool g_mqtt_connected = false;
static bool g_watchdog_available = false;
static bool g_relay_manager_initialized = false;

static void force_heap_cleanup(void) {
    for (int i = 0; i < 3; i++) {
        heap_caps_check_integrity_all(true);
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}

static void print_memory_info(void) {
    size_t free_heap = esp_get_free_heap_size();
    size_t min_heap = esp_get_minimum_free_heap_size();
    size_t largest_block = heap_caps_get_largest_free_block(MALLOC_CAP_8BIT);
    
    ESP_LOGI(TAG, "=== Memory Status ===");
    ESP_LOGI(TAG, "Free heap: %zu bytes", free_heap);
    ESP_LOGI(TAG, "Minimum free heap: %zu bytes", min_heap);
    ESP_LOGI(TAG, "Largest free block: %zu bytes", largest_block);
    
    if (g_watchdog_available) {
        esp_err_t ret = watchdog_manager_report_activity(WATCHDOG_CHECK_MEMORY);
        if (ret != ESP_OK) {
            ESP_LOGD(TAG, "Could not report memory activity to watchdog");
        }
    }
    
    if (free_heap < 50000) {
        ESP_LOGW(TAG, "Low memory warning: %zu bytes", free_heap);
        if (free_heap < 30000 && g_watchdog_available) {
            esp_err_t mode_ret = watchdog_manager_set_mode(WATCHDOG_MODE_CRITICAL);
            if (mode_ret != ESP_OK) {
                ESP_LOGD(TAG, "Could not set watchdog to critical mode");
            }
        }
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
                mqtt_manager_emergency_memory_cleanup();
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
                char esp32_id[ESP32_ID_LENGTH + 1];
                if (esp32_id_manager_get_id(esp32_id, sizeof(esp32_id)) == ESP_OK) {
                    mqtt_manager_set_esp32_id(esp32_id);
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
    if (!g_mqtt_connected) {
        ESP_LOGD(TAG, "MQTT not connected, skipping relay event");
        return;
    }
    
    if (g_watchdog_available) {
        watchdog_manager_report_activity(WATCHDOG_CHECK_TASKS);
    }
    
    size_t free_heap = esp_get_free_heap_size();
    if (free_heap < 20000) {
        ESP_LOGW(TAG, "Insufficient memory for relay event: %zu bytes", free_heap);
        return;
    }
    
    char esp32_id[ESP32_ID_LENGTH + 1];
    if (esp32_id_manager_get_id(esp32_id, sizeof(esp32_id)) != ESP_OK) {
        ESP_LOGE(TAG, "Could not get ESP32 ID for relay event");
        return;
    }
    
    char *json_buffer = heap_caps_malloc(512, MALLOC_CAP_8BIT);
    if (!json_buffer) {
        ESP_LOGE(TAG, "Failed to allocate relay event buffer");
        return;
    }
    
    memset(json_buffer, 0, 512);
    
    int64_t timestamp_ms = event->timestamp / 1000;
    
    int len = snprintf(json_buffer, 512,
                      "{"
                      "\"relay\":\"%s\","
                      "\"state\":\"%s\","
                      "\"timestamp\":%lld"
                      "}",
                      event->relay_id,
                      (event->new_state == RELAY_STATE_OK) ? "OK" : "DISC",
                      (long long)timestamp_ms);
    
    if (len > 0 && len < 512) {
        char relay_topic[MQTT_TOPIC_MAX_LENGTH];
        esp_err_t topic_ret = mqtt_manager_get_panel_topic(relay_topic, sizeof(relay_topic), "relays");
        
        if (topic_ret == ESP_OK) {
            esp_err_t ret = mqtt_manager_publish_json(relay_topic, json_buffer, 1, false);
            if (ret == ESP_OK) {
                ESP_LOGI(TAG, "Relay %s state change published to %s: %s -> %s", 
                        event->relay_id,
                        relay_topic,
                        (event->old_state == RELAY_STATE_OK) ? "OK" : "DISC",
                        (event->new_state == RELAY_STATE_OK) ? "OK" : "DISC");
            } else {
                ESP_LOGW(TAG, "Failed to publish relay event: %s", esp_err_to_name(ret));
            }
        } else {
            ESP_LOGW(TAG, "No panel configuration, using generic topic");
            char generic_topic[128];
            snprintf(generic_topic, sizeof(generic_topic), "esp32/%s/relays", esp32_id);
            mqtt_manager_publish_json(generic_topic, json_buffer, 1, false);
        }
    } else {
        ESP_LOGE(TAG, "Relay event JSON too large or formatting error");
    }
    
    memset(json_buffer, 0, 512);
    free(json_buffer);
    json_buffer = NULL;
    
    heap_caps_check_integrity_all(true);
}

static esp_err_t relay_mqtt_command_callback(const char *topic, const char *command_json, void *user_data) {
    ESP_LOGI(TAG, "Processing relay MQTT command from topic: %s", topic);
    ESP_LOGI(TAG, "Command JSON: %s", command_json);
    
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
    ESP_LOGI(TAG, "MQTT message: %s (%d bytes)", topic, data_len);
    
    if (g_watchdog_available) {
        esp_err_t ret = watchdog_manager_report_activity(WATCHDOG_CHECK_MQTT);
        if (ret != ESP_OK) {
            ESP_LOGD(TAG, "Could not report MQTT activity to watchdog");
        }
    }
    
    size_t free_heap = esp_get_free_heap_size();
    if (free_heap < 20000) {
        ESP_LOGW(TAG, "Insufficient memory for MQTT message processing: %zu bytes", free_heap);
        return;
    }
    
    if (strstr(topic, "esp32/config/") && !strstr(topic, "/relay_config") && !strstr(topic, "/response")) {
        ESP_LOGI(TAG, "Configuration message received");
        
        char *json_buffer = heap_caps_malloc(data_len + 1, MALLOC_CAP_8BIT);
        if (!json_buffer) {
            ESP_LOGE(TAG, "Failed to allocate buffer for configuration");
            return;
        }
        
        memcpy(json_buffer, data, data_len);
        json_buffer[data_len] = '\0';
        
        esp_err_t ret = process_esp32_configuration(json_buffer);
        if (ret == ESP_OK) {
            ESP_LOGI(TAG, "Configuration processed successfully");
            
            if (g_relay_manager_initialized) {
                relay_manager_check_all_states(true);
            }
        } else {
            ESP_LOGE(TAG, "Failed to process configuration: %s", esp_err_to_name(ret));
        }
        
        free(json_buffer);
    }
    else if (strstr(topic, "esp32/config/") && strstr(topic, "/reset")) {
        ESP_LOGW(TAG, "Reset command received");
        
        config_manager_erase_key("client_id");
        config_manager_erase_key("panel_id");
        config_manager_erase_key("panel_name");
        config_manager_erase_key("location");
        
        mqtt_manager_send_config_response(true, "Configuration reset, restarting...");
        
        vTaskDelay(pdMS_TO_TICKS(1000));
        
        esp_restart();
    }
    else if (strstr(topic, "/relay_config") && g_relay_manager_initialized) {
        ESP_LOGI(TAG, "Relay config command received");
        esp_err_t ret = relay_manager_process_mqtt_command(data);
        if (ret != ESP_OK) {
            ESP_LOGW(TAG, "Failed to process relay command: %s", esp_err_to_name(ret));
        }
    }
}

static void wifi_state_callback(wifi_manager_state_t state, void *user_data) {
    char esp32_id[ESP32_ID_LENGTH + 1];
    
    switch (state) {
        case WIFI_MANAGER_STATE_CONNECTED:
            ESP_LOGI(TAG, "WiFi connected");
            g_wifi_connected = true;
            
            if (g_watchdog_available) {
                watchdog_manager_report_activity(WATCHDOG_CHECK_WIFI);
            }
            
            time_manager_sync_time();
            
            if (esp32_id_manager_get_id(esp32_id, sizeof(esp32_id)) == ESP_OK) {
                if (mqtt_manager_set_esp32_id(esp32_id) == ESP_OK) {
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
    
    bool task_watchdog_available = false;
    
    if (g_watchdog_available) {
        esp_err_t wd_ret = watchdog_manager_register_task(NULL, "sys_monitor");
        if (wd_ret == ESP_OK) {
            ESP_LOGI(TAG, "System monitor task registered in watchdog manager");
            task_watchdog_available = true;
        } else {
            ESP_LOGW(TAG, "Failed to register in watchdog manager: %s", esp_err_to_name(wd_ret));
        }
    }
    
    if (!task_watchdog_available) {
        ESP_LOGW(TAG, "Watchdog manager not available, using default ESP-IDF TWDT");
        esp_err_t twdt_ret = esp_task_wdt_add(NULL);
        if (twdt_ret == ESP_OK) {
            ESP_LOGI(TAG, "System monitor task registered in default TWDT");
        } else {
            ESP_LOGW(TAG, "Failed to register in default TWDT: %s", esp_err_to_name(twdt_ret));
        }
    }
    
    uint32_t cycle_count = 0;
    
    while (1) {
        if (task_watchdog_available) {
            watchdog_manager_feed();
        } else {
            esp_task_wdt_reset();
        }
        
        cycle_count++;
        
        if (cycle_count % 60 == 0 && g_relay_manager_initialized) {
            ESP_LOGI(TAG, "Performing periodic relay check");
            relay_manager_check_all_states(false);
        }
        
        if (time_manager_should_send_network_info()) {
            ESP_LOGI(TAG, "Sending network info after NTP synchronization");
            esp_err_t ret = mqtt_manager_send_network_info();
            if (ret == ESP_OK) {
                ESP_LOGI(TAG, "Network info sent successfully");
                time_manager_mark_network_info_sent();
            } else {
                ESP_LOGW(TAG, "Failed to send network info: %s", esp_err_to_name(ret));
            }
        }
        
        size_t free_heap = esp_get_free_heap_size();
        if (free_heap < 40000) {
            ESP_LOGW(TAG, "Low memory detected: %zu bytes - Performing emergency cleanup", free_heap);
            
            if (mqtt_manager_is_connected()) {
                ESP_LOGI(TAG, "Triggering MQTT memory cleanup");
                mqtt_manager_emergency_memory_cleanup();
            }
            
            force_heap_cleanup();
            
            size_t free_after = esp_get_free_heap_size();
            ESP_LOGI(TAG, "Memory after cleanup: %zu bytes (recovered: %d bytes)", 
                    free_after, (int)(free_after - free_heap));
        }
        
        if (cycle_count % 360 == 0) {
            force_heap_cleanup();
        }
        
        if (cycle_count % 120 == 0) {
            print_memory_info();
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
                esp_err_t time_ret = time_manager_check_sync();
                if (time_ret != ESP_OK && time_ret != ESP_ERR_NOT_FINISHED) {
                    ESP_LOGD(TAG, "Time sync check result: %s", esp_err_to_name(time_ret));
                }
            }
            
            if (free_heap > 30000) {
                esp_err_t mqtt_ret = mqtt_manager_loop(0);
                if (mqtt_ret != ESP_OK) {
                    ESP_LOGD(TAG, "MQTT loop result: %s", esp_err_to_name(mqtt_ret));
                }
            } else {
                ESP_LOGW(TAG, "Skipping MQTT loop due to low memory: %zu bytes", free_heap);
            }
            
            if (mqtt_connected) {
                if (g_watchdog_available) {
                    watchdog_manager_report_activity(WATCHDOG_CHECK_MQTT);
                }
                
                if (cycle_count % 24 == 0) {
                    char time_str[32];
                    if (time_manager_get_lima_time_str(time_str, sizeof(time_str)) == ESP_OK) {
                        ESP_LOGI(TAG, "System OK - WiFi+MQTT connected, Time: %s", time_str);
                    } else {
                        ESP_LOGI(TAG, "System OK - WiFi+MQTT connected");
                    }
                }
            } else {
                if (cycle_count % 24 == 0 && free_heap > 50000) {
                    ESP_LOGI(TAG, "Retrying MQTT connection");
                    char esp32_id[ESP32_ID_LENGTH + 1];
                    if (esp32_id_manager_get_id(esp32_id, sizeof(esp32_id)) == ESP_OK) {
                        mqtt_manager_set_esp32_id(esp32_id);
                        mqtt_manager_connect();
                    }
                } else if (free_heap <= 50000) {
                    ESP_LOGW(TAG, "Skipping MQTT reconnect due to low memory: %zu bytes", free_heap);
                }
            }
        } else {
            if (cycle_count % 12 == 0) {
                ESP_LOGI(TAG, "WiFi disconnected - attempting recovery");
                wifi_manager_handle_disconnection("FirePanel", "firepanel", 3);
            }
        }
        
        if (g_watchdog_available) {
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
        vTaskDelay(pdMS_TO_TICKS(2000));
        
        watchdog_ret = watchdog_manager_init();
        if (watchdog_ret != ESP_OK) {
            ESP_LOGE(TAG, "Watchdog initialization failed twice, continuing without custom watchdog manager");
            ESP_LOGI(TAG, "System will use default ESP-IDF TWDT");
            g_watchdog_available = false;
        } else {
            ESP_LOGI(TAG, "Watchdog manager initialized on second attempt");
            g_watchdog_available = true;
            
            watchdog_manager_set_event_callback(watchdog_event_callback, NULL);
            
            esp_err_t reg_ret = watchdog_manager_register_task(NULL, "app_main");
            if (reg_ret != ESP_OK) {
                ESP_LOGW(TAG, "Failed to register main task in watchdog: %s", esp_err_to_name(reg_ret));
            }
        }
    } else {
        ESP_LOGI(TAG, "Watchdog manager initialized successfully");
        g_watchdog_available = true;
        
        watchdog_manager_set_event_callback(watchdog_event_callback, NULL);
        
        esp_err_t reg_ret = watchdog_manager_register_task(NULL, "app_main");
        if (reg_ret != ESP_OK) {
            ESP_LOGW(TAG, "Failed to register main task in watchdog: %s", esp_err_to_name(reg_ret));
        }
    }
    
    if (g_watchdog_available) {
        watchdog_manager_feed();
    }
    
    ESP_ERROR_CHECK(config_manager_init());
    if (g_watchdog_available) {
        watchdog_manager_feed();
    }
    
    ESP_ERROR_CHECK(esp32_id_manager_init());
    if (g_watchdog_available) {
        watchdog_manager_feed();
    }
    
    char esp32_id[ESP32_ID_LENGTH + 1];
    ESP_ERROR_CHECK(esp32_id_manager_get_id(esp32_id, sizeof(esp32_id)));
    ESP_LOGI(TAG, "ESP32 ID: %s", esp32_id);
    
    char mac_address[ESP32_MAC_STR_LENGTH + 1];
    ESP_ERROR_CHECK(esp32_id_manager_get_mac(mac_address, sizeof(mac_address)));
    ESP_LOGI(TAG, "MAC: %s", mac_address);
    
    if (g_watchdog_available) {
        watchdog_manager_feed();
    }
    
    ESP_LOGI(TAG, "Phase 2: Manager initialization");
    
    ESP_ERROR_CHECK(wifi_manager_init());
    if (g_watchdog_available) {
        watchdog_manager_feed();
    }
    
    ESP_ERROR_CHECK(time_manager_init());
    if (g_watchdog_available) {
        watchdog_manager_feed();
    }
    
    ESP_ERROR_CHECK(mqtt_manager_init());
    if (g_watchdog_available) {
        watchdog_manager_feed();
    }
    
    ESP_LOGI(TAG, "Phase 3: Callback configuration");
    
    ESP_ERROR_CHECK(wifi_manager_set_state_callback(wifi_state_callback, NULL));
    ESP_ERROR_CHECK(mqtt_manager_set_state_callback(mqtt_state_callback, NULL));
    ESP_ERROR_CHECK(mqtt_manager_set_message_callback(mqtt_message_callback, NULL));
    ESP_ERROR_CHECK(mqtt_manager_set_esp32_id(esp32_id));
    
    if (g_watchdog_available) {
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
    
    if (g_watchdog_available) {
        watchdog_manager_feed();
    }
    
    ESP_LOGI(TAG, "Phase 5: Creating system monitor task");
    
    BaseType_t xReturned = xTaskCreate(
        system_monitor_task,
        "sys_monitor",
        8192,
        NULL,
        5,
        NULL
    );
    
    if (xReturned != pdPASS) {
        ESP_LOGE(TAG, "Failed to create system monitor task");
        if (g_watchdog_available) {
            watchdog_manager_force_reset("task_creation_failed");
        } else {
            esp_restart();
        }
    }
    
    g_system_initialized = true;
    ESP_LOGI(TAG, "System initialization completed successfully");
    
    uint32_t main_cycle = 0;
    
    while (1) {
        if (g_watchdog_available) {
            watchdog_manager_feed();
        } else {
            esp_task_wdt_reset();
        }
        
        main_cycle++;
        
        if (main_cycle % 12 == 0) {
            if (g_watchdog_available) {
                uint32_t feed_count, error_count;
                const char *last_reset_reason;
                
                if (watchdog_manager_get_stats(&feed_count, &error_count, &last_reset_reason) == ESP_OK) {
                    if (error_count > 0) {
                        ESP_LOGW(TAG, "Watchdog errors detected: %lu (last reset: %s)", 
                                error_count, last_reset_reason ? last_reset_reason : "none");
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
            if (free_heap < 30000) {
                ESP_LOGW(TAG, "Main task: Low memory detected: %zu bytes", free_heap);
            }
        }
        
        vTaskDelay(pdMS_TO_TICKS(5000));
    }
    
    if (g_relay_manager_initialized) {
        ESP_LOGI(TAG, "Cleaning up Relay Manager");
        relay_manager_deinit();
        g_relay_manager_initialized = false;
    }
}