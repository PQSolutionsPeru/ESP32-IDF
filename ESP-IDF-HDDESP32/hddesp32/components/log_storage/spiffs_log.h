#pragma once

#include <stdarg.h>
#include "esp_err.h"

esp_err_t log_storage_init(void);
int spiffs_vprintf(const char *fmt, va_list ap);
esp_err_t log_storage_rotate(void);

