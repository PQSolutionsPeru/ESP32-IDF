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

#define WIFI_CONNECTED_BIT BIT0
#define WIFI_DISCONNECTED_BIT BIT1
#define WIFI_AP_STARTED_BIT BIT2
#define WIFI_SCAN_DONE_BIT BIT3
#define WIFI_CONNECT_FAIL_BIT BIT4

#define MAX_RECONNECT_ATTEMPTS 5
#define WIFI_CONNECT_TIMEOUT_MS 45000
#define RECONNECT_DELAY_MS 5000
#define DEFAULT_AP_IP "192.168.4.1"

typedef struct {
    wifi_manager_state_t state;
    EventGroupHandle_t event_group;
    SemaphoreHandle_t connection_mutex;
    esp_netif_t *sta_netif;
    esp_netif_t *ap_netif;
    char ssid[33];
    char password[65];
    char ap_ssid[33];
    char ap_password[65];
    bool credentials_saved;
    int reconnect_attempts;
    wifi_ap_record_t ap_info;
    wifi_scan_config_t scan_config;
    uint16_t scan_ap_count;
    wifi_ap_record_t *scan_ap_list;
    bool scan_in_progress;
    bool temporary_apsta_mode;
    bool connection_in_progress;
    uint32_t event_group_operations;
    uint32_t netif_recreation_count;
    void (*state_callback)(wifi_manager_state_t state, void *user_data);
    void *user_data;
} wifi_manager_context_t;

static wifi_manager_context_t s_wifi_manager_ctx = {0};

static esp_err_t recreate_wifi_event_group(wifi_manager_context_t *ctx) {
    EventBits_t current_bits = xEventGroupGetBits(ctx->event_group);
    
    vEventGroupDelete(ctx->event_group);
    ctx->event_group = xEventGroupCreate();
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "Failed to recreate WiFi event group");
        return ESP_ERR_NO_MEM;
    }
    
    if (current_bits != 0) {
        xEventGroupSetBits(ctx->event_group, current_bits);
    }
    
    ctx->event_group_operations = 0;
    ESP_LOGI(TAG, "WiFi event group recreated");
    return ESP_OK;
}

static void safe_wifi_set_bits(wifi_manager_context_t *ctx, EventBits_t bits) {
    ctx->event_group_operations++;
    
    if (ctx->event_group_operations >= 1000000) {
        recreate_wifi_event_group(ctx);
    }
    
    xEventGroupSetBits(ctx->event_group, bits);
}

static void safe_wifi_clear_bits(wifi_manager_context_t *ctx, EventBits_t bits) {
    ctx->event_group_operations++;
    
    if (ctx->event_group_operations >= 1000000) {
        recreate_wifi_event_group(ctx);
    }
    
    xEventGroupClearBits(ctx->event_group, bits);
}

static esp_err_t cleanup_and_recreate_netifs(wifi_manager_context_t *ctx) {
    esp_err_t ret = ESP_OK;
    
    if (ctx->sta_netif) {
        esp_netif_destroy(ctx->sta_netif);
        ctx->sta_netif = NULL;
    }
    
    if (ctx->ap_netif) {
        esp_netif_destroy(ctx->ap_netif);
        ctx->ap_netif = NULL;
    }
    
    vTaskDelay(pdMS_TO_TICKS(100));
    
    ctx->sta_netif = esp_netif_create_default_wifi_sta();
    if (ctx->sta_netif == NULL) {
        ESP_LOGE(TAG, "Failed to recreate station netif");
        return ESP_FAIL;
    }
    
    ctx->ap_netif = esp_netif_create_default_wifi_ap();
    if (ctx->ap_netif == NULL) {
        ESP_LOGE(TAG, "Failed to recreate AP netif");
        esp_netif_destroy(ctx->sta_netif);
        ctx->sta_netif = NULL;
        return ESP_FAIL;
    }
    
    return ret;
}

static esp_err_t _internal_wifi_connect_safe(void) {
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->connection_in_progress) {
        ESP_LOGW(TAG, "Connection already in progress, skipping");
        return ESP_ERR_INVALID_STATE;
    }
    
    wifi_mode_t current_mode;
    esp_err_t ret = esp_wifi_get_mode(&current_mode);
    if (ret != ESP_OK) {
        return ret;
    }
    
    if (current_mode == WIFI_MODE_AP) {
        ESP_LOGW(TAG, "Cannot connect in AP-only mode");
        return ESP_ERR_INVALID_STATE;
    }
    
    ctx->connection_in_progress = true;
    
    ret = esp_wifi_connect();
    if (ret == ESP_ERR_WIFI_CONN) {
        ESP_LOGW(TAG, "WiFi connect failed - connection already in progress");
        ctx->connection_in_progress = false;
        return ESP_ERR_INVALID_STATE;
    } else if (ret != ESP_OK) {
        ESP_LOGE(TAG, "WiFi connect failed: %s", esp_err_to_name(ret));
        ctx->connection_in_progress = false;
        return ret;
    }
    
    return ESP_OK;
}

