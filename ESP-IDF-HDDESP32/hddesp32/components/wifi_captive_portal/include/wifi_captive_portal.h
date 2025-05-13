#ifndef WIFI_CAPTIVE_PORTAL_H
#define WIFI_CAPTIVE_PORTAL_H

#include <esp_err.h>
#include "wifi_manager.h"

/**
 * @brief Inicializa el portal cautivo para configuración WiFi
 * 
 * @param ap_ssid SSID del punto de acceso (NULL para usar ID de ESP32)
 * @param ap_password Contraseña del punto de acceso (NULL para punto abierto)
 * @return ESP_OK si se inicializó correctamente, de lo contrario un código de error
 */
esp_err_t wifi_captive_portal_start(const char *ap_ssid, const char *ap_password);

/**
 * @brief Detiene el portal cautivo
 * 
 * @return ESP_OK si se detuvo correctamente, de lo contrario un código de error
 */
esp_err_t wifi_captive_portal_stop(void);

/**
 * @brief Verifica si el portal cautivo está activo
 * 
 * @return true si está activo, false si no
 */
bool wifi_captive_portal_is_active(void);

/**
 * @brief Registra un callback que será llamado cuando se configure correctamente el WiFi
 * 
 * @param callback Función a llamar cuando se configure el WiFi
 * @param user_data Datos de usuario a pasar al callback
 * @return ESP_OK si se registró correctamente, de lo contrario un código de error
 */
esp_err_t wifi_captive_portal_set_on_connect_callback(void (*callback)(void *user_data), void *user_data);

#endif /* WIFI_CAPTIVE_PORTAL_H */