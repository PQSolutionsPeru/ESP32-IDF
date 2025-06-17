#ifndef RELAY_MANAGER_H
#define RELAY_MANAGER_H

#include <stdbool.h>
#include "esp_err.h"
#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"

#ifdef __cplusplus
extern "C" {
#endif

#define RELAY_MANAGER_MAX_RELAYS 6
#define RELAY_MANAGER_NAME_MAX_LENGTH 32
#define RELAY_MANAGER_ID_MAX_LENGTH 16

#define RELAY_MANAGER_DEBOUNCE_TIME_MS 30
#define RELAY_MANAGER_MIN_REPORT_INTERVAL_MS 500
#define RELAY_MANAGER_STABLE_READINGS 8
#define RELAY_MANAGER_READING_DELAY_MS 3
#define RELAY_MANAGER_FAST_READINGS 3
#define RELAY_MANAGER_AUTO_CHECK_INTERVAL_MS 1000

typedef enum {
    RELAY_STATE_OK = 0,
    RELAY_STATE_DISC,
    RELAY_STATE_ERROR
} relay_state_t;

typedef enum {
    RELAY_CONTACT_NO = 0,
    RELAY_CONTACT_NC
} relay_contact_type_t;

typedef struct {
    gpio_num_t gpio_pin;
    char relay_id[RELAY_MANAGER_ID_MAX_LENGTH];
    char name[RELAY_MANAGER_NAME_MAX_LENGTH];
    relay_contact_type_t contact_type;
    bool is_active;
    relay_state_t current_state;
    int64_t last_change_time;
    int64_t last_report_time;
} relay_config_t;

typedef struct {
    gpio_num_t gpio_pin;
    relay_state_t old_state;
    relay_state_t new_state;
    int64_t timestamp;
    char relay_id[RELAY_MANAGER_ID_MAX_LENGTH];
    char name[RELAY_MANAGER_NAME_MAX_LENGTH];
    relay_contact_type_t contact_type;
} relay_event_t;

typedef void (*relay_state_change_callback_t)(const relay_event_t *event, void *user_data);

typedef esp_err_t (*relay_mqtt_command_callback_t)(const char *topic, const char *command_json, void *user_data);

typedef enum {
    RELAY_MGR_STATE_UNINITIALIZED = 0,
    RELAY_MGR_STATE_INITIALIZING,
    RELAY_MGR_STATE_RUNNING,
    RELAY_MGR_STATE_DEINITIALIZING
} relay_mgr_state_t;

relay_mgr_state_t relay_manager_get_mgr_state(void);

esp_err_t relay_manager_init(void);

esp_err_t relay_manager_set_state_callback(relay_state_change_callback_t callback, void *user_data);

esp_err_t relay_manager_set_mqtt_callback(relay_mqtt_command_callback_t callback, void *user_data);

esp_err_t relay_manager_get_state(const char *relay_id, relay_state_t *state);

esp_err_t relay_manager_get_all_states_json(char *json_buffer, size_t buffer_size);

esp_err_t relay_manager_set_name(const char *relay_id, const char *name);

esp_err_t relay_manager_set_active(const char *relay_id, bool active);

esp_err_t relay_manager_set_contact_type(const char *relay_id, relay_contact_type_t contact_type);

esp_err_t relay_manager_process_mqtt_command(const char *command_json);

esp_err_t relay_manager_get_config_json(char *json_buffer, size_t buffer_size);

esp_err_t relay_manager_check_all_states(bool force_report);

esp_err_t relay_manager_get_diagnostics_json(char *json_buffer, size_t buffer_size);

esp_err_t relay_manager_deinit(void);

int relay_manager_read_stable_gpio(gpio_num_t gpio_pin);

#ifdef __cplusplus
}
#endif

#endif