static void wifi_event_handler(void *arg, esp_event_base_t event_base,
                               int32_t event_id, void *event_data)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (event_base == WIFI_EVENT) {
        switch (event_id) {
            case WIFI_EVENT_SCAN_DONE: {
                wifi_event_sta_scan_done_t *scan_done = (wifi_event_sta_scan_done_t*) event_data;
                ESP_LOGI(TAG, "WiFi scan completed, status: %d, found APs: %u",
                        (int)scan_done->status, (unsigned int)scan_done->number);
                
                ctx->scan_in_progress = false;
                
                if (scan_done->status == 0) {
                    if (ctx->scan_ap_list != NULL) {
                        ctx->scan_ap_list = NULL;
                    }
                    
                    ctx->scan_ap_count = scan_done->number;
                    if (ctx->scan_ap_count > 20) {
                        ctx->scan_ap_count = 20;
                        ESP_LOGW(TAG, "Limiting scan results to 20 APs to preserve memory");
                    }
                    
                    if (ctx->scan_ap_count > 0) {
                        size_t free_heap = esp_get_free_heap_size();
                        
                        if (free_heap > 50000) {
                            static wifi_ap_record_t static_scan_results[20];
                            ctx->scan_ap_list = static_scan_results;
                            
                            esp_err_t get_ret = esp_wifi_scan_get_ap_records(&ctx->scan_ap_count, ctx->scan_ap_list);
                            if (get_ret == ESP_OK) {
                                ESP_LOGI(TAG, "Successfully retrieved %u scan results", ctx->scan_ap_count);
                                
                                for (int i = 0; i < ctx->scan_ap_count - 1; i++) {
                                    for (int j = i + 1; j < ctx->scan_ap_count; j++) {
                                        if (ctx->scan_ap_list[j].rssi > ctx->scan_ap_list[i].rssi) {
                                            wifi_ap_record_t temp = ctx->scan_ap_list[i];
                                            ctx->scan_ap_list[i] = ctx->scan_ap_list[j];
                                            ctx->scan_ap_list[j] = temp;
                                        }
                                    }
                                }
                            } else {
                                ESP_LOGE(TAG, "Failed to get scan records: %s", esp_err_to_name(get_ret));
                                ctx->scan_ap_list = NULL;
                                ctx->scan_ap_count = 0;
                            }
                        } else {
                            ESP_LOGW(TAG, "Insufficient memory for scan results, skipping allocation");
                            ctx->scan_ap_count = 0;
                        }
                    }
                }
                
                if (ctx->temporary_apsta_mode) {
                    ESP_LOGI(TAG, "Reverting to AP mode after scan completion");
                    ctx->temporary_apsta_mode = false;
                    
                    if (!wifi_manager_is_connected()) {
                        esp_err_t mode_ret = esp_wifi_set_mode(WIFI_MODE_AP);
                        if (mode_ret != ESP_OK) {
                            ESP_LOGE(TAG, "Failed to revert to AP mode: %s", esp_err_to_name(mode_ret));
                        }
                    }
                }
                
                safe_wifi_set_bits(ctx, WIFI_SCAN_DONE_BIT);
                break;
            }
            
            case WIFI_EVENT_AP_START:
                ESP_LOGI(TAG, "Access Point started");
                ctx->state = WIFI_MANAGER_STATE_AP_MODE;
                safe_wifi_set_bits(ctx, WIFI_AP_STARTED_BIT);
                if (ctx->state_callback) {
                    ctx->state_callback(ctx->state, ctx->user_data);
                }
                break;
                
            case WIFI_EVENT_AP_STOP:
                ESP_LOGI(TAG, "Access Point stopped");
                safe_wifi_clear_bits(ctx, WIFI_AP_STARTED_BIT);
                break;
            
            default:
                ESP_LOGD(TAG, "Unhandled WiFi event: %d", (int)event_id);
                break;
        }
    } else if (event_base == IP_EVENT) {
        switch (event_id) {
            case IP_EVENT_STA_GOT_IP: {
                ip_event_got_ip_t *event = (ip_event_got_ip_t*) event_data;
                ESP_LOGI(TAG, "Got IP address: " IPSTR, IP2STR(&event->ip_info.ip));
                
                ctx->connection_in_progress = false;
                
                safe_wifi_clear_bits(ctx, WIFI_DISCONNECTED_BIT | WIFI_CONNECT_FAIL_BIT);
                safe_wifi_set_bits(ctx, WIFI_CONNECTED_BIT);
                
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
                
                if (esp_wifi_sta_get_ap_info(&ctx->ap_info) != ESP_OK) {
                    ESP_LOGW(TAG, "Failed to get AP info");
                }
                break;
            }
            
            case IP_EVENT_STA_LOST_IP:
                ESP_LOGW(TAG, "Lost IP address");
                if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
                    safe_wifi_clear_bits(ctx, WIFI_CONNECTED_BIT);
                }
                break;
                
            default:
                ESP_LOGD(TAG, "Unhandled IP event: %d", (int)event_id);
                break;
        }
    }
}

