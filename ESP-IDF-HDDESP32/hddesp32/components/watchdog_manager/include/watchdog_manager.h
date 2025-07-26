#ifndef WATCHDOG_MANAGER_H
#define WATCHDOG_MANAGER_H

#include <stdbool.h>
#include "esp_err.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

typedef enum {
    WATCHDOG_MODE_CONFIG,
    WATCHDOG_MODE_RUNNING,
    WATCHDOG_MODE_CRITICAL,
    WATCHDOG_MODE_EMERGENCY
} watchdog_mode_t;

typedef enum {
    WATCHDOG_HEALTH_GOOD,
    WATCHDOG_HEALTH_WARNING,
    WATCHDOG_HEALTH_CRITICAL,
    WATCHDOG_HEALTH_ERROR,
    WATCHDOG_HEALTH_EMERGENCY
} watchdog_health_status_t;

typedef enum {
    WATCHDOG_CHECK_MEMORY,
    WATCHDOG_CHECK_WIFI,
    WATCHDOG_CHECK_MQTT,
    WATCHDOG_CHECK_TASKS,
    WATCHDOG_CHECK_SYSTEM
} watchdog_check_type_t;

typedef struct {
    uint32_t timeout_ms;
    uint32_t feed_interval_ms;
    uint32_t health_check_interval_ms;
    uint32_t memory_threshold_bytes;
    uint32_t critical_memory_threshold_bytes;
    uint32_t emergency_memory_threshold_bytes;
    bool enable_memory_check;
    bool enable_wifi_check;
    bool enable_mqtt_check;
    bool enable_task_monitoring;
    bool enable_auto_recovery;
} watchdog_config_t;

typedef struct {
    watchdog_health_status_t status;
    watchdog_check_type_t check_type;
    uint32_t current_memory;
    uint32_t min_memory;
    uint32_t feed_failures;
    int64_t last_feed_time;
    const char *additional_info;
} watchdog_event_context_t;

typedef void (*watchdog_event_callback_t)(const watchdog_event_context_t *context, void *user_data);

typedef struct {
    uint32_t total_feeds;
    uint32_t feed_failures;
    uint32_t health_checks;
    uint32_t emergency_recoveries;
    uint32_t mode_changes;
    int64_t last_emergency_time;
    int64_t uptime_ms;
    const char *last_reset_reason;
} watchdog_statistics_t;

esp_err_t watchdog_manager_init(void);
esp_err_t watchdog_manager_deinit(void);
esp_err_t watchdog_manager_set_mode(watchdog_mode_t mode);
watchdog_mode_t watchdog_manager_get_mode(void);
esp_err_t watchdog_manager_register_task(TaskHandle_t task_handle, const char *task_name);
esp_err_t watchdog_manager_unregister_task(TaskHandle_t task_handle);
esp_err_t watchdog_manager_feed(void);
esp_err_t watchdog_manager_feed_with_context(const char *context);
esp_err_t watchdog_manager_report_activity(watchdog_check_type_t check_type);
void watchdog_manager_force_reset(const char *reason) __attribute__((noreturn));
watchdog_health_status_t watchdog_manager_get_health_status(void);
esp_err_t watchdog_manager_get_stats(uint32_t *feed_count, uint32_t *error_count, const char **last_reset_reason);
esp_err_t watchdog_manager_get_detailed_stats(watchdog_statistics_t *stats);
esp_err_t watchdog_manager_set_event_callback(watchdog_event_callback_t callback, void *user_data);
watchdog_health_status_t watchdog_manager_check_system_health(void);
esp_err_t watchdog_manager_emergency_recovery(const char *reason);
esp_err_t watchdog_manager_set_config(watchdog_mode_t mode, const watchdog_config_t *config);
esp_err_t watchdog_manager_get_config(watchdog_mode_t mode, watchdog_config_t *config);
esp_err_t watchdog_manager_get_memory_stats(uint32_t *current_free, uint32_t *min_free, uint32_t *largest_block);
bool watchdog_manager_is_system_under_stress(void);

#endif /* WATCHDOG_MANAGER_H */