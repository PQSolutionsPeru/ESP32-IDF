#include "wifi_manager.h"
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "esp_wifi.h"
#include "esp_log.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_mac.h"
#include "esp_system.h"
#include "nvs_flash.h"
#include <inttypes.h>
#include "config_manager.h"
#include "esp32_id_manager.h"
#include "esp_timer.h"

#define TAG "WIFI_MGR"

// Event group bits
#define WIFI_CONNECTED_BIT BIT0
#define WIFI_DISCONNECTED_BIT BIT1
#define WIFI_AP_STARTED_BIT BIT2
#define WIFI_SCAN_DONE_BIT BIT3
#define WIFI_CONNECT_FAIL_BIT BIT4

// Connection parameters - optimized for 4MB
#define MAX_RECONNECT_ATTEMPTS 3
#define WIFI_CONNECT_TIMEOUT_MS 15000
#define RECONNECT_DELAY_MS 2000
#define DEFAULT_AP_IP "192.168.4.1"

// WiFi Manager context - optimized structure
typedef struct {
    wifi_manager_state_t state;
    EventGroupHandle_t event_group;
    esp_netif_t *sta_netif;
    esp_netif_t *ap_netif;
    char ssid[33];
    char password[65];
    char ap_ssid[33];
    char ap_password[65];
    bool credentials_saved;
    int reconnect_attempts;
    wifi_ap_record_t ap_info;
    uint16_t scan_ap_count;
    wifi_ap_record_t *scan_ap_list;
    bool scan_in_progress;
    void (*state_callback)(wifi_manager_state_t state, void *user_data);
    void *user_data;
} wifi_manager_context_t;

static wifi_manager_context_t s_wifi_manager_ctx = {0};

