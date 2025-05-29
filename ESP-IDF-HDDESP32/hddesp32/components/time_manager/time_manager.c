#include "time_manager.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_system.h"
#include "esp_sntp.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "freertos/semphr.h"
#include "esp_netif.h"
#include "wifi_manager.h"
#include "mqtt_manager.h"
#include <string.h>

static const char *TAG = "TIME_MGR";

// Event group bits
#define TIME_SYNC_BIT BIT0
#define TIME_UNSYNC_BIT BIT1
#define TIME_SEND_NETWORK_INFO_BIT BIT2  // ← NUEVO BIT PARA SEÑALAR ENVÍO

// Constants optimized for 4MB ESP32
#define NTP_SERVER_PRIMARY "pool.ntp.org"
#define NTP_SERVER_SECONDARY "time.nist.gov"
#define NTP_SERVER_FALLBACK "time.google.com"
#define TIME_SYNC_INTERVAL_MS (1000 * 60 * 60) // 1 hour
#define TIME_SYNC_RETRY_DELAY_MS (1000 * 60)   // 1 minute

// Lima, Peru timezone constants (UTC-5)
#define LIMA_TIMEZONE_OFFSET -5 * 3600
#define LIMA_TIMEZONE_STR "PET"

// Time manager context - optimized structure
typedef struct {
    time_manager_state_t state;
    EventGroupHandle_t event_group;
    SemaphoreHandle_t mutex;
    int64_t last_sync_time;
    int sync_retry_count;
    bool sntp_initialized;
    bool network_info_pending;  // ← NUEVO FLAG
} time_manager_context_t;

static time_manager_context_t s_time_manager_ctx = {0};

// SNTP callback - CORREGIDO: NO llamar MQTT desde aquí
static void sntp_sync_callback(struct timeval *tv)
{
    time_manager_context_t *ctx = &s_time_manager_ctx;
    
    ESP_LOGI(TAG, "NTP synchronized successfully");
    
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        ctx->state = TIME_MANAGER_STATE_SYNCHRONIZED;
        ctx->last_sync_time = esp_timer_get_time() / 1000;
        ctx->sync_retry_count = 0;
        ctx->network_info_pending = true;  // ← MARCAR COMO PENDIENTE
        
        xEventGroupClearBits(ctx->event_group, TIME_UNSYNC_BIT);
        xEventGroupSetBits(ctx->event_group, TIME_SYNC_BIT | TIME_SEND_NETWORK_INFO_BIT);
        xSemaphoreGive(ctx->mutex);
    }
    
    // Show current time - minimal logging
    char time_str[32];
    if (time_manager_get_lima_time_str(time_str, sizeof(time_str)) == ESP_OK) {
        ESP_LOGI(TAG, "Lima time: %s", time_str);
    }
    
    // NO LLAMAR MQTT DESDE AQUÍ - será manejado por el task principal
}

// NUEVA FUNCIÓN: Verificar si hay que enviar network info
bool time_manager_should_send_network_info(void) {
    time_manager_context_t *ctx = &s_time_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return false;
    }
    
    EventBits_t bits = xEventGroupGetBits(ctx->event_group);
    return (bits & TIME_SEND_NETWORK_INFO_BIT) != 0;
}

// NUEVA FUNCIÓN: Marcar network info como enviado
void time_manager_mark_network_info_sent(void) {
    time_manager_context_t *ctx = &s_time_manager_ctx;
    
    if (ctx->event_group != NULL) {
        if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
            ctx->network_info_pending = false;
            xEventGroupClearBits(ctx->event_group, TIME_SEND_NETWORK_INFO_BIT);
            xSemaphoreGive(ctx->mutex);
        }
    }
}

