#ifndef WIFI_MANAGER_H
#define WIFI_MANAGER_H

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"
#include "esp_wifi_types.h"
#include "esp_wifi.h"
#include "esp_log.h"

/**
 * @brief Estados del WiFi Manager
 */
typedef enum {
    WIFI_MANAGER_STATE_INIT,           // Estado inicial
    WIFI_MANAGER_STATE_DISCONNECTED,   // WiFi desconectado
    WIFI_MANAGER_STATE_CONNECTING,     // Intentando conectar
    WIFI_MANAGER_STATE_CONNECTED,      // Conectado a WiFi
    WIFI_MANAGER_STATE_AP_MODE,        // Modo Access Point
    WIFI_MANAGER_STATE_STA_AP_MODE,    // Modo combinado STA+AP
    WIFI_MANAGER_STATE_ERROR           // Error en la conexión
} wifi_manager_state_t;

/**
 * @brief Estructura para el resultado de un escaneo WiFi
 */
typedef struct {
    char ssid[33];
    int8_t rssi;
    wifi_auth_mode_t auth_mode;    // Cambiado de auth_mode_t a wifi_auth_mode_t
} wifi_scan_result_t;

/**
 * @brief Inicializa el componente WiFi Manager
 * 
 * @return ESP_OK si se inicializó correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_init(void);

/**
 * @brief Conecta a una red WiFi usando credenciales proporcionadas
 * 
 * @param ssid SSID de la red WiFi
 * @param password Contraseña de la red WiFi
 * @param save Indica si se deben guardar las credenciales (true) o no (false)
 * @return ESP_OK si la conexión se inició correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_connect(const char *ssid, const char *password, bool save);

/**
 * @brief Conecta a una red WiFi usando credenciales guardadas previamente
 * 
 * @return ESP_OK si la conexión se inició correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_connect_saved(void);

/**
 * @brief Desconecta de la red WiFi actual
 * 
 * @return ESP_OK si la desconexión se inició correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_disconnect(void);

/**
 * @brief Inicia el modo Access Point para configuración
 * 
 * @param ap_ssid SSID para el Access Point
 * @param ap_password Contraseña para el Access Point
 * @return ESP_OK si el modo AP se inició correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_start_ap_mode(const char *ap_ssid, const char *ap_password);

/**
 * @brief Inicia el modo combinado STA+AP para mantener conexión mientras se permite configuración
 * 
 * @param ap_ssid SSID para el Access Point
 * @param ap_password Contraseña para el Access Point
 * @return ESP_OK si el modo combinado se inició correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_start_sta_ap_mode(const char *ap_ssid, const char *ap_password);

/**
 * @brief Detiene el modo Access Point o combinado
 * 
 * @return ESP_OK si el modo se detuvo correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_stop_ap_mode(void);

/**
 * @brief Verifica si el dispositivo está conectado a una red WiFi
 * 
 * @return true si está conectado, false si no
 */
bool wifi_manager_is_connected(void);

/**
 * @brief Obtiene la dirección IP actual en modo STA
 * 
 * @param ip Puntero a un buffer donde se almacenará la dirección IP como string
 * @param max_len Tamaño máximo del buffer
 * @return ESP_OK si se obtuvo la IP correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_get_ip(char *ip, size_t max_len);

/**
 * @brief Obtiene la dirección IP del Access Point
 * 
 * @param ip Puntero a un buffer donde se almacenará la dirección IP como string
 * @param max_len Tamaño máximo del buffer
 * @return ESP_OK si se obtuvo la IP correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_get_ap_ip(char *ip, size_t max_len);

/**
 * @brief Obtiene el RSSI de la conexión actual
 * 
 * @param rssi Puntero donde se almacenará el valor RSSI
 * @return ESP_OK si se obtuvo el RSSI correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_get_rssi(int8_t *rssi);

/**
 * @brief Obtiene el estado actual del WiFi Manager
 * 
 * @return Estado actual del WiFi Manager
 */
wifi_manager_state_t wifi_manager_get_state(void);

/**
 * @brief Obtiene el SSID configurado actualmente
 * 
 * @param ssid Buffer donde se copiará el SSID
 * @param max_len Tamaño máximo del buffer
 * @return ESP_OK si se obtuvo el SSID correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_get_configured_ssid(char *ssid, size_t max_len);

/**
 * @brief Elimina las credenciales WiFi guardadas
 * 
 * @return ESP_OK si se borraron correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_forget_network(void);

/**
 * @brief Inicia un escaneo de redes WiFi disponibles
 * 
 * @return ESP_OK si el escaneo se inició correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_start_scan(void);

/**
 * @brief Obtiene los resultados del último escaneo WiFi
 * 
 * @param results Puntero a un array donde se almacenarán los resultados
 * @param max_networks Número máximo de redes a almacenar
 * @param num_networks Puntero donde se almacenará el número de redes encontradas
 * @return ESP_OK si se obtuvieron los resultados correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_get_scan_results(wifi_scan_result_t *results, size_t max_networks, size_t *num_networks);

/**
 * @brief Registra un callback para notificar cambios de estado
 * 
 * @param callback Función callback a llamar cuando cambie el estado
 * @param user_data Datos de usuario a pasar al callback
 * @return ESP_OK si se registró correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_set_state_callback(void (*callback)(wifi_manager_state_t state, void *user_data), void *user_data);

/**
 * @brief Maneja la pérdida de conexión WiFi, intentando reconectar y activando el modo AP si falla
 * 
 * @param ap_ssid_prefix Prefijo para el SSID del AP (se añadirá el ID del dispositivo)
 * @param ap_password Contraseña para el AP
 * @param max_attempts Número máximo de intentos de reconexión antes de activar el AP
 * @return ESP_OK si está gestionando correctamente, ESP_FAIL en caso de error crítico
 */
esp_err_t wifi_manager_handle_disconnection(const char *ap_ssid_prefix, const char *ap_password, int max_attempts);

/**
 * @brief Función para cambiar a modo station solamente (sin AP)
 * 
 * @return ESP_OK si se cambió correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_set_sta_mode(void);

/**
 * @brief Genera un SSID único para el AP basado en el ID del dispositivo
 * 
 * @param ap_ssid Buffer donde se generará el SSID
 * @param max_len Tamaño máximo del buffer
 * @param prefix Prefijo para el SSID (ej: "ESP32_")
 * @return ESP_OK si se generó correctamente, de lo contrario un código de error
 */
esp_err_t wifi_manager_generate_ap_ssid(char *ap_ssid, size_t max_len, const char *prefix);

#endif /* WIFI_MANAGER_H */