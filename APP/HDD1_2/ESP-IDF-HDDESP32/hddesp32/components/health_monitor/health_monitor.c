/**
 * @file health_monitor.c
 * @brief Implementation of independent health monitoring system
 */

#include "health_monitor.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_heap_caps.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "mqtt_manager.h"
#include "config_manager.h"
#include "log_uploader.h"
#include <string.h>
#include <stdio.h>

static const char *TAG = "HEALTH_MON";

// Configuration - ULTRA-LIGHTWEIGHT FOR NATIVE APPROACH
#define HEALTH_MONITOR_TASK_STACK_SIZE (1536)  // Minimal stack for simple monitoring
#define HEALTH_MONITOR_TASK_PRIORITY (tskIDLE_PRIORITY + 2)  // Low priority (not critical path)
#define HEALTH_MONITOR_CORE_ID (1)  // Run on Core 1
#define HEALTH_MONITOR_CHECK_INTERVAL_MS (30000)  // Check every 30 seconds (reduce CPU usage)

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
 * @brief Send simple health alert to server via MQTT (LIGHTWEIGHT)
 */
static esp_err_t send_simple_alert(const char *type, uint32_t free_heap, uint32_t boot_count) {
    // Build MQTT topic: hdd-monitor/alerts/{ESP32_ID}
    char topic[80];
    snprintf(topic, sizeof(topic), "hdd-monitor/alerts/%s", device_id);

    // Build minimal JSON payload
    char payload[200];
    snprintf(payload, sizeof(payload),
        "{\"type\":\"%s\",\"heap\":%lu,\"boot_count\":%lu,\"uptime\":%lld}",
        type, free_heap, boot_count, esp_timer_get_time() / 1000000LL
    );

    ESP_LOGW(TAG, "Alert: %s (heap=%lu, boots=%lu)", type, free_heap, boot_count);

    // Publish to MQTT
    esp_err_t ret = mqtt_manager_publish(topic, payload, 0, 1, false);
    if (ret != ESP_OK) {
        ESP_LOGD(TAG, "Failed to publish alert: %s", esp_err_to_name(ret));
        return ret;
    }

    return ESP_OK;
}

/**
 * @brief Send comprehensive health alert to server via MQTT
 */
static esp_err_t send_health_alert(const health_report_t *report) {
    if (!report) {
        return ESP_ERR_INVALID_ARG;
    }

    // Build MQTT topic: hdd-monitor/alerts/{ESP32_ID}
    char topic[80];
    snprintf(topic, sizeof(topic), "hdd-monitor/alerts/%s", device_id);

    // Build JSON payload
    char payload[512];
    snprintf(payload, sizeof(payload),
        "{\"esp32_id\":\"%s\",\"status\":\"%s\",\"issue_type\":\"%s\","
        "\"description\":\"%s\",\"uptime_ms\":%lu,\"free_heap\":%lu,"
        "\"min_free_heap\":%lu,\"boot_count\":%lu,\"timestamp\":%lld}",
        report->esp32_id,
        health_status_to_string(report->status),
        health_issue_to_string(report->issue_type),
        report->issue_description,
        (unsigned long)report->uptime_ms,
        report->free_heap,
        report->min_free_heap,
        report->boot_count,
        esp_timer_get_time() / 1000000LL
    );

    ESP_LOGW(TAG, "Health Alert: %s - %s",
             health_status_to_string(report->status),
             report->issue_description);

    // Publish to MQTT
    esp_err_t ret = mqtt_manager_publish(topic, payload, 0, 1, false);
    if (ret != ESP_OK) {
        ESP_LOGD(TAG, "Failed to publish health alert: %s", esp_err_to_name(ret));
        return ret;
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
 * @brief Simple health monitoring task (NATIVE APPROACH)
 * Only monitors and alerts - recovery handled by native TWDT
 */
static void health_monitor_task(void *pvParameters) {
    ESP_LOGI(TAG, "Simple health monitor started on core %d (native approach)", xPortGetCoreID());

    uint32_t stable_checks = 0;
    uint32_t last_alert_time = 0;
    const uint32_t ALERT_COOLDOWN_MS = 60000;  // Max 1 alert per minute

    while (monitor_running) {
        uint32_t current_time = esp_timer_get_time() / 1000;
        uint32_t free_heap = esp_get_free_heap_size();

        // Get boot count from config manager (if available)
        uint32_t boot_count = 0;
        // Note: Boot count tracking handled by main via RTC memory

        // Auto-clear boot counter after 10 minutes stable
        if (!boot_counter_cleared && current_time > stable_uptime_threshold_ms) {
            ESP_LOGI(TAG, "System stable for 10 minutes - clearing boot counter");
            config_manager_clear_boot_count();
            boot_counter_cleared = true;
        }

        // SIMPLE HEALTH CHECKS (only critical conditions)
        bool should_alert = false;
        const char *alert_type = NULL;

        if (free_heap < 30 * 1024) {
            should_alert = true;
            alert_type = "MEMORY_CRITICAL";
            ESP_LOGE(TAG, "CRITICAL: Low memory - %lu bytes free", free_heap);
        } else if (free_heap < 40 * 1024) {
            should_alert = true;
            alert_type = "MEMORY_LOW";
            ESP_LOGW(TAG, "WARNING: Memory low - %lu bytes free", free_heap);
        }

        // Send alert if needed (with cooldown)
        if (should_alert && (current_time - last_alert_time) > ALERT_COOLDOWN_MS) {
            send_simple_alert(alert_type, free_heap, boot_count);
            last_alert_time = current_time;
        }

        // Track stability
        if (free_heap > 40 * 1024) {
            stable_checks++;
        } else {
            stable_checks = 0;
        }

        // Log periodic status (every 10 checks = 5 minutes)
        if (stable_checks > 0 && stable_checks % 10 == 0) {
            ESP_LOGI(TAG, "System healthy - Uptime: %lu min, Heap: %lu KB",
                     current_time / 60000, free_heap / 1024);
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