esp_err_t wifi_manager_init(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    esp_err_t ret = ESP_OK;
    
    if (ctx->event_group != NULL) {
        ESP_LOGW(TAG, "WiFi Manager already initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Initializing WiFi Manager");
    
    ctx->state = WIFI_MANAGER_STATE_INIT;
    ctx->scan_in_progress = false;
    ctx->temporary_apsta_mode = false;
    ctx->connection_in_progress = false;
    ctx->event_group_operations = 0;
    ctx->netif_recreation_count = 0;
    
    ctx->event_group = xEventGroupCreate();
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "Failed to create event group");
        return ESP_ERR_NO_MEM;
    }
    
    ctx->connection_mutex = xSemaphoreCreateMutex();
    if (ctx->connection_mutex == NULL) {
        vEventGroupDelete(ctx->event_group);
        ctx->event_group = NULL;
        ESP_LOGE(TAG, "Failed to create connection mutex");
        return ESP_ERR_NO_MEM;
    }
    
    ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_LOGW(TAG, "NVS needs to be erased");
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);
    
    ESP_LOGI(TAG, "Initializing TCP/IP stack");
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    
    ESP_LOGI(TAG, "Creating WiFi station netif");
    ctx->sta_netif = esp_netif_create_default_wifi_sta();
    if (ctx->sta_netif == NULL) {
        ESP_LOGE(TAG, "Failed to create station netif");
        vSemaphoreDelete(ctx->connection_mutex);
        vEventGroupDelete(ctx->event_group);
        ctx->connection_mutex = NULL;
        ctx->event_group = NULL;
        return ESP_FAIL;
    }
    
    ESP_LOGI(TAG, "Creating WiFi AP netif");
    ctx->ap_netif = esp_netif_create_default_wifi_ap();
    if (ctx->ap_netif == NULL) {
        ESP_LOGE(TAG, "Failed to create AP netif");
        esp_netif_destroy(ctx->sta_netif);
        vSemaphoreDelete(ctx->connection_mutex);
        vEventGroupDelete(ctx->event_group);
        ctx->connection_mutex = NULL;
        ctx->event_group = NULL;
        return ESP_FAIL;
    }
    
    wifi_init_config_t wifi_init_config = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&wifi_init_config));
    
    ESP_LOGI(TAG, "Registering event handlers");
    ESP_ERROR_CHECK(esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                                       &wifi_event_handler, NULL, NULL));
    ESP_ERROR_CHECK(esp_event_handler_instance_register(IP_EVENT, ESP_EVENT_ANY_ID,
                                                       &wifi_event_handler, NULL, NULL));
    
    ESP_LOGI(TAG, "Setting WiFi to station mode");
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_start());
    
    ret = config_manager_get_str("wifi_ssid", ctx->ssid, sizeof(ctx->ssid));
    if (ret == ESP_OK) {
        ret = config_manager_get_str("wifi_password", ctx->password, sizeof(ctx->password));
        if (ret == ESP_OK) {
            ESP_LOGI(TAG, "Found saved WiFi credentials for SSID: %s", ctx->ssid);
            ctx->credentials_saved = true;
        } else {
            ctx->credentials_saved = false;
        }
    } else {
        ctx->credentials_saved = false;
    }
    
    memset(&ctx->scan_config, 0, sizeof(ctx->scan_config));
    ctx->scan_config.show_hidden = true;
    ctx->scan_config.scan_type = WIFI_SCAN_TYPE_ACTIVE;
    ctx->scan_config.scan_time.active.min = 100;
    ctx->scan_config.scan_time.active.max = 300;
    
    ctx->state = WIFI_MANAGER_STATE_DISCONNECTED;
    safe_wifi_set_bits(ctx, WIFI_DISCONNECTED_BIT);
    
    ESP_LOGI(TAG, "WiFi Manager initialized successfully");
    return ESP_OK;
}