// WiFi event handler - ULTRA-SIMPLIFIED for 4MB ESP32
static void wifi_event_handler(void *arg, esp_event_base_t event_base,
                               int32_t event_id, void *event_data)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (event_base == WIFI_EVENT) {
        switch (event_id) {
            case WIFI_EVENT_STA_START:
                ESP_LOGI(TAG, "WiFi STA started");
                break;
            
            case WIFI_EVENT_STA_CONNECTED:
                ESP_LOGI(TAG, "WiFi connected to: %s", ctx->ssid);
                break;
            
            case WIFI_EVENT_STA_DISCONNECTED: {
                wifi_event_sta_disconnected_t *disconn = (wifi_event_sta_disconnected_t*) event_data;
                ESP_LOGW(TAG, "WiFi disconnected, reason: %d", (int)disconn->reason);
                
                if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
                    xEventGroupClearBits(ctx->event_group, WIFI_CONNECTED_BIT);
                    xEventGroupSetBits(ctx->event_group, WIFI_DISCONNECTED_BIT);
                }
                
                // Auto-reconnect logic
                if (ctx->credentials_saved && ctx->reconnect_attempts < MAX_RECONNECT_ATTEMPTS) {
                    ctx->reconnect_attempts++;
                    ESP_LOGI(TAG, "Reconnect attempt %d/%d", ctx->reconnect_attempts, MAX_RECONNECT_ATTEMPTS);
                    
                    if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
                        ctx->state = WIFI_MANAGER_STATE_CONNECTING;
                        if (ctx->state_callback) {
                            ctx->state_callback(ctx->state, ctx->user_data);
                        }
                    }
                    
                    vTaskDelay(pdMS_TO_TICKS(RECONNECT_DELAY_MS));
                    esp_wifi_connect();
                } else if (ctx->reconnect_attempts >= MAX_RECONNECT_ATTEMPTS) {
                    ESP_LOGE(TAG, "Max reconnect attempts reached");
                    xEventGroupSetBits(ctx->event_group, WIFI_CONNECT_FAIL_BIT);
                    
                    if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
                        ctx->state = WIFI_MANAGER_STATE_ERROR;
                        if (ctx->state_callback) {
                            ctx->state_callback(ctx->state, ctx->user_data);
                        }
                    }
                } else {
                    if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
                        ctx->state = WIFI_MANAGER_STATE_DISCONNECTED;
                        if (ctx->state_callback) {
                            ctx->state_callback(ctx->state, ctx->user_data);
                        }
                    }
                }
                break;
            }
            
            case WIFI_EVENT_AP_START:
                ESP_LOGI(TAG, "WiFi AP started");
                xEventGroupSetBits(ctx->event_group, WIFI_AP_STARTED_BIT);
                
                if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
                    ctx->state = WIFI_MANAGER_STATE_AP_MODE;
                    if (ctx->state_callback) {
                        ctx->state_callback(ctx->state, ctx->user_data);
                    }
                }
                break;
                
            case WIFI_EVENT_AP_STOP:
                ESP_LOGI(TAG, "WiFi AP stopped");
                xEventGroupClearBits(ctx->event_group, WIFI_AP_STARTED_BIT);
                break;
                
            case WIFI_EVENT_AP_STACONNECTED: {
                wifi_event_ap_staconnected_t* event = (wifi_event_ap_staconnected_t*) event_data;
                ESP_LOGI(TAG, "Station connected to AP");
                break;
            }
                
            case WIFI_EVENT_AP_STADISCONNECTED: {
                wifi_event_ap_stadisconnected_t* event = (wifi_event_ap_stadisconnected_t*) event_data;
                ESP_LOGI(TAG, "Station disconnected from AP");
                break;
            }
                
            case WIFI_EVENT_SCAN_DONE: {
                wifi_event_sta_scan_done_t *scan_done = (wifi_event_sta_scan_done_t*) event_data;
                ESP_LOGI(TAG, "WiFi scan done, found %u APs", (unsigned int)scan_done->number);
                
                ctx->scan_in_progress = false;
                
                if (scan_done->status == 0) {
                    if (ctx->scan_ap_list != NULL) {
                        free(ctx->scan_ap_list);
                        ctx->scan_ap_list = NULL;
                    }
                    
                    ctx->scan_ap_count = scan_done->number;
                    if (ctx->scan_ap_count > 0) {
                        ctx->scan_ap_list = (wifi_ap_record_t*)malloc(ctx->scan_ap_count * sizeof(wifi_ap_record_t));
                        if (ctx->scan_ap_list != NULL) {
                            ESP_ERROR_CHECK(esp_wifi_scan_get_ap_records(&ctx->scan_ap_count, ctx->scan_ap_list));
                            
                            // Sort by RSSI (strongest first)
                            for (int i = 0; i < ctx->scan_ap_count - 1; i++) {
                                for (int j = i + 1; j < ctx->scan_ap_count; j++) {
                                    if (ctx->scan_ap_list[j].rssi > ctx->scan_ap_list[i].rssi) {
                                        wifi_ap_record_t temp = ctx->scan_ap_list[i];
                                        ctx->scan_ap_list[i] = ctx->scan_ap_list[j];
                                        ctx->scan_ap_list[j] = temp;
                                    }
                                }
                            }
                        }
                    }
                }
                
                xEventGroupSetBits(ctx->event_group, WIFI_SCAN_DONE_BIT);
                break;
            }
        }
    } else if (event_base == IP_EVENT) {
        switch (event_id) {
            case IP_EVENT_STA_GOT_IP: {
                ip_event_got_ip_t *event = (ip_event_got_ip_t*) event_data;
                ESP_LOGI(TAG, "Got IP: " IPSTR, IP2STR(&event->ip_info.ip));
                
                xEventGroupClearBits(ctx->event_group, WIFI_DISCONNECTED_BIT | WIFI_CONNECT_FAIL_BIT);
                xEventGroupSetBits(ctx->event_group, WIFI_CONNECTED_BIT);
                
                ctx->reconnect_attempts = 0;
                
                if (ctx->state == WIFI_MANAGER_STATE_AP_MODE || 
                    ctx->state == WIFI_MANAGER_STATE_STA_AP_MODE) {
                    ctx->state = WIFI_MANAGER_STATE_STA_AP_MODE;
                } else {
                    ctx->state = WIFI_MANAGER_STATE_CONNECTED;
                }
                
                if (ctx->state_callback) {
                    ctx->state_callback(ctx->state, ctx->user_data);
                }
                
                esp_wifi_sta_get_ap_info(&ctx->ap_info);
                break;
            }
            
            case IP_EVENT_STA_LOST_IP:
                ESP_LOGW(TAG, "Lost IP address");
                if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
                    xEventGroupClearBits(ctx->event_group, WIFI_CONNECTED_BIT);
                }
                break;
        }
    }
}

