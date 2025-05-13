#ifndef CONFIG_MANAGER_H
#define CONFIG_MANAGER_H

#include "esp_err.h"
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Constantes de configuración
#define CONFIG_NVS_NAMESPACE "hddesp32_cfg"
#define CONFIG_MAX_KEY_LENGTH 15
#define CONFIG_MAX_STRING_LENGTH 256

// Inicialización y finalización
esp_err_t config_manager_init(void);
esp_err_t config_manager_deinit(void);

// Operaciones con strings
esp_err_t config_manager_get_str(const char *key, char *out_value, size_t max_len);
esp_err_t config_manager_set_str(const char *key, const char *value);

// Operaciones con enteros
esp_err_t config_manager_get_i32(const char *key, int32_t *out_value);
esp_err_t config_manager_set_i32(const char *key, int32_t value);

// Operaciones con blobs
esp_err_t config_manager_get_blob(const char *key, void *out_value, size_t *length);
esp_err_t config_manager_set_blob(const char *key, const void *value, size_t length);

// Operaciones de borrado
esp_err_t config_manager_erase_key(const char *key);
esp_err_t config_manager_erase_all(void);

// Utilidades
esp_err_t config_manager_get_namespace_stats(size_t *used_entries, size_t *free_entries);
bool config_manager_key_exists(const char *key);

#ifdef __cplusplus
}
#endif

#endif // CONFIG_MANAGER_H