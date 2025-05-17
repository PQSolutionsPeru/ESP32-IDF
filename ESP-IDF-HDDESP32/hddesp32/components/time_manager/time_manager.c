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
#include <string.h>

static const char *TAG = "TIME_MGR";

// Definición de bits para el grupo de eventos
#define TIME_SYNC_BIT BIT0
#define TIME_UNSYNC_BIT BIT1

// Constantes para sincronización
#define NTP_SERVER_PRIMARY "pool.ntp.org"
#define NTP_SERVER_SECONDARY "time.nist.gov"
#define NTP_SERVER_FALLBACK "time.google.com"
#define TIME_SYNC_INTERVAL_MS (1000 * 60 * 60) // 1 hora
#define TIME_SYNC_RETRY_DELAY_MS (1000 * 60)   // 1 minuto

// Constantes para Lima, Perú (UTC-5)
#define LIMA_TIMEZONE_OFFSET -5 * 3600 // -5 horas en segundos
#define LIMA_TIMEZONE_STR "PET" // Peru Time

// Estructura para almacenar el estado del Time Manager
typedef struct {
    time_manager_state_t state;
    EventGroupHandle_t event_group;
    SemaphoreHandle_t mutex;
    int64_t last_sync_time;
    int sync_retry_count;
    bool sntp_initialized;
} time_manager_context_t;

// Instancia única del contexto del Time Manager
static time_manager_context_t s_time_manager_ctx = {0};

// Callback para eventos SNTP
static void sntp_sync_callback(struct timeval *tv)
{
    time_manager_context_t *ctx = &s_time_manager_ctx;
    ESP_LOGI(TAG, "NTP time synchronized!");
    
    // Adquirir mutex
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        ctx->state = TIME_MANAGER_STATE_SYNCHRONIZED;
        ctx->last_sync_time = esp_timer_get_time() / 1000;
        ctx->sync_retry_count = 0;
        
        // Actualizar eventos
        xEventGroupClearBits(ctx->event_group, TIME_UNSYNC_BIT);
        xEventGroupSetBits(ctx->event_group, TIME_SYNC_BIT);
        
        // Liberar mutex
        xSemaphoreGive(ctx->mutex);
    }
    
    // Mostrar la hora actualizada
    char time_str[64];
    time_manager_get_lima_time_str(time_str, sizeof(time_str));
    ESP_LOGI(TAG, "Current Lima time: %s", time_str);
}

// Inicialización del Time Manager
esp_err_t time_manager_init(void)
{
    time_manager_context_t *ctx = &s_time_manager_ctx;
    
    // Evitar inicialización múltiple
    if (ctx->event_group != NULL) {
        ESP_LOGW(TAG, "Time Manager already initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Initializing Time Manager");
    
    // Crear grupo de eventos
    ctx->event_group = xEventGroupCreate();
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "Failed to create event group");
        return ESP_ERR_NO_MEM;
    }
    
    // Crear mutex
    ctx->mutex = xSemaphoreCreateMutex();
    if (ctx->mutex == NULL) {
        vEventGroupDelete(ctx->event_group);
        ctx->event_group = NULL;
        ESP_LOGE(TAG, "Failed to create mutex");
        return ESP_ERR_NO_MEM;
    }
    
    // Inicializar variables
    ctx->state = TIME_MANAGER_STATE_INIT;
    ctx->last_sync_time = 0;
    ctx->sync_retry_count = 0;
    ctx->sntp_initialized = false;
    
    // Configurar zona horaria para Lima, Perú (UTC-5)
    setenv("TZ", "UTC-5", 1);
    tzset();
    ESP_LOGI(TAG, "Timezone set to Lima, Peru (UTC-5)");
    
    // Marcar como inicializado y no sincronizado
    ctx->state = TIME_MANAGER_STATE_UNSYNCHRONIZED;
    xEventGroupSetBits(ctx->event_group, TIME_UNSYNC_BIT);
    
    ESP_LOGI(TAG, "Time Manager initialized successfully");
    return ESP_OK;
}

