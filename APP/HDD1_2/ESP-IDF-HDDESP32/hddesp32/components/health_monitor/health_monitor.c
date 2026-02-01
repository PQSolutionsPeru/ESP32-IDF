/**
 * @file health_monitor.c
 * @brief Implementation of independent health monitoring system
 */

#include "health_monitor.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_heap_caps.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "mqtt_manager.h"
#include "config_manager.h"
#include "log_uploader.h"
#include <string.h>
#include <stdio.h>

static const char *TAG = "HEALTH_MON";

// Configuration - OPTIMIZED FOR LOW MEMORY
#define HEALTH_MONITOR_TASK_STACK_SIZE (2560)  // Reduced from 4096 (saves 1.5KB)
#define HEALTH_MONITOR_TASK_PRIORITY (configMAX_PRIORITIES - 2)  // High priority
#define HEALTH_MONITOR_CORE_ID (1)  // Run on Core 1
#define HEALTH_MONITOR_CHECK_INTERVAL_MS (10000)  // Check every 10 seconds (reduced frequency)

// Thresholds - OPTIMIZED
#define MEMORY_LOW_THRESHOLD (40 * 1024)  // 40KB (adjusted for your device)
#define MEMORY_CRITICAL_THRESHOLD (30 * 1024)  // 30KB
#define MEMORY_LEAK_THRESHOLD (15 * 1024)  // 15KB decrease per check (less sensitive)
#define BOOT_LOOP_THRESHOLD (20)

// State
static TaskHandle_t health_task_handle = NULL;
static SemaphoreHandle_t health_mutex = NULL;
static bool monitor_running = false;
static health_report_t current_health = {0};
static char device_id[32] = {0};
static uint32_t last_free_heap = 0;
static uint32_t stable_uptime_threshold_ms = 600000;  // 10 minutes
static bool boot_counter_cleared = false;

// Recovery tracking
static uint32_t consecutive_critical_issues = 0;
static uint32_t last_critical_time_ms = 0;
static uint32_t recovery_attempts = 0;

/**
 * @brief Get issue type as string
 */
static const char* health_issue_to_string(health_issue_type_t issue) {
    switch (issue) {
        case HEALTH_ISSUE_NONE: return "NONE";
        case HEALTH_ISSUE_BOOT_LOOP: return "BOOT_LOOP";
        case HEALTH_ISSUE_MEMORY_LOW: return "MEMORY_LOW";
        case HEALTH_ISSUE_MEMORY_LEAK: return "MEMORY_LEAK";
        case HEALTH_ISSUE_TASK_STARVATION: return "TASK_STARVATION";
        case HEALTH_ISSUE_WATCHDOG_TIMEOUT: return "WATCHDOG_TIMEOUT";
        case HEALTH_ISSUE_MQTT_DISCONNECTED: return "MQTT_DISCONNECTED";
        case HEALTH_ISSUE_WIFI_DISCONNECTED: return "WIFI_DISCONNECTED";
        case HEALTH_ISSUE_STACK_OVERFLOW: return "STACK_OVERFLOW";
        case HEALTH_ISSUE_HEAP_CORRUPTION: return "HEAP_CORRUPTION";
        case HEALTH_ISSUE_UNEXPECTED_REBOOT: return "UNEXPECTED_REBOOT";
        default: return "UNKNOWN";
    }
}

/**
 * @brief Get status severity as string
 */
static const char* health_status_to_string(health_status_t status) {
    switch (status) {
        case HEALTH_STATUS_OK: return "OK";
        case HEALTH_STATUS_WARNING: return "WARNING";
        case HEALTH_STATUS_CRITICAL: return "CRITICAL";
        case HEALTH_STATUS_FATAL: return "FATAL";
        default: return "UNKNOWN";
    }
}

/**
 * @brief Send health alert to server via MQTT
 */