esp_err_t wifi_manager_connect(const char *ssid, const char *password, bool save)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL || ctx->connection_mutex == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ssid == NULL) {
        ESP_LOGE(TAG, "SSID is NULL");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (strlen(ssid) > 32) {
        ESP_LOGE(TAG, "SSID too long");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (password != NULL && strlen(password) > 64) {
        ESP_LOGE(TAG, "Password too long");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (xSemaphoreTake(ctx->connection_mutex, pdMS_TO_TICKS(5000)) != pdTRUE) {
        ESP_LOGE(TAG, "Could not acquire connection mutex");
        return ESP_ERR_TIMEOUT;
    }
    
    ESP_LOGI(TAG, "Connecting to WiFi network: %s", ssid);
    
    esp_err_t disconnect_ret = esp_wifi_disconnect();
    if (disconnect_ret != ESP_OK && disconnect_ret != ESP_ERR_WIFI_NOT_CONNECT) {
        ESP_LOGW(TAG, "Disconnect failed: %s", esp_err_to_name(disconnect_ret));
    }
    
    ctx->connection_in_progress = false;
    
    vTaskDelay(pdMS_TO_TICKS(500));
    
    strncpy(ctx->ssid, ssid, sizeof(ctx->ssid) - 1);
    ctx->ssid[sizeof(ctx->ssid) - 1] = '\0';
    
    if (password != NULL) {
        strncpy(ctx->password, password, sizeof(ctx->password) - 1);
        ctx->password[sizeof(ctx->password) - 1] = '\0';
    } else {
        ctx->password[0] = '\0';
    }
    
    if (save) {
        ESP_LOGI(TAG, "Saving WiFi credentials");
        ESP_ERROR_CHECK(config_manager_set_str("wifi_ssid", ctx->ssid));
        ESP_ERROR_CHECK(config_manager_set_str("wifi_password", ctx->password));
        ctx->credentials_saved = true;
    }
    
    wifi_config_t wifi_config = {0};
    strncpy((char *)wifi_config.sta.ssid, ctx->ssid, sizeof(wifi_config.sta.ssid) - 1);
    if (ctx->password[0] != '\0') {
        strncpy((char *)wifi_config.sta.password, ctx->password, sizeof(wifi_config.sta.password) - 1);
    }
    
    wifi_config.sta.scan_method = WIFI_FAST_SCAN;
    wifi_config.sta.sort_method = WIFI_CONNECT_AP_BY_SIGNAL;
    wifi_config.sta.threshold.rssi = -127;
    wifi_config.sta.threshold.authmode = WIFI_AUTH_WPA2_PSK;
    
    wifi_mode_t current_mode;
    ESP_ERROR_CHECK(esp_wifi_get_mode(&current_mode));
    
    if (current_mode == WIFI_MODE_AP) {
        ESP_LOGI(TAG, "Changing from AP to STA+AP mode");
        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_APSTA));
        ctx->state = WIFI_MANAGER_STATE_STA_AP_MODE;
    } else if (current_mode == WIFI_MODE_APSTA) {
        ESP_LOGI(TAG, "Updating STA configuration in STA+AP mode");
    } else {
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
    
    esp_err_t connect_result = _internal_wifi_connect_safe();
    
    xSemaphoreGive(ctx->connection_mutex);
    
    if (connect_result != ESP_OK) {
        ESP_LOGW(TAG, "Initial connection attempt failed: %s", esp_err_to_name(connect_result));
    }
    
    EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                          WIFI_CONNECTED_BIT | WIFI_CONNECT_FAIL_BIT,
                                          pdFALSE, pdFALSE, pdMS_TO_TICKS(WIFI_CONNECT_TIMEOUT_MS));
    
    if (bits & WIFI_CONNECTED_BIT) {
        ESP_LOGI(TAG, "Successfully connected to WiFi network");
        return ESP_OK;
    } else if (bits & WIFI_CONNECT_FAIL_BIT) {
        ESP_LOGE(TAG, "Failed to connect to WiFi network");
        if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
            ctx->state = WIFI_MANAGER_STATE_ERROR;
            if (ctx->state_callback) {
                ctx->state_callback(ctx->state, ctx->user_data);
            }
        }
        return ESP_FAIL;
    } else {
        ESP_LOGW(TAG, "Connection attempt timed out");
        if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
            ctx->state = WIFI_MANAGER_STATE_ERROR;
            if (ctx->state_callback) {
                ctx->state_callback(ctx->state, ctx->user_data);
            }
        }
        return ESP_ERR_TIMEOUT;
    }
}

esp_err_t wifi_manager_connect_saved(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (!ctx->credentials_saved) {
        ESP_LOGW(TAG, "No saved WiFi credentials");
        return ESP_ERR_NOT_FOUND;
    }
    
    ESP_LOGI(TAG, "Connecting with saved credentials, SSID: %s", ctx->ssid);
    return wifi_manager_connect(ctx->ssid, ctx->password, false);
}

