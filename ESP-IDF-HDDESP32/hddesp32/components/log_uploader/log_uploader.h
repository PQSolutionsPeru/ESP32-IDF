#pragma once

#include <stdbool.h>

void log_uploader_start(const char *esp32_id);
void log_uploader_stop(void);
bool log_uploader_is_system_stable(void);
void log_uploader_reset_stability(void);