static esp_err_t send_health_alert(const health_report_t *report) {
    if (!report) {
        return ESP_ERR_INVALID_ARG;
    }

    // Build MQTT topic: hdd-monitor/alerts/{ESP32_ID}
    char topic[96];  // Reduced from 128
    snprintf(topic, sizeof(topic), "hdd-monitor/alerts/%s", device_id);

    // Build JSON payload - OPTIMIZED: smaller buffer
    char payload[384];  // Reduced from 512
    snprintf(payload, sizeof(payload),
        "{"
        "\"esp32_id\":\"%s\","
        "\"status\":\"%s\","
        "\"issue_type\":\"%s\","
        "\"description\":\"%s\","
        "\"uptime_ms\":%lu,"
        "\"free_heap\":%lu,"
        "\"min_free_heap\":%lu,"
        "\"boot_count\":%lu,"
        "\"timestamp\":%lld"
        "}",
        report->esp32_id,
        health_status_to_string(report->status),
        health_issue_to_string(report->issue_type),
        report->issue_description,
        report->uptime_ms,
        report->free_heap,
        report->min_free_heap,
        report->boot_count,
        esp_timer_get_time() / 1000000LL
    );

    ESP_LOGW(TAG, "Sending health alert: %s - %s",
             health_issue_to_string(report->issue_type),
             report->issue_description);

    // Publish to MQTT
    esp_err_t ret = mqtt_manager_publish(topic, payload, 0, 1, false);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to publish health alert: %s", esp_err_to_name(ret));
        return ret;
    }

    // If critical or fatal, trigger log upload
    if (report->status >= HEALTH_STATUS_CRITICAL) {
        ESP_LOGW(TAG, "Critical issue detected - triggering log upload");
        log_uploader_reset_stability();  // Force immediate upload
    }

    return ESP_OK;
}

/**
 * @brief Check memory health
 */
static esp_err_t check_memory_health(health_report_t *report) {
    uint32_t free_heap = esp_get_free_heap_size();
    uint32_t min_free_heap = esp_get_minimum_free_heap_size();

    report->free_heap = free_heap;
    report->min_free_heap = min_free_heap;

    // Check for critically low memory
    if (free_heap < MEMORY_CRITICAL_THRESHOLD) {
        report->status = HEALTH_STATUS_CRITICAL;
        report->issue_type = HEALTH_ISSUE_MEMORY_LOW;
        snprintf(report->issue_description, sizeof(report->issue_description),
                 "Critical low memory: %lu bytes free", free_heap);
        return ESP_OK;
    }

    // Check for low memory warning
    if (free_heap < MEMORY_LOW_THRESHOLD) {
        if (report->status < HEALTH_STATUS_WARNING) {
            report->status = HEALTH_STATUS_WARNING;
            report->issue_type = HEALTH_ISSUE_MEMORY_LOW;
            snprintf(report->issue_description, sizeof(report->issue_description),
                     "Low memory: %lu bytes free", free_heap);
        }
    }

    // Memory leak detection disabled to save resources
    // Uncomment if needed after verifying memory is stable
    /*
    if (last_free_heap > 0 && last_free_heap > free_heap + MEMORY_LEAK_THRESHOLD) {
        if (report->status < HEALTH_STATUS_WARNING) {
            report->status = HEALTH_STATUS_WARNING;
            report->issue_type = HEALTH_ISSUE_MEMORY_LEAK;
            snprintf(report->issue_description, sizeof(report->issue_description),
                     "Possible memory leak: heap decreased from %lu to %lu bytes",
                     last_free_heap, free_heap);
        }
    }
    */

    last_free_heap = free_heap;
    return ESP_OK;
}

/**
 * @brief Check boot loop status
 */
static esp_err_t check_boot_loop(health_report_t *report) {
    esp_err_t ret = config_manager_check_boot_loop();

    if (ret != ESP_OK) {
        report->status = HEALTH_STATUS_FATAL;
        report->issue_type = HEALTH_ISSUE_BOOT_LOOP;
        snprintf(report->issue_description, sizeof(report->issue_description),
                 "Boot loop detected: %lu boots in <5min", report->boot_count);
        return ESP_OK;
    }

    return ESP_OK;
}

/**
 * @brief Check if this was an unexpected reboot
 */
static esp_err_t check_unexpected_reboot(health_report_t *report) {
    esp_reset_reason_t reset_reason = esp_reset_reason();

    // Check for unexpected reset reasons
    bool unexpected = false;
    const char *reason_str = "";

    switch (reset_reason) {
        case ESP_RST_PANIC:
            unexpected = true;
            reason_str = "Software panic/exception";
            break;
        case ESP_RST_INT_WDT:
            unexpected = true;
            reason_str = "Interrupt watchdog timeout";
            break;
        case ESP_RST_TASK_WDT:
            unexpected = true;
            reason_str = "Task watchdog timeout";
            break;
        case ESP_RST_WDT:
            unexpected = true;
            reason_str = "Other watchdog timeout";
            break;
        case ESP_RST_BROWNOUT:
            unexpected = true;
            reason_str = "Brownout detected";
            break;
        default:
            break;
    }

    if (unexpected && report->uptime_ms < 60000) {  // Only report if uptime < 1 minute
        report->status = HEALTH_STATUS_CRITICAL;
        report->issue_type = HEALTH_ISSUE_UNEXPECTED_REBOOT;
        snprintf(report->issue_description, sizeof(report->issue_description),
                 "Unexpected reboot: %s", reason_str);
    }

    return ESP_OK;
}

