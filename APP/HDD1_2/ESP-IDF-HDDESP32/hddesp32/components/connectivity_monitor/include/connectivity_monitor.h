#ifndef CONNECTIVITY_MONITOR_H
#define CONNECTIVITY_MONITOR_H

#include "esp_err.h"
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CONNECTIVITY_STABILIZATION_TIME_MS 15000
#define CONNECTIVITY_CHECK_INTERVAL_MS 5000
#define CONNECTIVITY_MAX_STORED_EVENTS 10

typedef enum {
    CONNECTIVITY_STATE_INIT = 0,
    CONNECTIVITY_STATE_STABILIZING,
    CONNECTIVITY_STATE_MONITORING,
    CONNECTIVITY_STATE_ERROR
} connectivity_monitor_state_t;

typedef enum {
    CONNECTIVITY_EVENT_WIFI_LOST = 0,
    CONNECTIVITY_EVENT_WIFI_RECOVERED,
    CONNECTIVITY_EVENT_INTERNET_LOST,
    CONNECTIVITY_EVENT_INTERNET_RECOVERED,
    CONNECTIVITY_EVENT_MQTT_LOST,
    CONNECTIVITY_EVENT_MQTT_RECOVERED
} connectivity_event_type_t;

typedef struct {
    bool wifi_connected;
    bool internet_available;
    bool mqtt_connected;
    int64_t wifi_lost_time;
    int64_t internet_lost_time;
    int64_t mqtt_lost_time;
    int64_t last_check_time;
    char current_ssid[33];
} connectivity_status_t;

typedef struct {
    connectivity_event_type_t type;
    int64_t timestamp_ms;
    char ssid[33];
    char panel_name[64];
    bool sent;
} connectivity_event_t;

typedef void (*connectivity_event_callback_t)(const connectivity_event_t *event, void *user_data);

esp_err_t connectivity_monitor_init(void);

esp_err_t connectivity_monitor_deinit(void);

esp_err_t connectivity_monitor_start(void);

esp_err_t connectivity_monitor_stop(void);

esp_err_t connectivity_monitor_set_event_callback(connectivity_event_callback_t callback, void *user_data);

esp_err_t connectivity_monitor_force_check(void);

esp_err_t connectivity_monitor_get_status(connectivity_status_t *status);

connectivity_monitor_state_t connectivity_monitor_get_state(void);

esp_err_t connectivity_monitor_get_pending_events(connectivity_event_t *events, size_t max_events, size_t *event_count);

esp_err_t connectivity_monitor_mark_events_sent(void);

esp_err_t connectivity_monitor_clear_events(void);

bool connectivity_monitor_has_pending_events(void);

esp_err_t connectivity_monitor_check_internet_connectivity(bool *has_internet);

esp_err_t connectivity_monitor_process_pending_events(void);

bool connectivity_monitor_has_pending_ram_events(void);

int connectivity_monitor_get_pending_event_count(void);

esp_err_t connectivity_monitor_get_ram_usage_stats(size_t *total_events, size_t *pending_events, size_t *memory_used);

void connectivity_monitor_debug_dump_events(void);

esp_err_t connectivity_monitor_report_mqtt_status(bool mqtt_connected);

#ifdef __cplusplus
}

#endif

#endif