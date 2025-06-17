#ifndef RELAY_MANAGER_H
#define RELAY_MANAGER_H

#include <stdbool.h>
#include "esp_err.h"
#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"

#ifdef __cplusplus
extern "C" {
#endif

// Configuración de relays - 6 relays soportados
#define RELAY_MANAGER_MAX_RELAYS 6
#define RELAY_MANAGER_NAME_MAX_LENGTH 32
#define RELAY_MANAGER_ID_MAX_LENGTH 16

// Configuración de debounce
#define RELAY_MANAGER_DEBOUNCE_TIME_MS 50
#define RELAY_MANAGER_MIN_REPORT_INTERVAL_MS 2000

// Estados de relay (compatibles con VM)
typedef enum {
    RELAY_STATE_OK = 0,     // Contacto en estado normal/activo
    RELAY_STATE_DISC,       // Contacto desconectado/abierto
    RELAY_STATE_ERROR       // Estado de error
} relay_state_t;

// Tipos de contacto
typedef enum {
    RELAY_CONTACT_NO = 0,   // Normally Open (por defecto)
    RELAY_CONTACT_NC        // Normally Closed
} relay_contact_type_t;

// Estructura de configuración de relay
typedef struct {
    gpio_num_t gpio_pin;                                    // Pin GPIO
    char relay_id[RELAY_MANAGER_ID_MAX_LENGTH];            // ID del relay (relay_1, relay_2, etc.)
    char name[RELAY_MANAGER_NAME_MAX_LENGTH];              // Nombre personalizado
    relay_contact_type_t contact_type;                     // Tipo de contacto (NO/NC)
    bool is_active;                                        // ¿Está activo para monitoreo?
    relay_state_t current_state;                           // Estado actual
    int64_t last_change_time;                              // Timestamp del último cambio
    int64_t last_report_time;                              // Timestamp del último reporte MQTT
} relay_config_t;

// Evento de cambio de estado de relay
typedef struct {
    gpio_num_t gpio_pin;
    relay_state_t old_state;
    relay_state_t new_state;
    int64_t timestamp;
    char relay_id[RELAY_MANAGER_ID_MAX_LENGTH];
    char name[RELAY_MANAGER_NAME_MAX_LENGTH];
    relay_contact_type_t contact_type;
} relay_event_t;

// Callback para eventos de cambio de estado
typedef void (*relay_state_change_callback_t)(const relay_event_t *event, void *user_data);

// Callback para comandos de configuración MQTT
typedef esp_err_t (*relay_mqtt_command_callback_t)(const char *topic, const char *command_json, void *user_data);

typedef enum {
    RELAY_MGR_STATE_UNINITIALIZED = 0,
    RELAY_MGR_STATE_INITIALIZING,
    RELAY_MGR_STATE_RUNNING,
    RELAY_MGR_STATE_DEINITIALIZING
} relay_mgr_state_t;

relay_mgr_state_t relay_manager_get_mgr_state(void);

/**
 * @brief Inicializa el Relay Manager
 * 
 * Configura los 6 relays con pines por defecto y carga configuración persistente.
 * 
 * @return ESP_OK si se inicializó correctamente, de lo contrario un código de error
 */
esp_err_t relay_manager_init(void);

/**
 * @brief Configura el callback para cambios de estado de relays
 * 
 * @param callback Función a llamar cuando cambie el estado de un relay
 * @param user_data Datos de usuario a pasar al callback
 * @return ESP_OK si se configuró correctamente, de lo contrario un código de error
 */
esp_err_t relay_manager_set_state_callback(relay_state_change_callback_t callback, void *user_data);

/**
 * @brief Configura el callback para comandos MQTT de configuración
 * 
 * @param callback Función a llamar cuando se reciba un comando de configuración
 * @param user_data Datos de usuario a pasar al callback
 * @return ESP_OK si se configuró correctamente, de lo contrario un código de error
 */
esp_err_t relay_manager_set_mqtt_callback(relay_mqtt_command_callback_t callback, void *user_data);

/**
 * @brief Obtiene el estado actual de un relay por ID
 * 
 * @param relay_id ID del relay (relay_1, relay_2, etc.)
 * @param[out] state Estado actual del relay
 * @return ESP_OK si se obtuvo correctamente, ESP_ERR_NOT_FOUND si no existe
 */
esp_err_t relay_manager_get_state(const char *relay_id, relay_state_t *state);

/**
 * @brief Obtiene todos los estados de relays activos
 * 
 * Genera un JSON con los estados de todos los relays activos,
 * compatible con el formato esperado por la VM.
 * 
 * @param[out] json_buffer Buffer para el JSON de salida
 * @param buffer_size Tamaño del buffer
 * @return ESP_OK si se generó correctamente, de lo contrario un código de error
 */
esp_err_t relay_manager_get_all_states_json(char *json_buffer, size_t buffer_size);

/**
 * @brief Establece un nombre personalizado para un relay
 * 
 * @param relay_id ID del relay
 * @param name Nuevo nombre personalizado
 * @return ESP_OK si se estableció correctamente, de lo contrario un código de error
 */
esp_err_t relay_manager_set_name(const char *relay_id, const char *name);

/**
 * @brief Activa o desactiva un relay para monitoreo
 * 
 * @param relay_id ID del relay
 * @param active true para activar, false para desactivar
 * @return ESP_OK si se configuró correctamente, de lo contrario un código de error
 */
esp_err_t relay_manager_set_active(const char *relay_id, bool active);

/**
 * @brief Establece el tipo de contacto de un relay
 * 
 * @param relay_id ID del relay
 * @param contact_type Tipo de contacto (NO o NC)
 * @return ESP_OK si se estableció correctamente, de lo contrario un código de error
 */
esp_err_t relay_manager_set_contact_type(const char *relay_id, relay_contact_type_t contact_type);

/**
 * @brief Procesa un comando de configuración MQTT
 * 
 * Procesa comandos JSON como set_name, set_active, set_contact_type, etc.
 * 
 * @param command_json String JSON con el comando
 * @return ESP_OK si se procesó correctamente, de lo contrario un código de error
 */
esp_err_t relay_manager_process_mqtt_command(const char *command_json);

/**
 * @brief Obtiene la configuración completa de relays en formato JSON
 * 
 * @param[out] json_buffer Buffer para el JSON de salida
 * @param buffer_size Tamaño del buffer
 * @return ESP_OK si se generó correctamente, de lo contrario un código de error
 */
esp_err_t relay_manager_get_config_json(char *json_buffer, size_t buffer_size);

/**
 * @brief Fuerza una verificación de estados de todos los relays
 * 
 * Útil para enviar estado inicial después de conectar MQTT.
 * 
 * @param force_report Si true, envía eventos incluso si el estado no cambió
 * @return ESP_OK si se verificó correctamente, de lo contrario un código de error
 */
esp_err_t relay_manager_check_all_states(bool force_report);

/**
 * @brief Obtiene información de diagnóstico del relay manager
 * 
 * @param[out] json_buffer Buffer para el JSON de diagnóstico
 * @param buffer_size Tamaño del buffer
 * @return ESP_OK si se obtuvo correctamente, de lo contrario un código de error
 */
esp_err_t relay_manager_get_diagnostics_json(char *json_buffer, size_t buffer_size);

/**
 * @brief Deinicializa el Relay Manager y libera recursos
 * 
 * @return ESP_OK si se deinicializó correctamente, de lo contrario un código de error
 */
esp_err_t relay_manager_deinit(void);

#ifdef __cplusplus
}
#endif

#endif /* RELAY_MANAGER_H */