/**
 * @brief Perform comprehensive health check
 */
static esp_err_t perform_health_check(health_report_t *report) {
    if (!report) {
        return ESP_ERR_INVALID_ARG;
    }

    // Initialize report
    memset(report, 0, sizeof(health_report_t));
    report->status = HEALTH_STATUS_OK;
    report->issue_type = HEALTH_ISSUE_NONE;
    report->uptime_ms = esp_timer_get_time() / 1000;
    strncpy(report->esp32_id, device_id, sizeof(report->esp32_id) - 1);

    // Get boot count from config manager
    // Note: You may need to add a function to config_manager to get boot count
    report->boot_count = 0;  // Placeholder

    // Run all health checks
    check_memory_health(report);
    check_boot_loop(report);
    check_unexpected_reboot(report);

    // If no issues found, set OK description
    if (report->status == HEALTH_STATUS_OK) {
        snprintf(report->issue_description, sizeof(report->issue_description),
                 "System healthy");
    }

    return ESP_OK;
}

/**
 * @brief Health monitoring task
 */
static void health_monitor_task(void *pvParameters) {
    ESP_LOGI(TAG, "Health monitor task started on core %d", xPortGetCoreID());

    health_report_t report;
    bool first_check = true;
    uint32_t consecutive_errors = 0;

    while (monitor_running) {
        // Auto-clear boot counter after stable uptime
        if (!boot_counter_cleared && esp_timer_get_time() / 1000 > stable_uptime_threshold_ms) {
            ESP_LOGI(TAG, "System stable for %lu seconds - clearing boot counter",
                     stable_uptime_threshold_ms / 1000);
            config_manager_clear_boot_count();
            boot_counter_cleared = true;
        }

        // Perform health check
        esp_err_t ret = perform_health_check(&report);

        if (ret == ESP_OK) {
            // Store current health status
            if (xSemaphoreTake(health_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
                memcpy(&current_health, &report, sizeof(health_report_t));
                xSemaphoreGive(health_mutex);
            }

            // Send alert if there's an issue
            if (report.status > HEALTH_STATUS_OK) {
                send_health_alert(&report);
                consecutive_errors++;

                // Track critical issues for recovery decision
                if (report.status >= HEALTH_STATUS_CRITICAL) {
                    consecutive_critical_issues++;
                    last_critical_time_ms = report.uptime_ms;

                    ESP_LOGW(TAG, "Critical issue #%lu detected: %s",
                             consecutive_critical_issues,
                             report.issue_description);

                    // ESCALATION LOGIC
                    if (consecutive_critical_issues >= 3) {
                        ESP_LOGE(TAG, "========================================");
                        ESP_LOGE(TAG, "ESCALATION: %lu consecutive critical issues",
                                 consecutive_critical_issues);
                        ESP_LOGE(TAG, "System is NOT recovering - initiating restart");
                        ESP_LOGE(TAG, "========================================");

                        // Give time for alert to be sent
                        vTaskDelay(pdMS_TO_TICKS(2000));

                        // Force restart with recovery tracking
                        recovery_attempts++;
                        esp_restart();
                    }
                } else if (report.status == HEALTH_STATUS_WARNING) {
                    // Warning - monitor but don't escalate yet
                    ESP_LOGW(TAG, "Warning detected - monitoring for escalation");
                }

                // Check for too many consecutive errors
                if (consecutive_errors > 10) {
                    ESP_LOGE(TAG, "========================================");
                    ESP_LOGE(TAG, "SYSTEM UNSTABLE: %lu consecutive errors", consecutive_errors);
                    ESP_LOGE(TAG, "Recovery attempts: %lu", recovery_attempts);
                    ESP_LOGE(TAG, "Initiating controlled restart");
                    ESP_LOGE(TAG, "========================================");

                    vTaskDelay(pdMS_TO_TICKS(2000));
                    recovery_attempts++;
                    esp_restart();
                }
            } else {
                // System is OK - reset counters
                if (consecutive_errors > 0 || consecutive_critical_issues > 0) {
                    ESP_LOGI(TAG, "System recovered - resetting error counters");
                }
                consecutive_errors = 0;
                consecutive_critical_issues = 0;
            }

            // On first check, always send status (helps detect unexpected reboots)
            if (first_check) {
                if (report.uptime_ms < 60000) {  // Less than 1 minute uptime
                    ESP_LOGW(TAG, "System recently rebooted - sending initial health report");
                    send_health_alert(&report);
                }
                first_check = false;
            }
        }

        // Sleep for check interval
        vTaskDelay(pdMS_TO_TICKS(HEALTH_MONITOR_CHECK_INTERVAL_MS));
    }

    ESP_LOGI(TAG, "Health monitor task stopping");
    vTaskDelete(NULL);
}

esp_err_t health_monitor_init(const char *esp32_id) {
    if (!esp32_id) {
        return ESP_ERR_INVALID_ARG;
    }

    if (monitor_running) {
        ESP_LOGW(TAG, "Health monitor already running");
        return ESP_OK;
    }

    // Store device ID
    strncpy(device_id, esp32_id, sizeof(device_id) - 1);

    // Create mutex
    health_mutex = xSemaphoreCreateMutex();
    if (!health_mutex) {
        ESP_LOGE(TAG, "Failed to create health mutex");
        return ESP_ERR_NO_MEM;
    }

    // Create health monitor task on Core 1
    monitor_running = true;
    BaseType_t ret = xTaskCreatePinnedToCore(
        health_monitor_task,
        "health_mon",
        HEALTH_MONITOR_TASK_STACK_SIZE,
        NULL,
        HEALTH_MONITOR_TASK_PRIORITY,
        &health_task_handle,
        HEALTH_MONITOR_CORE_ID
    );

    if (ret != pdPASS) {
        ESP_LOGE(TAG, "Failed to create health monitor task");
        monitor_running = false;
        vSemaphoreDelete(health_mutex);
        return ESP_FAIL;
    }

    ESP_LOGI(TAG, "Health monitor initialized for device %s", esp32_id);
    return ESP_OK;
}

esp_err_t health_monitor_stop(void) {
    if (!monitor_running) {
        return ESP_OK;
    }

    monitor_running = false;

    // Wait for task to finish
    if (health_task_handle) {
        vTaskDelay(pdMS_TO_TICKS(100));
        health_task_handle = NULL;
    }

    // Delete mutex
    if (health_mutex) {
        vSemaphoreDelete(health_mutex);
        health_mutex = NULL;
    }

    ESP_LOGI(TAG, "Health monitor stopped");
    return ESP_OK;
}

esp_err_t health_monitor_report_issue(health_issue_type_t issue_type, const char *description) {
    if (!monitor_running) {
        return ESP_ERR_INVALID_STATE;
    }

    health_report_t report;
    perform_health_check(&report);

    // Override with manual issue
    report.issue_type = issue_type;
    if (description) {
        strncpy(report.issue_description, description, sizeof(report.issue_description) - 1);
    }

    // Determine status based on issue type
    switch (issue_type) {
        case HEALTH_ISSUE_BOOT_LOOP:
        case HEALTH_ISSUE_HEAP_CORRUPTION:
            report.status = HEALTH_STATUS_FATAL;
            break;
        case HEALTH_ISSUE_WATCHDOG_TIMEOUT:
        case HEALTH_ISSUE_MEMORY_LOW:
        case HEALTH_ISSUE_UNEXPECTED_REBOOT:
            report.status = HEALTH_STATUS_CRITICAL;
            break;
        default:
            report.status = HEALTH_STATUS_WARNING;
            break;
    }

    return send_health_alert(&report);
}

esp_err_t health_monitor_get_status(health_report_t *report) {
    if (!report) {
        return ESP_ERR_INVALID_ARG;
    }

    if (!monitor_running) {
        return ESP_ERR_INVALID_STATE;
    }

    if (xSemaphoreTake(health_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        memcpy(report, &current_health, sizeof(health_report_t));
        xSemaphoreGive(health_mutex);
        return ESP_OK;
    }

    return ESP_ERR_TIMEOUT;
}

bool health_monitor_is_healthy(void) {
    if (!monitor_running) {
        return false;
    }

    bool healthy = false;
    if (xSemaphoreTake(health_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        healthy = (current_health.status == HEALTH_STATUS_OK);
        xSemaphoreGive(health_mutex);
    }

    return healthy;
}

esp_err_t health_monitor_check_now(void) {
    if (!monitor_running) {
        return ESP_ERR_INVALID_STATE;
    }

    health_report_t report;
    esp_err_t ret = perform_health_check(&report);
    if (ret == ESP_OK) {
        ret = send_health_alert(&report);
    }

    return ret;
}