// Inicialización de SNTP
static esp_err_t init_sntp(void)
{
    time_manager_context_t *ctx = &s_time_manager_ctx;
    
    if (ctx->sntp_initialized) {
        return ESP_OK;
    }
    
    ESP_LOGI(TAG, "Initializing SNTP");
    esp_sntp_setoperatingmode(ESP_SNTP_OPMODE_POLL);
    
    // Configurar servidores NTP
    esp_sntp_setservername(0, NTP_SERVER_PRIMARY);
    esp_sntp_setservername(1, NTP_SERVER_SECONDARY);
    esp_sntp_setservername(2, NTP_SERVER_FALLBACK);
    
    // Establecer callback de sincronización
    esp_sntp_set_time_sync_notification_cb(sntp_sync_callback);
    
    // Iniciar SNTP
    esp_sntp_init();
    ctx->sntp_initialized = true;
    
    ESP_LOGI(TAG, "SNTP initialized successfully");
    return ESP_OK;
}

// Sincronización con servidores NTP
esp_err_t time_manager_sync_time(void)
{
    time_manager_context_t *ctx = &s_time_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "Time Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    // Verificar si WiFi está conectado
    if (!wifi_manager_is_connected()) {
        ESP_LOGW(TAG, "WiFi not connected, cannot sync time");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Synchronizing time with NTP servers");
    
    // Adquirir mutex
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        // Inicializar SNTP si no está inicializado
        if (!ctx->sntp_initialized) {
            esp_err_t ret = init_sntp();
            if (ret != ESP_OK) {
                xSemaphoreGive(ctx->mutex);
                ESP_LOGE(TAG, "Failed to initialize SNTP: %s", esp_err_to_name(ret));
                return ret;
            }
        }
        
        // Liberar mutex
        xSemaphoreGive(ctx->mutex);
    }
    
    // Esperar sincronización con timeout (reducido para evitar bloqueo)
    EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                           TIME_SYNC_BIT,
                                           pdFALSE, pdFALSE, pdMS_TO_TICKS(30000));
    
    if (bits & TIME_SYNC_BIT) {
        ESP_LOGI(TAG, "Time synchronized successfully");
        return ESP_OK;
    } else {
        ESP_LOGW(TAG, "Timeout waiting for time synchronization");
        return ESP_ERR_TIMEOUT;
    }
}

// Verificar sincronización
esp_err_t time_manager_check_sync(void)
{
    time_manager_context_t *ctx = &s_time_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "Time Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    // Verificar si ya está sincronizado
    if (time_manager_is_synchronized()) {
        int64_t current_time = esp_timer_get_time() / 1000;
        
        // Verificar si es necesario resincronizar
        if (current_time - ctx->last_sync_time >= TIME_SYNC_INTERVAL_MS) {
            ESP_LOGI(TAG, "Time sync interval reached, resynchronizing");
            return time_manager_sync_time();
        }
        
        return ESP_OK;
    }
    
    // Si no está sincronizado, verificar si hay que reintentar
    // Adquirir mutex
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        int64_t current_time = esp_timer_get_time() / 1000;
        
        // Solo reintentar después del intervalo de reintento
        if (current_time - ctx->last_sync_time >= TIME_SYNC_RETRY_DELAY_MS) {
            ctx->sync_retry_count++;
            ctx->last_sync_time = current_time;
            
            // Liberar mutex
            xSemaphoreGive(ctx->mutex);
            
            // Mostrar intento actual
            ESP_LOGI(TAG, "Retry #%d for time synchronization", ctx->sync_retry_count);
            
            // Reintentar sincronización
            return time_manager_sync_time();
        }
        
        // Liberar mutex
        xSemaphoreGive(ctx->mutex);
    }
    
    return ESP_ERR_NOT_FINISHED;
}

// Obtener timestamp como string ISO 8601
esp_err_t time_manager_get_iso8601(char *timestamp_out, size_t max_len)
{
    if (timestamp_out == NULL || max_len < 25) { // ISO8601 necesita al menos 25 chars
        return ESP_ERR_INVALID_ARG;
    }
    
    time_t now;
    struct tm timeinfo;
    
    time(&now);
    localtime_r(&now, &timeinfo);
    
    // Formato ISO 8601: YYYY-MM-DDThh:mm:ss-05:00 (para Lima, Perú UTC-5)
    strftime(timestamp_out, max_len, "%Y-%m-%dT%H:%M:%S-05:00", &timeinfo);
    
    return ESP_OK;
}

// Obtener timestamp numérico actual (segundos desde Epoch)
time_t time_manager_get_time(void)
{
    return time(NULL);
}

// Obtener timestamp formateado según patrón
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
    if (ret == 0) {
        return ESP_ERR_INVALID_SIZE;
    }
    
    return ESP_OK;
}