// Initialize WiFi Manager - simplified
esp_err_t wifi_manager_init(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group != NULL) {
        ESP_LOGW(TAG, "Already initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Initializing WiFi Manager");
    
    ctx->state = WIFI_MANAGER_STATE_INIT;
    ctx->scan_in_progress = false;
    ctx->event_group = xEventGroupCreate();
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "Failed to create event group");
        return ESP_ERR_NO_MEM;
    }
    
    // Initialize NVS
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);
    
    // Initialize TCP/IP stack
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    
    // Create network interfaces
    ctx->sta_netif = esp_netif_create_default_wifi_sta();
    ctx->ap_netif = esp_netif_create_default_wifi_ap();
    
    if (ctx->sta_netif == NULL || ctx->ap_netif == NULL) {
        ESP_LOGE(TAG, "Failed to create network interfaces");
        return ESP_FAIL;
    }
    
    // Initialize WiFi
    wifi_init_config_t wifi_init_config = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&wifi_init_config));
    
    // Register event handlers
    ESP_ERROR_CHECK(esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                                       &wifi_event_handler, NULL, NULL));
    ESP_ERROR_CHECK(esp_event_handler_instance_register(IP_EVENT, ESP_EVENT_ANY_ID,
                                                       &wifi_event_handler, NULL, NULL));
    
    // Set WiFi mode and start
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_start());
    
    // Load saved credentials
    err = config_manager_get_str("wifi_ssid", ctx->ssid, sizeof(ctx->ssid));
    if (err == ESP_OK) {
        err = config_manager_get_str("wifi_password", ctx->password, sizeof(ctx->password));
        if (err == ESP_OK) {
            ESP_LOGI(TAG, "Found saved credentials for: %s", ctx->ssid);
            ctx->credentials_saved = true;
        }
    }
    
    ctx->state = WIFI_MANAGER_STATE_DISCONNECTED;
    xEventGroupSetBits(ctx->event_group, WIFI_DISCONNECTED_BIT);
    
    ESP_LOGI(TAG, "WiFi Manager initialized");
    return ESP_OK;
}

