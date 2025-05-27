#ifndef WATCHDOG_MANAGER_H
#define WATCHDOG_MANAGER_H

#include <stdbool.h>
#include "esp_err.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

/**
 * @brief Modos de operación del watchdog
 */
typedef enum {
    WATCHDOG_MODE_CONFIG,       // Modo configuración (timeouts largos)
    WATCHDOG_MODE_RUNNING,      // Modo operacional (timeouts cortos)
    WATCHDOG_MODE_CRITICAL      // Modo crítico (timeouts muy cortos)
} watchdog_mode_t;

/**
 * @brief Estados de salud del sistema
 */
typedef enum {
    WATCHDOG_HEALTH_GOOD,       // Sistema saludable
    WATCHDOG_HEALTH_WARNING,    // Advertencias detectadas
    WATCHDOG_HEALTH_CRITICAL,   // Estado crítico
    WATCHDOG_HEALTH_ERROR       // Errores graves
} watchdog_health_status_t;

/**
 * @brief Tipos de verificación de salud
 */
typedef enum {
    WATCHDOG_CHECK_MEMORY,      // Verificar memoria disponible
    WATCHDOG_CHECK_WIFI,        // Verificar conectividad WiFi
    WATCHDOG_CHECK_MQTT,        // Verificar conectividad MQTT
    WATCHDOG_CHECK_TASKS        // Verificar estado de tasks
} watchdog_check_type_t;

/**
 * @brief Configuración del watchdog por modo
 */
typedef struct {
    uint32_t timeout_ms;            // Timeout del TWDT en milisegundos
    uint32_t feed_interval_ms;      // Intervalo de alimentación
    uint32_t health_check_interval_ms; // Intervalo de verificación de salud
    uint32_t memory_threshold_bytes;   // Umbral mínimo de memoria libre
    bool enable_memory_check;       // Habilitar verificación de memoria
    bool enable_wifi_check;         // Habilitar verificación WiFi
    bool enable_mqtt_check;         // Habilitar verificación MQTT
    bool enable_task_monitoring;    // Habilitar monitoreo de tasks
} watchdog_config_t;

/**
 * @brief Callback para eventos del watchdog
 */
typedef void (*watchdog_event_callback_t)(watchdog_health_status_t status, watchdog_check_type_t check_type, void *user_data);

/**
 * @brief Inicializa el Watchdog Manager
 * 
 * @return ESP_OK si se inicializó correctamente, de lo contrario un código de error
 */
esp_err_t watchdog_manager_init(void);

/**
 * @brief Desinicializa el Watchdog Manager
 * 
 * @return ESP_OK si se desinicializó correctamente, de lo contrario un código de error
 */
esp_err_t watchdog_manager_deinit(void);

/**
 * @brief Establece el modo de operación del watchdog
 * 
 * @param mode Modo de operación
 * @return ESP_OK si se estableció correctamente, de lo contrario un código de error
 */
esp_err_t watchdog_manager_set_mode(watchdog_mode_t mode);

/**
 * @brief Obtiene el modo actual del watchdog
 * 
 * @return Modo actual de operación
 */
watchdog_mode_t watchdog_manager_get_mode(void);

/**
 * @brief Registra un task en el monitoreo del watchdog
 * 
 * @param task_handle Handle del task a registrar (NULL para el task actual)
 * @param task_name Nombre del task para debugging
 * @return ESP_OK si se registró correctamente, de lo contrario un código de error
 */
esp_err_t watchdog_manager_register_task(TaskHandle_t task_handle, const char *task_name);

/**
 * @brief Desregistra un task del monitoreo del watchdog
 * 
 * @param task_handle Handle del task a desregistrar (NULL para el task actual)
 * @return ESP_OK si se desregistró correctamente, de lo contrario un código de error
 */
esp_err_t watchdog_manager_unregister_task(TaskHandle_t task_handle);

/**
 * @brief Alimenta el watchdog (debe ser llamado periódicamente por cada task registrado)
 * 
 * @return ESP_OK si se alimentó correctamente, de lo contrario un código de error
 */
esp_err_t watchdog_manager_feed(void);

/**
 * @brief Reporta actividad de un subsistema específico
 * 
 * @param check_type Tipo de verificación/subsistema
 * @return ESP_OK si se reportó correctamente, de lo contrario un código de error
 */
esp_err_t watchdog_manager_report_activity(watchdog_check_type_t check_type);

/**
 * @brief Fuerza un reset del sistema con una razón específica
 * 
 * @param reason Razón del reset (para logging)
 * @return Esta función no retorna (reset del sistema)
 */
void watchdog_manager_force_reset(const char *reason) __attribute__((noreturn));

/**
 * @brief Obtiene el estado de salud actual del sistema
 * 
 * @return Estado de salud actual
 */
watchdog_health_status_t watchdog_manager_get_health_status(void);

/**
 * @brief Obtiene estadísticas del watchdog
 * 
 * @param feed_count Contador de alimentaciones (puede ser NULL)
 * @param error_count Contador de errores (puede ser NULL)
 * @param last_reset_reason Última razón de reset (puede ser NULL)
 * @return ESP_OK si se obtuvieron las estadísticas correctamente
 */
esp_err_t watchdog_manager_get_stats(uint32_t *feed_count, uint32_t *error_count, const char **last_reset_reason);

/**
 * @brief Establece callback para eventos del watchdog
 * 
 * @param callback Función callback a llamar en eventos
 * @param user_data Datos de usuario para el callback
 * @return ESP_OK si se estableció correctamente, de lo contrario un código de error
 */
esp_err_t watchdog_manager_set_event_callback(watchdog_event_callback_t callback, void *user_data);

/**
 * @brief Realiza una verificación manual de salud del sistema
 * 
 * @return Estado de salud después de la verificación
 */
watchdog_health_status_t watchdog_manager_check_system_health(void);

/**
 * @brief Configura parámetros personalizados para un modo específico
 * 
 * @param mode Modo a configurar
 * @param config Configuración personalizada
 * @return ESP_OK si se configuró correctamente, de lo contrario un código de error
 */
esp_err_t watchdog_manager_set_config(watchdog_mode_t mode, const watchdog_config_t *config);

/**
 * @brief Obtiene la configuración actual para un modo específico
 * 
 * @param mode Modo del que obtener la configuración
 * @param config Estructura donde almacenar la configuración
 * @return ESP_OK si se obtuvo correctamente, de lo contrario un código de error
 */
esp_err_t watchdog_manager_get_config(watchdog_mode_t mode, watchdog_config_t *config);

#endif /* WATCHDOG_MANAGER_H */