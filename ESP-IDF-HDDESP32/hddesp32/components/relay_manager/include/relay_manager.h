#ifndef RELAY_MANAGER_H
#define RELAY_MANAGER_H

#include <stdint.h>
#include <stdbool.h>
#include "esp_err.h"
#include "driver/gpio.h"

// Número de relays disponibles
#define RELAY_COUNT 6

// Estados lógicos del relay
#define RELAY_STATE_OK "OK"
#define RELAY_STATE_DISC "DISC"

// Tipos de contacto
typedef enum {
    RELAY_CONTACT_TYPE_NO,  // Normally Open (Normalmente Abierto)
    RELAY_CONTACT_TYPE_NC   // Normally Closed (Normalmente Cerrado)
} relay_contact_type_t;

// Estado del relay
typedef enum {
    RELAY_STATUS_OK,        // Estado normal
    RELAY_STATUS_DISC       // Estado de alarma/desconectado
} relay_status_t;

// Configuración de un relay individual
typedef struct {
    gpio_num_t gpio_pin;             // Pin GPIO
    char relay_id[16];               // ID del relay (ej: "relay_1")
    char custom_name[32];            // Nombre personalizado (ej: "Alarma")
    relay_contact_type_t contact_type; // Tipo de contacto (NO/NC)
    bool is_active;                  // Si está activo para monitoreo
    uint32_t debounce_time_ms;       // Tiempo de debounce en ms
    uint32_t min_report_interval_ms; // Intervalo mínimo entre reportes
} relay_config_t;

// Información de estado de un relay
typedef struct {
    char relay_id[16];
    char name[32];
    relay_status_t status;
    gpio_num_t pin;
    relay_contact_type_t contact_type;
    int64_t timestamp;
} relay_status_info_t;

// Callback para cambios de estado
typedef void (*relay_state_change_callback_t)(const char *relay_id, relay_status_t new_state, void *user_data);

/**
 * @brief Inicializa el gestor de relays
 * 
 * @return ESP_OK si se inicializó correctamente
 */
esp_err_t relay_manager_init(void);

/**
 * @brief Configura un callback para cambios de estado
 * 
 * @param callback Función callback a llamar cuando cambie el estado
 * @param user_data Datos de usuario a pasar al callback
 * @return ESP_OK si se configuró correctamente
 */
esp_err_t relay_manager_set_state_callback(relay_state_change_callback_t callback, void *user_data);

/**
 * @brief Obtiene el estado actual de un relay
 * 
 * @param pin Pin GPIO del relay
 * @param status Puntero donde se almacenará el estado
 * @return ESP_OK si se obtuvo correctamente
 */
esp_err_t relay_manager_get_state(gpio_num_t pin, relay_status_t *status);

/**
 * @brief Obtiene el estado de todos los relays activos
 * 
 * @param states Array para almacenar los estados
 * @param count Número máximo de estados a obtener
 * @param actual_count Número real de estados obtenidos
 * @return ESP_OK si se obtuvo correctamente
 */
esp_err_t relay_manager_get_all_states(relay_status_info_t *states, size_t count, size_t *actual_count);

/**
 * @brief Establece el nombre personalizado de un relay
 * 
 * @param relay_id ID del relay
 * @param name Nombre personalizado
 * @return ESP_OK si se configuró correctamente
 */
esp_err_t relay_manager_set_name(const char *relay_id, const char *name);

/**
 * @brief Activa o desactiva un relay para monitoreo
 * 
 * @param relay_id ID del relay
 * @param active true para activar, false para desactivar
 * @return ESP_OK si se configuró correctamente
 */
esp_err_t relay_manager_set_active(const char *relay_id, bool active);

/**
 * @brief Establece el tipo de contacto de un relay
 * 
 * @param relay_id ID del relay
 * @param contact_type Tipo de contacto (NO/NC)
 * @return ESP_OK si se configuró correctamente
 */
esp_err_t relay_manager_set_contact_type(const char *relay_id, relay_contact_type_t contact_type);

/**
 * @brief Obtiene la configuración completa de los relays
 * 
 * @param configs Array para almacenar las configuraciones
 * @param count Número máximo de configuraciones a obtener
 * @param actual_count Número real de configuraciones obtenidas
 * @return ESP_OK si se obtuvo correctamente
 */
esp_err_t relay_manager_get_config(relay_config_t *configs, size_t count, size_t *actual_count);

/**
 * @brief Guarda la configuración actual en NVS
 * 
 * @return ESP_OK si se guardó correctamente
 */
esp_err_t relay_manager_save_config(void);

/**
 * @brief Carga la configuración desde NVS
 * 
 * @return ESP_OK si se cargó correctamente
 */
esp_err_t relay_manager_load_config(void);

/**
 * @brief Realiza tareas de mantenimiento periódicas
 * 
 * @return ESP_OK si se ejecutó correctamente
 */
esp_err_t relay_manager_process(void);

#endif /* RELAY_MANAGER_H */