esp_err_t wifi_manager_disconnect(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ctx->state != WIFI_MANAGER_STATE_CONNECTED && 
        ctx->state != WIFI_MANAGER_STATE_CONNECTING &&
        ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
        ESP_LOGW(TAG, "Not connected to WiFi");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Disconnecting from WiFi network: %s", ctx->ssid);
    ESP_ERROR_CHECK(esp_wifi_disconnect());
    
    if (ctx->state == WIFI_MANAGER_STATE_STA_AP_MODE) {
        ESP_LOGI(TAG, "Remaining in AP mode after STA disconnection");
        ctx->state = WIFI_MANAGER_STATE_AP_MODE;
        if (ctx->state_callback) {
            ctx->state_callback(ctx->state, ctx->user_data);
        }
        return ESP_OK;
    }
    
    EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                          WIFI_DISCONNECTED_BIT,
                                          pdFALSE, pdFALSE, pdMS_TO_TICKS(5000));
    
    if (bits & WIFI_DISCONNECTED_BIT) {
        ESP_LOGI(TAG, "Successfully disconnected from WiFi");
        return ESP_OK;
    } else {
        ESP_LOGW(TAG, "Timeout waiting for disconnection");
        return ESP_ERR_TIMEOUT;
    }
}

esp_err_t wifi_manager_start_ap_mode(const char *ap_ssid, const char *ap_password)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ap_ssid == NULL) {
        ESP_LOGE(TAG, "AP SSID is NULL");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (strlen(ap_ssid) > 32) {
        ESP_LOGE(TAG, "AP SSID too long");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (ap_password != NULL && strlen(ap_password) > 64) {
        ESP_LOGE(TAG, "AP Password too long");
        return ESP_ERR_INVALID_ARG;
    }
    
    ESP_LOGI(TAG, "Starting WiFi Access Point: %s", ap_ssid);
    
    if (ctx->state == WIFI_MANAGER_STATE_CONNECTED || 
        ctx->state == WIFI_MANAGER_STATE_CONNECTING) {
        ESP_LOGI(TAG, "Disconnecting from current network before starting AP");
        ESP_ERROR_CHECK(esp_wifi_disconnect());
        
        EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                              WIFI_DISCONNECTED_BIT,
                                              pdFALSE, pdFALSE, pdMS_TO_TICKS(5000));
        if ((bits & WIFI_DISCONNECTED_BIT) == 0) {
            ESP_LOGW(TAG, "Timeout waiting for disconnection");
        }
    }
    
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
    
    EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                          WIFI_AP_STARTED_BIT,
                                          pdFALSE, pdFALSE, pdMS_TO_TICKS(5000));
    
    if (bits & WIFI_AP_STARTED_BIT) {
        ESP_LOGI(TAG, "WiFi Access Point started successfully");
        return ESP_OK;
    } else {
        ESP_LOGW(TAG, "Timeout waiting for AP to start");
        return ESP_ERR_TIMEOUT;
    }
}

esp_err_t wifi_manager_start_sta_ap_mode(const char *ap_ssid, const char *ap_password)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ap_ssid == NULL) {
        ESP_LOGE(TAG, "AP SSID is NULL");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (strlen(ap_ssid) > 32) {
        ESP_LOGE(TAG, "AP SSID too long");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (ap_password != NULL && strlen(ap_password) > 64) {
        ESP_LOGE(TAG, "AP Password too long");
        return ESP_ERR_INVALID_ARG;
    }
    
    ESP_LOGI(TAG, "Starting WiFi STA+AP mode with AP: %s", ap_ssid);
    
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
    
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_APSTA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &ap_config));
    
    EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                          WIFI_AP_STARTED_BIT,
                                          pdFALSE, pdFALSE, pdMS_TO_TICKS(5000));
    
    if (bits & WIFI_AP_STARTED_BIT) {
        ctx->state = WIFI_MANAGER_STATE_STA_AP_MODE;
        if (ctx->state_callback) {
            ctx->state_callback(ctx->state, ctx->user_data);
        }
        ESP_LOGI(TAG, "WiFi STA+AP mode started successfully");
        return ESP_OK;
    } else {
        ESP_LOGW(TAG, "Timeout waiting for AP to start in STA+AP mode");
        return ESP_ERR_TIMEOUT;
    }
}