// Connect to WiFi network - optimized
esp_err_t wifi_manager_connect(const char *ssid, const char *password, bool save)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL || ssid == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (strlen(ssid) > 32 || (password && strlen(password) > 64)) {
        return ESP_ERR_INVALID_ARG;
    }
    
    ESP_LOGI(TAG, "Connecting to: %s", ssid);
    
    // Disconnect if currently connected
    if (ctx->state == WIFI_MANAGER_STATE_CONNECTED || 
        ctx->state == WIFI_MANAGER_STATE_CONNECTING) {
        esp_wifi_disconnect();
        xEventGroupWaitBits(ctx->event_group, WIFI_DISCONNECTED_BIT,
                          pdFALSE, pdFALSE, pdMS_TO_TICKS(5000));
    }
    
    // Save credentials
    strncpy(ctx->ssid, ssid, sizeof(ctx->ssid) - 1);
    ctx->ssid[sizeof(ctx->ssid) - 1] = '\0';
    
    if (password != NULL) {
        strncpy(ctx->password, password, sizeof(ctx->password) - 1);
        ctx->password[sizeof(ctx->password) - 1] = '\0';
    } else {
        ctx->password[0] = '\0';
    }
    
    if (save) {
        ESP_ERROR_CHECK(config_manager_set_str("wifi_ssid", ctx->ssid));
        ESP_ERROR_CHECK(config_manager_set_str("wifi_password", ctx->password));
        ctx->credentials_saved = true;
    }
    
    // Configure WiFi
    wifi_config_t wifi_config = {0};
    strncpy((char *)wifi_config.sta.ssid, ctx->ssid, sizeof(wifi_config.sta.ssid) - 1);
    if (ctx->password[0] != '\0') {
        strncpy((char *)wifi_config.sta.password, ctx->password, sizeof(wifi_config.sta.password) - 1);
    }
    
    wifi_config.sta.scan_method = WIFI_FAST_SCAN;
    wifi_config.sta.sort_method = WIFI_CONNECT_AP_BY_SIGNAL;
    wifi_config.sta.threshold.rssi = -127;
    wifi_config.sta.threshold.authmode = WIFI_AUTH_WPA2_PSK;
    
    // Check current mode
    wifi_mode_t current_mode;
    ESP_ERROR_CHECK(esp_wifi_get_mode(&current_mode));
    
    if (current_mode == WIFI_MODE_AP) {
        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_APSTA));
        ctx->state = WIFI_MANAGER_STATE_STA_AP_MODE;
    } else if (current_mode == WIFI_MODE_STA) {
        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    }
    
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wifi_config));
    
    if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
        ctx->state = WIFI_MANAGER_STATE_CONNECTING;
    }
    ctx->reconnect_attempts = 0;
    if (ctx->state_callback) {
        ctx->state_callback(ctx->state, ctx->user_data);
    }
    
    ESP_ERROR_CHECK(esp_wifi_connect());
    
    // Wait for connection
    EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                          WIFI_CONNECTED_BIT | WIFI_CONNECT_FAIL_BIT,
                                          pdFALSE, pdFALSE, pdMS_TO_TICKS(WIFI_CONNECT_TIMEOUT_MS));
    
    if (bits & WIFI_CONNECTED_BIT) {
        ESP_LOGI(TAG, "Connected successfully");
        return ESP_OK;
    } else if (bits & WIFI_CONNECT_FAIL_BIT) {
        ESP_LOGE(TAG, "Connection failed");
        if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
            ctx->state = WIFI_MANAGER_STATE_ERROR;
            if (ctx->state_callback) {
                ctx->state_callback(ctx->state, ctx->user_data);
            }
        }
        return ESP_FAIL;
    } else {
        ESP_LOGW(TAG, "Connection timeout");
        if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
            ctx->state = WIFI_MANAGER_STATE_ERROR;
            if (ctx->state_callback) {
                ctx->state_callback(ctx->state, ctx->user_data);
            }
        }
        return ESP_ERR_TIMEOUT;
    }
}

// Connect with saved credentials
esp_err_t wifi_manager_connect_saved(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (!ctx->credentials_saved) {
        return ESP_ERR_NOT_FOUND;
    }
    
    return wifi_manager_connect(ctx->ssid, ctx->password, false);
}

// Start AP mode - simplified
esp_err_t wifi_manager_start_ap_mode(const char *ap_ssid, const char *ap_password)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL || ap_ssid == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    ESP_LOGI(TAG, "Starting AP: %s", ap_ssid);
    
    strncpy(ctx->ap_ssid, ap_ssid, sizeof(ctx->ap_ssid) - 1);
    ctx->ap_ssid[sizeof(ctx->ap_ssid) - 1] = '\0';
    
    if (ap_password != NULL) {
        strncpy(ctx->ap_password, ap_password, sizeof(ctx->ap_password) - 1);
        ctx->ap_password[sizeof(ctx->ap_password) - 1] = '\0';
    } else {
        ctx->ap_password[0] = '\0';
    }
    
    wifi_config_t ap_config = {0};
    strncpy((char *)ap_config.ap.ssid, ctx->ap_ssid, sizeof(ap_config.ap.ssid) - 1);
    ap_config.ap.ssid_len = strlen(ctx->ap_ssid);
    
    if (ctx->ap_password[0] != '\0' && strlen(ctx->ap_password) >= 8) {
        strncpy((char *)ap_config.ap.password, ctx->ap_password, sizeof(ap_config.ap.password) - 1);
        ap_config.ap.authmode = WIFI_AUTH_WPA2_PSK;
    } else {
        ap_config.ap.authmode = WIFI_AUTH_OPEN;
    }
    
    ap_config.ap.max_connection = 4;
    ap_config.ap.beacon_interval = 100;
    
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &ap_config));
    
    EventBits_t bits = xEventGroupWaitBits(ctx->event_group, WIFI_AP_STARTED_BIT,
                                          pdFALSE, pdFALSE, pdMS_TO_TICKS(5000));
    
    return (bits & WIFI_AP_STARTED_BIT) ? ESP_OK : ESP_ERR_TIMEOUT;
}

