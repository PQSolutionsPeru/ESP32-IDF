#ifndef ESP32_ID_MANAGER_H
#define ESP32_ID_MANAGER_H

#include "esp_err.h"
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Constantes
#define ESP32_ID_LENGTH 8
#define ESP32_MAC_STR_LENGTH 12
#define ESP32_ID_LENGTH 8
#define ESP32_ID_BUFFER_SIZE (ESP32_ID_LENGTH + 1)
#define ESP32_MAC_LENGTH 12
#define ESP32_MAC_BUFFER_SIZE (ESP32_MAC_LENGTH + 1)
#define ESP32_MAC_STR_LENGTH 17 

// Inicialización
esp_err_t esp32_id_manager_init(void);

// Obtención de datos
esp_err_t esp32_id_manager_get_id(char *id_out, size_t max_len);
esp_err_t esp32_id_manager_get_mac(char *mac_out, size_t max_len);

// Gestión
esp_err_t esp32_id_manager_reset(void);
bool esp32_id_manager_is_valid(void);

#ifdef __cplusplus
}
#endif

#endif // ESP32_ID_MANAGER_H