esp_err_t wifi_manager_stop_ap_mode(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ctx->state != WIFI_MANAGER_STATE_AP_MODE && 
        ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
        ESP_LOGW(TAG, "Not in AP or STA+AP mode");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Stopping WiFi Access Point");
    
    if (ctx->state == WIFI_MANAGER_STATE_STA_AP_MODE && 
        (xEventGroupGetBits(ctx->event_group) & WIFI_CONNECTED_BIT)) {
        ESP_LOGI(TAG, "Switching from STA+AP to STA mode");
        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
        ctx->state = WIFI_MANAGER_STATE_CONNECTED;
    } else {
        ESP_LOGI(TAG, "Switching to STA mode (disconnected)");
        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
        ctx->state = WIFI_MANAGER_STATE_DISCONNECTED;
    }
    
    if (ctx->state_callback) {
        ctx->state_callback(ctx->state, ctx->user_data);
    }
    
    return ESP_OK;
}

esp_err_t wifi_manager_set_sta_mode(void) {
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    ESP_LOGI(TAG, "Switching to station-only mode");
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    wifi_mode_t current_mode;
    esp_err_t ret = esp_wifi_get_mode(&current_mode);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to get WiFi mode: %s", esp_err_to_name(ret));
        return ret;
    }
    
    if (current_mode == WIFI_MODE_STA) {
        ESP_LOGI(TAG, "Already in STA mode");
        return ESP_OK;
    }
    
    if (current_mode == WIFI_MODE_APSTA) {
        ret = esp_wifi_set_mode(WIFI_MODE_STA);
        if (ret != ESP_OK) {
            ESP_LOGE(TAG, "Failed to set WiFi mode: %s", esp_err_to_name(ret));
            return ret;
        }
        
        if (wifi_manager_is_connected()) {
            ctx->state = WIFI_MANAGER_STATE_CONNECTED;
        } else {
            ctx->state = WIFI_MANAGER_STATE_DISCONNECTED;
        }
        
        if (ctx->state_callback) {
            ctx->state_callback(ctx->state, ctx->user_data);
        }
    }
    
    return ESP_OK;
}

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
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ip == NULL || max_len < 16) {
        ESP_LOGE(TAG, "Invalid buffer for IP address");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (!wifi_manager_is_connected()) {
        ESP_LOGW(TAG, "Not connected to WiFi");
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
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ip == NULL || max_len < 16) {
        ESP_LOGE(TAG, "Invalid buffer for IP address");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (ctx->state != WIFI_MANAGER_STATE_AP_MODE && 
        ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
        ESP_LOGW(TAG, "Not in AP or STA+AP mode");
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
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (rssi == NULL) {
        ESP_LOGE(TAG, "RSSI pointer is NULL");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (!wifi_manager_is_connected()) {
        ESP_LOGW(TAG, "Not connected to WiFi");
        *rssi = -127;
        return ESP_ERR_INVALID_STATE;
    }
    
    if (esp_wifi_sta_get_ap_info(&ctx->ap_info) != ESP_OK) {
        ESP_LOGW(TAG, "Failed to get AP info");
        *rssi = -127;
        return ESP_FAIL;
    }
    
    *rssi = ctx->ap_info.rssi;
    return ESP_OK;
}

wifi_manager_state_t wifi_manager_get_state(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return WIFI_MANAGER_STATE_INIT;
    }
    
    return ctx->state;
}

esp_err_t wifi_manager_get_configured_ssid(char *ssid, size_t max_len)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ssid == NULL || max_len < 1) {
        ESP_LOGE(TAG, "Invalid buffer for SSID");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (!ctx->credentials_saved) {
        ESP_LOGW(TAG, "No saved WiFi credentials");
        ssid[0] = '\0';
        return ESP_ERR_NOT_FOUND;
    }
    
    strncpy(ssid, ctx->ssid, max_len - 1);
    ssid[max_len - 1] = '\0';
    
    return ESP_OK;
}

esp_err_t wifi_manager_forget_network(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Forgetting saved WiFi network");
    
    if (ctx->state == WIFI_MANAGER_STATE_CONNECTED || 
        ctx->state == WIFI_MANAGER_STATE_CONNECTING) {
        ESP_LOGI(TAG, "Disconnecting from current network");
        ESP_ERROR_CHECK(esp_wifi_disconnect());
        
        EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                              WIFI_DISCONNECTED_BIT,
                                              pdFALSE, pdFALSE, pdMS_TO_TICKS(5000));
        if ((bits & WIFI_DISCONNECTED_BIT) == 0) {
            ESP_LOGW(TAG, "Timeout waiting for disconnection");
        }
    }
    
    ESP_ERROR_CHECK(config_manager_erase_key("wifi_ssid"));
    ESP_ERROR_CHECK(config_manager_erase_key("wifi_password"));
    
    ctx->ssid[0] = '\0';
    ctx->password[0] = '\0';
    ctx->credentials_saved = false;
    
    if (ctx->state == WIFI_MANAGER_STATE_STA_AP_MODE) {
        ctx->state = WIFI_MANAGER_STATE_AP_MODE;
    } else {
        ctx->state = WIFI_MANAGER_STATE_DISCONNECTED;
    }
    
    if (ctx->state_callback) {
        ctx->state_callback(ctx->state, ctx->user_data);
    }
    
    return ESP_OK;
}