// NUEVA FUNCIÓN: Start combined STA+AP mode
esp_err_t wifi_manager_start_sta_ap_mode(const char *ap_ssid, const char *ap_password)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL || ap_ssid == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    ESP_LOGI(TAG, "Starting STA+AP mode with AP: %s", ap_ssid);
    
    strncpy(ctx->ap_ssid, ap_ssid, sizeof(ctx->ap_ssid) - 1);
    ctx->ap_ssid[sizeof(ctx->ap_ssid) - 1] = '\0';
    
    if (ap_password != NULL) {
        strncpy(ctx->ap_password, ap_password, sizeof(ctx->ap_password) - 1);
        ctx->ap_password[sizeof(ctx->ap_password) - 1] = '\0';
    } else {
        ctx->ap_password[0] = '\0';
    }
    
    // Configure AP
    wifi_config_t ap_config = {0};
    strncpy((char *)ap_config.ap.ssid, ctx->ap_ssid, sizeof(ap_config.ap.ssid) - 1);
    ap_config.ap.ssid_len = strlen(ctx->ap_ssid);
    
    if (ctx->ap_password[0] != '\0' && strlen(ctx->ap_password) >= 8) {
        strncpy((char *)ap_config.ap.password, ctx->ap_password, sizeof(ap_config.ap.password) - 1);
        ap_config.ap.authmode = WIFI_AUTH_WPA2_PSK;
    } else {
        ap_config.ap.authmode = WIFI_AUTH_OPEN;
    }
    
    ap_config.ap.max_connection = 4;
    ap_config.ap.beacon_interval = 100;
    
    // Set mode to STA+AP
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_APSTA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &ap_config));
    
    // Update state
    ctx->state = WIFI_MANAGER_STATE_STA_AP_MODE;
    if (ctx->state_callback) {
        ctx->state_callback(ctx->state, ctx->user_data);
    }
    
    // Wait for AP to start
    EventBits_t bits = xEventGroupWaitBits(ctx->event_group, WIFI_AP_STARTED_BIT,
                                          pdFALSE, pdFALSE, pdMS_TO_TICKS(5000));
    
    return (bits & WIFI_AP_STARTED_BIT) ? ESP_OK : ESP_ERR_TIMEOUT;
}

// NUEVA FUNCIÓN: Stop AP mode
esp_err_t wifi_manager_stop_ap_mode(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Stopping AP mode");
    
    wifi_mode_t current_mode;
    ESP_ERROR_CHECK(esp_wifi_get_mode(&current_mode));
    
    if (current_mode == WIFI_MODE_AP) {
        // If only AP mode, switch to STA
        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
        ctx->state = WIFI_MANAGER_STATE_DISCONNECTED;
    } else if (current_mode == WIFI_MODE_APSTA) {
        // If STA+AP mode, switch to STA only
        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
        if (wifi_manager_is_connected()) {
            ctx->state = WIFI_MANAGER_STATE_CONNECTED;
        } else {
            ctx->state = WIFI_MANAGER_STATE_DISCONNECTED;
        }
    }
    
    if (ctx->state_callback) {
        ctx->state_callback(ctx->state, ctx->user_data);
    }
    
    return ESP_OK;
}

// NUEVA FUNCIÓN: Set STA mode only
esp_err_t wifi_manager_set_sta_mode(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Setting STA mode only");
    
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    
    if (wifi_manager_is_connected()) {
        ctx->state = WIFI_MANAGER_STATE_CONNECTED;
    } else {
        ctx->state = WIFI_MANAGER_STATE_DISCONNECTED;
    }
    
    if (ctx->state_callback) {
        ctx->state_callback(ctx->state, ctx->user_data);
    }
    
    return ESP_OK;
}

