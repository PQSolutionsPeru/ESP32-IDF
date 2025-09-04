#ifndef LOG_UPLOADER_H
#define LOG_UPLOADER_H

#include <stdbool.h>
#include "esp32_id_manager.h"

#ifdef __cplusplus
extern "C" {
#endif

void log_uploader_start(const char *esp32_id);

void log_uploader_stop(void);

bool log_uploader_is_system_stable(void);

void log_uploader_reset_stability(void);

#ifdef __cplusplus
}
#endif

#endif