esp_err_t wifi_manager_start_scan(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Starting WiFi scan");
    
    if (ctx->scan_in_progress) {
        ESP_LOGW(TAG, "Scan already in progress");
        return ESP_ERR_INVALID_STATE;
    }
    
    wifi_mode_t current_mode;
    esp_err_t ret = esp_wifi_get_mode(&current_mode);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to get WiFi mode: %s", esp_err_to_name(ret));
        return ret;
    }
    
    if (current_mode == WIFI_MODE_AP) {
        ESP_LOGI(TAG, "Switching from AP to AP+STA mode for scanning");
        ret = esp_wifi_set_mode(WIFI_MODE_APSTA);
        if (ret != ESP_OK) {
            ESP_LOGE(TAG, "Failed to switch to AP+STA mode: %s", esp_err_to_name(ret));
            return ret;
        }
        
        ctx->temporary_apsta_mode = true;
        
        vTaskDelay(pdMS_TO_TICKS(500));
    } else {
        ctx->temporary_apsta_mode = false;
    }
    
    safe_wifi_clear_bits(ctx, WIFI_SCAN_DONE_BIT);
    
    ctx->scan_in_progress = true;
    
    ret = esp_wifi_scan_start(&ctx->scan_config, false);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to start WiFi scan: %s", esp_err_to_name(ret));
        ctx->scan_in_progress = false;
        
        if (ctx->temporary_apsta_mode) {
            ESP_LOGW(TAG, "Reverting to AP mode after scan failure");
            ctx->temporary_apsta_mode = false;
            esp_err_t revert_ret = esp_wifi_set_mode(WIFI_MODE_AP);
            if (revert_ret != ESP_OK) {
                ESP_LOGE(TAG, "Failed to revert to AP mode: %s", esp_err_to_name(revert_ret));
            }
        }
        
        return ret;
    }
    
    ESP_LOGI(TAG, "WiFi scan started successfully");
    return ESP_OK;
}

esp_err_t wifi_manager_get_scan_results(wifi_scan_result_t *results, size_t max_networks, size_t *num_networks)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (results == NULL || num_networks == NULL) {
        ESP_LOGE(TAG, "Invalid output parameters");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (ctx->scan_in_progress) {
        ESP_LOGI(TAG, "Waiting for scan to complete");
        
        EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                              WIFI_SCAN_DONE_BIT,
                                              pdFALSE, pdFALSE, pdMS_TO_TICKS(10000));
        
        if ((bits & WIFI_SCAN_DONE_BIT) == 0) {
            ESP_LOGW(TAG, "Timeout waiting for scan to complete");
            return ESP_ERR_TIMEOUT;
        }
    }
    
    if (ctx->scan_ap_list == NULL || ctx->scan_ap_count == 0) {
        ESP_LOGW(TAG, "No scan results available");
        *num_networks = 0;
        return ESP_ERR_NOT_FOUND;
    }
    
    size_t count = (ctx->scan_ap_count < max_networks) ? ctx->scan_ap_count : max_networks;
    for (size_t i = 0; i < count; i++) {
        strncpy(results[i].ssid, (char *)ctx->scan_ap_list[i].ssid, sizeof(results[i].ssid) - 1);
        results[i].ssid[sizeof(results[i].ssid) - 1] = '\0';
        results[i].rssi = ctx->scan_ap_list[i].rssi;
        results[i].auth_mode = ctx->scan_ap_list[i].authmode;
    }
    
    *num_networks = count;
    ESP_LOGI(TAG, "Returning %zu scan results", count);
    return ESP_OK;
}

esp_err_t wifi_manager_set_state_callback(void (*callback)(wifi_manager_state_t state, void *user_data), void *user_data)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ctx->state_callback = callback;
    ctx->user_data = user_data;
    
    return ESP_OK;
}

