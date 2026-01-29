#ifndef WIFI_MANAGER_H
#define WIFI_MANAGER_H

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"
#include "esp_wifi_types.h"
#include "esp_wifi.h"
#include "esp_log.h"

typedef enum {
    WIFI_MANAGER_STATE_INIT,           
    WIFI_MANAGER_STATE_DISCONNECTED,   
    WIFI_MANAGER_STATE_CONNECTING,     
    WIFI_MANAGER_STATE_CONNECTED,      
    WIFI_MANAGER_STATE_AP_MODE,        
    WIFI_MANAGER_STATE_STA_AP_MODE,    
    WIFI_MANAGER_STATE_ERROR           
} wifi_manager_state_t;

typedef struct {
    char ssid[33];
    int8_t rssi;
    wifi_auth_mode_t auth_mode;
} wifi_scan_result_t;

typedef enum {
    WIFI_CONNECTIVITY_WIFI_CONNECTED,
    WIFI_CONNECTIVITY_WIFI_DISCONNECTED,
    WIFI_CONNECTIVITY_INTERNET_AVAILABLE,
    WIFI_CONNECTIVITY_INTERNET_LOST
} wifi_connectivity_event_t;

typedef void (*wifi_connectivity_callback_t)(wifi_connectivity_event_t event, const char *ssid, void *user_data);

esp_err_t wifi_manager_init(void);
esp_err_t wifi_manager_connect(const char *ssid, const char *password, bool save);
esp_err_t wifi_manager_connect_saved(void);
esp_err_t wifi_manager_disconnect(void);
esp_err_t wifi_manager_start_ap_mode(const char *ap_ssid, const char *ap_password);
esp_err_t wifi_manager_start_sta_ap_mode(const char *ap_ssid, const char *ap_password);
esp_err_t wifi_manager_stop_ap_mode(void);
bool wifi_manager_is_connected(void);
esp_err_t wifi_manager_get_ip(char *ip, size_t max_len);
esp_err_t wifi_manager_get_ap_ip(char *ip, size_t max_len);
esp_err_t wifi_manager_get_rssi(int8_t *rssi);
wifi_manager_state_t wifi_manager_get_state(void);
esp_err_t wifi_manager_get_configured_ssid(char *ssid, size_t max_len);
esp_err_t wifi_manager_forget_network(void);
esp_err_t wifi_manager_start_scan(void);
esp_err_t wifi_manager_get_scan_results(wifi_scan_result_t *results, size_t max_networks, size_t *num_networks);
esp_err_t wifi_manager_set_state_callback(void (*callback)(wifi_manager_state_t state, void *user_data), void *user_data);
esp_err_t wifi_manager_handle_disconnection(const char *ap_ssid_prefix, const char *ap_password, int max_attempts);
esp_err_t wifi_manager_set_sta_mode(void);
esp_err_t wifi_manager_generate_ap_ssid(char *ap_ssid, size_t max_len, const char *prefix);
esp_err_t wifi_manager_check_internet_connectivity(bool *has_internet);
esp_err_t wifi_manager_set_connectivity_callback(wifi_connectivity_callback_t callback, void *user_data);

#endif /* WIFI_MANAGER_H */