// NUEVA FUNCIÓN: Get configured SSID
esp_err_t wifi_manager_get_configured_ssid(char *ssid, size_t max_len)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ssid == NULL || max_len == 0) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (ctx->event_group == NULL) {
        ssid[0] = '\0';
        return ESP_ERR_INVALID_STATE;
    }
    
    strncpy(ssid, ctx->ssid, max_len - 1);
    ssid[max_len - 1] = '\0';
    
    return ESP_OK;
}

// NUEVA FUNCIÓN: Start WiFi scan
esp_err_t wifi_manager_start_scan(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ctx->scan_in_progress) {
        ESP_LOGW(TAG, "Scan already in progress");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Starting WiFi scan");
    
    wifi_scan_config_t scan_config = {
        .ssid = NULL,
        .bssid = NULL,
        .channel = 0,
        .show_hidden = false,
        .scan_type = WIFI_SCAN_TYPE_ACTIVE,
        .scan_time = {
            .active = {
                .min = 100,
                .max = 300
            }
        }
    };
    
    ctx->scan_in_progress = true;
    esp_err_t ret = esp_wifi_scan_start(&scan_config, false);
    
    if (ret != ESP_OK) {
        ctx->scan_in_progress = false;
        ESP_LOGE(TAG, "Failed to start scan: %s", esp_err_to_name(ret));
        return ret;
    }
    
    return ESP_OK;
}

// NUEVA FUNCIÓN: Get scan results
esp_err_t wifi_manager_get_scan_results(wifi_scan_result_t *results, size_t max_networks, size_t *num_networks)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (results == NULL || num_networks == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (ctx->event_group == NULL) {
        *num_networks = 0;
        return ESP_ERR_INVALID_STATE;
    }
    
    *num_networks = 0;
    
    if (ctx->scan_ap_list == NULL || ctx->scan_ap_count == 0) {
        ESP_LOGW(TAG, "No scan results available");
        return ESP_ERR_NOT_FOUND;
    }
    
    size_t copy_count = (ctx->scan_ap_count < max_networks) ? ctx->scan_ap_count : max_networks;
    
    for (size_t i = 0; i < copy_count; i++) {
        strncpy(results[i].ssid, (char *)ctx->scan_ap_list[i].ssid, sizeof(results[i].ssid) - 1);
        results[i].ssid[sizeof(results[i].ssid) - 1] = '\0';
        results[i].rssi = ctx->scan_ap_list[i].rssi;
        results[i].auth_mode = ctx->scan_ap_list[i].authmode;
    }
    
    *num_networks = copy_count;
    
    ESP_LOGI(TAG, "Returning %d scan results", (int)*num_networks);
    return ESP_OK;
}

// Other essential functions - simplified implementations
bool wifi_manager_is_connected(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return false;
    }
    
    EventBits_t bits = xEventGroupGetBits(ctx->event_group);
    return (bits & WIFI_CONNECTED_BIT) != 0;
}

esp_err_t wifi_manager_get_ip(char *ip, size_t max_len)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ip == NULL || max_len < 16 || !wifi_manager_is_connected()) {
        strncpy(ip, "0.0.0.0", max_len);
        return ESP_ERR_INVALID_STATE;
    }
    
    esp_netif_ip_info_t ip_info;
    ESP_ERROR_CHECK(esp_netif_get_ip_info(ctx->sta_netif, &ip_info));
    snprintf(ip, max_len, IPSTR, IP2STR(&ip_info.ip));
    return ESP_OK;
}

esp_err_t wifi_manager_get_ap_ip(char *ip, size_t max_len)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ip == NULL || max_len < 16) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (ctx->state != WIFI_MANAGER_STATE_AP_MODE && 
        ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
        strncpy(ip, "0.0.0.0", max_len);
        return ESP_ERR_INVALID_STATE;
    }
    
    esp_netif_ip_info_t ip_info;
    ESP_ERROR_CHECK(esp_netif_get_ip_info(ctx->ap_netif, &ip_info));
    snprintf(ip, max_len, IPSTR, IP2STR(&ip_info.ip));
    return ESP_OK;
}

