/**
 * @file health_monitor.h
 * @brief Independent health monitoring system for ESP32
 *
 * This component runs as an independent high-priority task that monitors
 * system health and reports critical failures to the server via MQTT.
 * It operates independently from the main application task to ensure
 * reliability even during system crashes or hangs.
 *
 * OPTIMIZED VERSION: Low memory footprint (~3.5KB total)
 * - Reduced stack size: 2.5KB (vs 4KB)
 * - Check interval: 10s (vs 5s)
 * - Simplified checks for memory-constrained devices
 */

#ifndef HEALTH_MONITOR_H
#define HEALTH_MONITOR_H

#include <stdbool.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief Health status severity levels
 */
typedef enum {
    HEALTH_STATUS_OK = 0,
    HEALTH_STATUS_WARNING,
    HEALTH_STATUS_CRITICAL,
    HEALTH_STATUS_FATAL,
    HEALTH_STATUS_HARDWARE_FAULT  // Hardware replacement required
} health_status_t;

/**
 * @brief Recovery strategies
 */
typedef enum {
    RECOVERY_NONE = 0,
    RECOVERY_MEMORY_CLEANUP,
    RECOVERY_SOFT_RESTART,
    RECOVERY_SAFE_MODE,
    RECOVERY_FACTORY_RESET,
    RECOVERY_SURVIVAL_MODE
} recovery_strategy_t;

/**
 * @brief Health issue types
 */
typedef enum {
    HEALTH_ISSUE_NONE = 0,
    HEALTH_ISSUE_BOOT_LOOP,
    HEALTH_ISSUE_MEMORY_LOW,
    HEALTH_ISSUE_MEMORY_LEAK,
    HEALTH_ISSUE_TASK_STARVATION,
    HEALTH_ISSUE_WATCHDOG_TIMEOUT,
    HEALTH_ISSUE_MQTT_DISCONNECTED,
    HEALTH_ISSUE_WIFI_DISCONNECTED,
    HEALTH_ISSUE_STACK_OVERFLOW,
    HEALTH_ISSUE_HEAP_CORRUPTION,
    HEALTH_ISSUE_UNEXPECTED_REBOOT
} health_issue_type_t;

/**
 * @brief Health report structure
 */
typedef struct {
    health_status_t status;
    health_issue_type_t issue_type;
    uint32_t uptime_ms;
    uint32_t free_heap;
    uint32_t min_free_heap;
    uint32_t boot_count;
    char issue_description[256];
    char esp32_id[32];
} health_report_t;

/**
 * @brief Initialize the health monitor
 *
 * Creates an independent monitoring task on Core 1 with high priority.
 * This task monitors system health and sends alerts when issues are detected.
 *
 * @param esp32_id The ESP32 device ID for MQTT reporting
 * @return ESP_OK on success, error code otherwise
 */
esp_err_t health_monitor_init(const char *esp32_id);

/**
 * @brief Stop the health monitor
 *
 * @return ESP_OK on success
 */
esp_err_t health_monitor_stop(void);

/**
 * @brief Report a health issue manually
 *
 * Allows other components to report health issues directly.
 *
 * @param issue_type Type of issue
 * @param description Human-readable description
 * @return ESP_OK on success
 */
esp_err_t health_monitor_report_issue(health_issue_type_t issue_type, const char *description);

/**
 * @brief Get current health status
 *
 * @param report Output pointer for health report
 * @return ESP_OK on success
 */
esp_err_t health_monitor_get_status(health_report_t *report);

/**
 * @brief Check if system is healthy
 *
 * @return true if system is healthy, false otherwise
 */
bool health_monitor_is_healthy(void);

/**
 * @brief Trigger immediate health check and report
 *
 * Forces an immediate health check and sends report to server.
 *
 * @return ESP_OK on success
 */
esp_err_t health_monitor_check_now(void);

#ifdef __cplusplus
}
#endif

#endif // HEALTH_MONITOR_H
