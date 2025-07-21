#ifndef CUSTOM_LOGGING_H
#define CUSTOM_LOGGING_H

#include "esp_log.h"
#include "esp_timer.h"
#include <stdio.h>

#ifdef __cplusplus
extern "C" {
#endif

// Declaraciones externas para evitar dependencia circular
extern bool time_manager_is_synchronized(void);
extern esp_err_t time_manager_get_lima_time_str(char *time_out, size_t max_len);

// Función auxiliar para obtener timestamp formateado
static inline const char* get_lima_timestamp_str(void) {
    static char time_buffer[32];
    
    if (time_manager_is_synchronized()) {
        if (time_manager_get_lima_time_str(time_buffer, sizeof(time_buffer)) == 0) {
            return time_buffer;
        }
    }
    
    // Fallback a milisegundos si no hay sincronización
    snprintf(time_buffer, sizeof(time_buffer), "%lld ms", esp_timer_get_time() / 1000);
    return time_buffer;
}

// Macros de logging personalizadas
#define CUSTOM_LOGE(tag, format, ...) \
    ESP_LOGE(tag, "[%s] " format, get_lima_timestamp_str(), ##__VA_ARGS__)

#define CUSTOM_LOGW(tag, format, ...) \
    ESP_LOGW(tag, "[%s] " format, get_lima_timestamp_str(), ##__VA_ARGS__)

#define CUSTOM_LOGI(tag, format, ...) \
    ESP_LOGI(tag, "[%s] " format, get_lima_timestamp_str(), ##__VA_ARGS__)

#define CUSTOM_LOGD(tag, format, ...) \
    ESP_LOGD(tag, "[%s] " format, get_lima_timestamp_str(), ##__VA_ARGS__)

#define CUSTOM_LOGV(tag, format, ...) \
    ESP_LOGV(tag, "[%s] " format, get_lima_timestamp_str(), ##__VA_ARGS__)

// Versiones abreviadas para facilitar el uso
#define LOG_E(tag, format, ...) CUSTOM_LOGE(tag, format, ##__VA_ARGS__)
#define LOG_W(tag, format, ...) CUSTOM_LOGW(tag, format, ##__VA_ARGS__)
#define LOG_I(tag, format, ...) CUSTOM_LOGI(tag, format, ##__VA_ARGS__)
#define LOG_D(tag, format, ...) CUSTOM_LOGD(tag, format, ##__VA_ARGS__)
#define LOG_V(tag, format, ...) CUSTOM_LOGV(tag, format, ##__VA_ARGS__)

#ifdef __cplusplus
}
#endif

#endif // CUSTOM_LOGGING_H