esp_err_t wifi_manager_handle_disconnection(const char *ap_ssid_prefix, const char *ap_password, int max_attempts) {
    static int reconnect_attempts = 0;
    static int recovery_cycle = 0;
    static int64_t last_reconnect_time = 0;
    static bool ap_mode_active = false;
    static int netif_cleanup_cycle = 0;
    int64_t current_time = esp_timer_get_time() / 1000;
    
    const int64_t RECONNECT_INTERVAL_MS = 8000;
    const int NETIF_CLEANUP_THRESHOLD = 50;
    const int ATTEMPTS_BEFORE_AP = 5;
    
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (wifi_manager_is_connected()) {
        reconnect_attempts = 0;
        recovery_cycle = 0;
        ap_mode_active = false;
        return ESP_OK;
    }
    
    if (current_time - last_reconnect_time > RECONNECT_INTERVAL_MS) {
        last_reconnect_time = current_time;
        
        if (!ctx->credentials_saved) {
            if (!ap_mode_active) {
                char ap_ssid[33];
                esp_err_t ap_ret = wifi_manager_generate_ap_ssid(ap_ssid, sizeof(ap_ssid), ap_ssid_prefix);
                if (ap_ret != ESP_OK) {
                    ESP_LOGE(TAG, "Failed to generate AP SSID");
                    return ESP_FAIL;
                }
                
                ESP_LOGI(TAG, "No credentials - starting AP+STA with SSID: %s", ap_ssid);
                
                ap_ret = wifi_manager_start_sta_ap_mode(ap_ssid, ap_password);
                if (ap_ret == ESP_OK) {
                    ap_mode_active = true;
                    reconnect_attempts = 0;
                } else {
                    ESP_LOGE(TAG, "Failed to start AP+STA mode");
                }
            }
            return ESP_OK;
        }
        
        reconnect_attempts++;
        netif_cleanup_cycle++;
        
        if (netif_cleanup_cycle >= NETIF_CLEANUP_THRESHOLD) {
            ESP_LOGI(TAG, "Performing netif cleanup after %d reconnections", netif_cleanup_cycle);
            
            esp_wifi_stop();
            vTaskDelay(pdMS_TO_TICKS(500));
            
            if (cleanup_and_recreate_netifs(ctx) == ESP_OK) {
                ESP_LOGI(TAG, "Netifs recreated successfully");
                netif_cleanup_cycle = 0;
            } else {
                ESP_LOGE(TAG, "Failed to recreate netifs");
            }
            
            esp_wifi_start();
            vTaskDelay(pdMS_TO_TICKS(1000));
        }
        
        ESP_LOGI(TAG, "WiFi recovery cycle %d, reconnection attempt %d (cleanup cycle: %d)", 
                 recovery_cycle + 1, reconnect_attempts, netif_cleanup_cycle);
        
        if (reconnect_attempts % 5 == 0 && netif_cleanup_cycle < NETIF_CLEANUP_THRESHOLD) {
            esp_wifi_stop();
            vTaskDelay(pdMS_TO_TICKS(1000));
            esp_wifi_start();
            vTaskDelay(pdMS_TO_TICKS(2000));
        }
        
        if (!ap_mode_active && reconnect_attempts >= ATTEMPTS_BEFORE_AP) {
            char ap_ssid[33];
            esp_err_t ap_ret = wifi_manager_generate_ap_ssid(ap_ssid, sizeof(ap_ssid), ap_ssid_prefix);
            if (ap_ret != ESP_OK) {
                ESP_LOGE(TAG, "Failed to generate AP SSID");
                return ESP_FAIL;
            }
            
            ESP_LOGI(TAG, "Starting AP+STA with SSID: %s while continuing reconnection attempts", ap_ssid);
            
            ap_ret = wifi_manager_start_sta_ap_mode(ap_ssid, ap_password);
            if (ap_ret == ESP_OK) {
                ap_mode_active = true;
            } else {
                ESP_LOGE(TAG, "Failed to start AP+STA mode");
            }
        }
        
        esp_err_t ret = wifi_manager_connect_saved();
        if (ret != ESP_OK) {
            ESP_LOGW(TAG, "Connection attempt failed: %s", esp_err_to_name(ret));
        }
    }
    
    return ESP_OK;
}

esp_err_t wifi_manager_generate_ap_ssid(char *ap_ssid, size_t max_len, const char *prefix)
{
    if (ap_ssid == NULL || max_len < 8 || prefix == NULL) {
        ESP_LOGE(TAG, "Invalid parameters for AP SSID generation");
        return ESP_ERR_INVALID_ARG;
    }
    
    char esp32_id[ESP32_ID_LENGTH + 1];
    esp_err_t ret = esp32_id_manager_get_id(esp32_id, sizeof(esp32_id));
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to get ESP32 ID");
        return ret;
    }
    
    size_t id_len = strlen(esp32_id);
    size_t prefix_len = strlen(prefix);
    
    if (id_len < 6 || prefix_len + 7 > max_len) {
        ESP_LOGE(TAG, "ID too short or buffer too small");
        return ESP_ERR_INVALID_ARG;
    }
    
    snprintf(ap_ssid, max_len, "%s_%s", prefix, &esp32_id[id_len - 6]);
    
    ESP_LOGI(TAG, "Generated AP SSID: %s", ap_ssid);
    return ESP_OK;
}