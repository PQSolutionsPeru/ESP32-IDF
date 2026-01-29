#ifndef SPIFFS_LOG_H
#define SPIFFS_LOG_H

#include "esp_err.h"
#include <stdarg.h>

#ifdef __cplusplus
extern "C" {
#endif

esp_err_t log_storage_init(void);

void log_storage_deinit(void);

int spiffs_vprintf(const char *fmt, va_list ap);

esp_err_t log_storage_rotate_if_needed(void);

esp_err_t log_storage_force_rotate(void);

esp_err_t log_storage_rotate(void);

esp_err_t log_storage_get_info(size_t *log_size, size_t *prev_log_size);

#ifdef __cplusplus
}
#endif

#endif