esp_err_t wifi_manager_get_rssi(int8_t *rssi)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (rssi == NULL || !wifi_manager_is_connected()) {
        if (rssi) *rssi = -127;
        return ESP_ERR_INVALID_STATE;
    }
    
    if (esp_wifi_sta_get_ap_info(&ctx->ap_info) != ESP_OK) {
        *rssi = -127;
        return ESP_FAIL;
    }
    
    *rssi = ctx->ap_info.rssi;
    return ESP_OK;
}

wifi_manager_state_t wifi_manager_get_state(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    return (ctx->event_group == NULL) ? WIFI_MANAGER_STATE_INIT : ctx->state;
}

esp_err_t wifi_manager_set_state_callback(void (*callback)(wifi_manager_state_t state, void *user_data), void *user_data)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    ctx->state_callback = callback;
    ctx->user_data = user_data;
    return ESP_OK;
}

esp_err_t wifi_manager_handle_disconnection(const char *ap_ssid_prefix, const char *ap_password, int max_attempts) {
    static int reconnect_attempts = 0;
    static int64_t last_reconnect_time = 0;
    int64_t current_time = esp_timer_get_time() / 1000;
    
    if (wifi_manager_is_connected()) {
        reconnect_attempts = 0;
        return ESP_OK;
    }
    
    if (current_time - last_reconnect_time > 10000) {
        last_reconnect_time = current_time;
        reconnect_attempts++;
        
        ESP_LOGI(TAG, "Reconnection attempt %d/%d", reconnect_attempts, max_attempts);
        
        esp_err_t ret = wifi_manager_connect_saved();
        
        if (reconnect_attempts >= max_attempts && ret != ESP_OK) {
            char ap_ssid[33];
            esp_err_t ap_ret = wifi_manager_generate_ap_ssid(ap_ssid, sizeof(ap_ssid), ap_ssid_prefix);
            if (ap_ret == ESP_OK) {
                ESP_LOGI(TAG, "Starting AP: %s", ap_ssid);
                wifi_manager_start_ap_mode(ap_ssid, ap_password);
                reconnect_attempts = 0;
            }
        }
    }
    
    return ESP_OK;
}

esp_err_t wifi_manager_generate_ap_ssid(char *ap_ssid, size_t max_len, const char *prefix)
{
    if (ap_ssid == NULL || max_len < 8 || prefix == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    char esp32_id[ESP32_ID_LENGTH + 1];
    esp_err_t ret = esp32_id_manager_get_id(esp32_id, sizeof(esp32_id));
    if (ret != ESP_OK) {
        return ret;
    }
    
    size_t id_len = strlen(esp32_id);
    size_t prefix_len = strlen(prefix);
    
    if (id_len < 6 || prefix_len + 7 > max_len) {
        return ESP_ERR_INVALID_ARG;
    }
    
    snprintf(ap_ssid, max_len, "%s_%s", prefix, &esp32_id[id_len - 6]);
    return ESP_OK;
}

// Additional simplified functions for completeness
esp_err_t wifi_manager_disconnect(void) {
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Disconnecting from WiFi");
    ESP_ERROR_CHECK(esp_wifi_disconnect());
    
    EventBits_t bits = xEventGroupWaitBits(ctx->event_group, WIFI_DISCONNECTED_BIT,
                                          pdFALSE, pdFALSE, pdMS_TO_TICKS(5000));
    
    return (bits & WIFI_DISCONNECTED_BIT) ? ESP_OK : ESP_ERR_TIMEOUT;
}

esp_err_t wifi_manager_forget_network(void) {
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Forgetting saved network");
    
    if (ctx->state == WIFI_MANAGER_STATE_CONNECTED || 
        ctx->state == WIFI_MANAGER_STATE_CONNECTING) {
        wifi_manager_disconnect();
    }
    
    config_manager_erase_key("wifi_ssid");
    config_manager_erase_key("wifi_password");
    
    ctx->ssid[0] = '\0';
    ctx->password[0] = '\0';
    ctx->credentials_saved = false;
    
    return ESP_OK;
}