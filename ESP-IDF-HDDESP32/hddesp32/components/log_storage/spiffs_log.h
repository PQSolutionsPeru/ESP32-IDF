#pragma once

#include <stdarg.h>
#include "esp_err.h"

esp_err_t log_storage_init(void);
void log_storage_deinit(void);
int spiffs_vprintf(const char *fmt, va_list ap);

// Nueva función que solo rota si es necesario (reemplaza a log_storage_rotate)
esp_err_t log_storage_rotate_if_needed(void);

// Función específica para rotar después de estabilidad del sistema (post-reinicio)
esp_err_t log_storage_rotate_after_stability(void);

// Función para forzar rotación (para casos especiales)
esp_err_t log_storage_force_rotate(void);

// Función legacy para compatibilidad
esp_err_t log_storage_rotate(void);

// Nueva función para obtener información de logs
esp_err_t log_storage_get_info(size_t *log_size, size_t *prev_log_size);