// Initialize Time Manager
esp_err_t time_manager_init(void)
{
    time_manager_context_t *ctx = &s_time_manager_ctx;
    
    if (ctx->event_group != NULL) {
        ESP_LOGW(TAG, "Already initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Initializing Time Manager");
    
    // Create event group
    ctx->event_group = xEventGroupCreate();
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "Failed to create event group");
        return ESP_ERR_NO_MEM;
    }
    
    // Create mutex
    ctx->mutex = xSemaphoreCreateMutex();
    if (ctx->mutex == NULL) {
        vEventGroupDelete(ctx->event_group);
        ctx->event_group = NULL;
        ESP_LOGE(TAG, "Failed to create mutex");
        return ESP_ERR_NO_MEM;
    }
    
    // Initialize variables
    ctx->state = TIME_MANAGER_STATE_UNSYNCHRONIZED;
    ctx->last_sync_time = 0;
    ctx->sync_retry_count = 0;
    ctx->sntp_initialized = false;
    ctx->network_info_pending = false;  // ← INICIALIZAR NUEVO FLAG
    
    // Set timezone for Lima, Peru (UTC-5)
    setenv("TZ", "PET5", 1);
    tzset();
    ESP_LOGI(TAG, "Timezone set to Lima, Peru (UTC-5)");
    
    xEventGroupSetBits(ctx->event_group, TIME_UNSYNC_BIT);
    ESP_LOGI(TAG, "Time Manager initialized");
    return ESP_OK;
}

// Initialize SNTP - simplified
static esp_err_t init_sntp(void)
{
    time_manager_context_t *ctx = &s_time_manager_ctx;
    
    if (ctx->sntp_initialized) {
        return ESP_OK;
    }
    
    ESP_LOGI(TAG, "Initializing SNTP");
    esp_sntp_setoperatingmode(ESP_SNTP_OPMODE_POLL);
    
    esp_sntp_setservername(0, NTP_SERVER_PRIMARY);
    esp_sntp_setservername(1, NTP_SERVER_SECONDARY);
    esp_sntp_setservername(2, NTP_SERVER_FALLBACK);
    
    esp_sntp_set_time_sync_notification_cb(sntp_sync_callback);
    esp_sntp_init();
    
    ctx->sntp_initialized = true;
    ESP_LOGI(TAG, "SNTP initialized");
    return ESP_OK;
}

// Sync time with NTP servers - optimized
esp_err_t time_manager_sync_time(void)
{
    time_manager_context_t *ctx = &s_time_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "Not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (!wifi_manager_is_connected()) {
        ESP_LOGW(TAG, "WiFi not connected");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Starting NTP synchronization");
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        if (!ctx->sntp_initialized) {
            esp_err_t ret = init_sntp();
            if (ret != ESP_OK) {
                xSemaphoreGive(ctx->mutex);
                ESP_LOGE(TAG, "Failed to initialize SNTP");
                return ret;
            }
        }
        xSemaphoreGive(ctx->mutex);
    }
    
    // Wait for synchronization with timeout
    EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                           TIME_SYNC_BIT,
                                           pdFALSE, pdFALSE, pdMS_TO_TICKS(30000));
    
    if (bits & TIME_SYNC_BIT) {
        ESP_LOGI(TAG, "Time synchronized successfully");
        return ESP_OK;
    } else {
        ESP_LOGW(TAG, "NTP synchronization timeout");
        return ESP_ERR_TIMEOUT;
    }
}

// Check sync status - simplified
esp_err_t time_manager_check_sync(void)
{
    time_manager_context_t *ctx = &s_time_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (time_manager_is_synchronized()) {
        int64_t current_time = esp_timer_get_time() / 1000;
        
        // Check if resync needed
        if (current_time - ctx->last_sync_time >= TIME_SYNC_INTERVAL_MS) {
            ESP_LOGI(TAG, "Resync interval reached");
            return time_manager_sync_time();
        }
        return ESP_OK;
    }
    
    // Retry sync if needed
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        int64_t current_time = esp_timer_get_time() / 1000;
        
        if (current_time - ctx->last_sync_time >= TIME_SYNC_RETRY_DELAY_MS) {
            ctx->sync_retry_count++;
            ctx->last_sync_time = current_time;
            xSemaphoreGive(ctx->mutex);
            
            ESP_LOGI(TAG, "Retry #%d for time sync", ctx->sync_retry_count);
            return time_manager_sync_time();
        }
        xSemaphoreGive(ctx->mutex);
    }
    
    return ESP_ERR_NOT_FINISHED;
}