// Obtener string de hora en formato compatible con la VM ubicada en Lima, Perú
esp_err_t time_manager_get_lima_time_str(char *time_out, size_t max_len)
{
    if (time_out == NULL || max_len < 20) {
        return ESP_ERR_INVALID_ARG;
    }
    
    time_t now;
    struct tm timeinfo;
    
    time(&now);
    localtime_r(&now, &timeinfo);
    
    // Formato DD/MM/YYYY, HH:MM:SS
    snprintf(time_out, max_len, "%02d/%02d/%04d, %02d:%02d:%02d",
             timeinfo.tm_mday, timeinfo.tm_mon + 1, timeinfo.tm_year + 1900,
             timeinfo.tm_hour, timeinfo.tm_min, timeinfo.tm_sec);
    
    return ESP_OK;
}

// Obtener timestamp largo (para compatibilidad con código existente)
esp_err_t time_manager_get_timestamp(char *timestamp_out, size_t max_len)
{
    if (timestamp_out == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    // Si no estamos sincronizados, usar el timestamp del sistema como fallback
    if (!time_manager_is_synchronized()) {
        int64_t current_time = esp_timer_get_time() / 1000;
        snprintf(timestamp_out, max_len, "%lld", (long long)current_time);
        return ESP_OK;
    }
    
    // Obtener el tiempo en segundos desde Epoch
    time_t now = time(NULL);
    
    // Convertir a milisegundos y formatear (mantiene compatibilidad con código existente)
    snprintf(timestamp_out, max_len, "%lld", (long long)(now * 1000));
    
    return ESP_OK;
}

// Verificar si el tiempo está sincronizado
bool time_manager_is_synchronized(void)
{
    time_manager_context_t *ctx = &s_time_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return false;
    }
    
    EventBits_t bits = xEventGroupGetBits(ctx->event_group);
    return (bits & TIME_SYNC_BIT) != 0;
}

// Obtener estado del Time Manager
time_manager_state_t time_manager_get_state(void)
{
    time_manager_context_t *ctx = &s_time_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return TIME_MANAGER_STATE_INIT;
    }
    
    return ctx->state;
}

// Convertir timestamp string a epoch
int64_t time_manager_timestamp_to_epoch(const char *timestamp)
{
    if (timestamp == NULL) {
        return 0;
    }
    
    // Verificar si es un timestamp numérico
    char *endptr;
    int64_t timestamp_int = strtoll(timestamp, &endptr, 10);
    
    // Si es un número y termina en \0, es un timestamp numérico
    if (*endptr == '\0') {
        // Verificar si es en milisegundos (13 dígitos) o segundos (10 dígitos)
        int digits = endptr - timestamp;
        if (digits >= 13) {
            return timestamp_int / 1000; // Convertir ms a segundos
        } else {
            return timestamp_int; // Ya está en segundos
        }
    }
    
    // Si no es numérico, intentar parsear como ISO 8601
    struct tm tm = {0};
    
    // Procesar diferentes formatos posibles de ISO 8601
    // Formato: YYYY-MM-DDThh:mm:ss
    if (strptime(timestamp, "%Y-%m-%dT%H:%M:%S", &tm) != NULL) {
        return mktime(&tm);
    }
    
    // Formato: YYYY-MM-DD hh:mm:ss
    if (strptime(timestamp, "%Y-%m-%d %H:%M:%S", &tm) != NULL) {
        return mktime(&tm);
    }
    
    // Formato: DD/MM/YYYY, HH:MM:SS (formato de Lima)
    if (strptime(timestamp, "%d/%m/%Y, %H:%M:%S", &tm) != NULL) {
        return mktime(&tm);
    }
    
    return 0; // Formato no reconocido
}

// Convertir epoch a ISO 8601
esp_err_t time_manager_epoch_to_iso8601(time_t epoch, char *iso_out, size_t max_len)
{
    if (iso_out == NULL || max_len < 25) {
        return ESP_ERR_INVALID_ARG;
    }
    
    struct tm timeinfo;
    localtime_r(&epoch, &timeinfo);
    
    // Formato ISO 8601: YYYY-MM-DDThh:mm:ss-05:00 (para Lima, Perú UTC-5)
    strftime(iso_out, max_len, "%Y-%m-%dT%H:%M:%S-05:00", &timeinfo);
    
    return ESP_OK;
}