// Get ISO8601 timestamp
esp_err_t time_manager_get_iso8601(char *timestamp_out, size_t max_len)
{
    if (timestamp_out == NULL || max_len < 25) {
        return ESP_ERR_INVALID_ARG;
    }
    
    time_t now;
    struct tm timeinfo;
    
    time(&now);
    localtime_r(&now, &timeinfo);
    
    strftime(timestamp_out, max_len, "%Y-%m-%dT%H:%M:%S-05:00", &timeinfo);
    return ESP_OK;
}

// Get current timestamp
time_t time_manager_get_time(void)
{
    return time(NULL);
}

// Get formatted time
esp_err_t time_manager_get_formatted(char *time_out, size_t max_len, const char* format)
{
    if (time_out == NULL || format == NULL || max_len < 10) {
        return ESP_ERR_INVALID_ARG;
    }
    
    time_t now;
    struct tm timeinfo;
    
    time(&now);
    localtime_r(&now, &timeinfo);
    
    size_t ret = strftime(time_out, max_len, format, &timeinfo);
    return (ret == 0) ? ESP_ERR_INVALID_SIZE : ESP_OK;
}

// Get Lima time string
esp_err_t time_manager_get_lima_time_str(char *time_out, size_t max_len)
{
    if (time_out == NULL || max_len < 20) {
        return ESP_ERR_INVALID_ARG;
    }
    
    time_t now;
    struct tm timeinfo;
    
    time(&now);
    localtime_r(&now, &timeinfo);
    
    snprintf(time_out, max_len, "%02d/%02d/%04d, %02d:%02d:%02d",
             timeinfo.tm_mday, timeinfo.tm_mon + 1, timeinfo.tm_year + 1900,
             timeinfo.tm_hour, timeinfo.tm_min, timeinfo.tm_sec);
    
    return ESP_OK;
}

// Get timestamp string
esp_err_t time_manager_get_timestamp(char *timestamp_out, size_t max_len)
{
    if (timestamp_out == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (!time_manager_is_synchronized()) {
        int64_t current_time = esp_timer_get_time() / 1000;
        snprintf(timestamp_out, max_len, "%lld", (long long)current_time);
        return ESP_OK;
    }
    
    time_t now = time(NULL);
    snprintf(timestamp_out, max_len, "%lld", (long long)(now * 1000));
    return ESP_OK;
}

// Check if synchronized
bool time_manager_is_synchronized(void)
{
    time_manager_context_t *ctx = &s_time_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return false;
    }
    
    EventBits_t bits = xEventGroupGetBits(ctx->event_group);
    return (bits & TIME_SYNC_BIT) != 0;
}

// Get state
time_manager_state_t time_manager_get_state(void)
{
    time_manager_context_t *ctx = &s_time_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return TIME_MANAGER_STATE_INIT;
    }
    
    return ctx->state;
}

// Convert timestamp to epoch
int64_t time_manager_timestamp_to_epoch(const char *timestamp)
{
    if (timestamp == NULL) {
        return 0;
    }
    
    char *endptr;
    int64_t timestamp_int = strtoll(timestamp, &endptr, 10);
    
    if (*endptr == '\0') {
        int digits = endptr - timestamp;
        if (digits >= 13) {
            return timestamp_int / 1000; // Convert ms to seconds
        } else {
            return timestamp_int; // Already in seconds
        }
    }
    
    // Try parsing ISO 8601
    struct tm tm = {0};
    
    if (strptime(timestamp, "%Y-%m-%dT%H:%M:%S", &tm) != NULL) {
        return mktime(&tm);
    }
    
    if (strptime(timestamp, "%Y-%m-%d %H:%M:%S", &tm) != NULL) {
        return mktime(&tm);
    }
    
    if (strptime(timestamp, "%d/%m/%Y, %H:%M:%S", &tm) != NULL) {
        return mktime(&tm);
    }
    
    return 0;
}

// Convert epoch to ISO8601
esp_err_t time_manager_epoch_to_iso8601(time_t epoch, char *iso_out, size_t max_len)
{
    if (iso_out == NULL || max_len < 25) {
        return ESP_ERR_INVALID_ARG;
    }
    
    struct tm timeinfo;
    localtime_r(&epoch, &timeinfo);
    
    strftime(iso_out, max_len, "%Y-%m-%dT%H:%M:%S-05:00", &timeinfo);
    return ESP_OK;
}