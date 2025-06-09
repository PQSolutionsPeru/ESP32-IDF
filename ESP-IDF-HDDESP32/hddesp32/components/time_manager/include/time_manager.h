#ifndef TIME_MANAGER_H
#define TIME_MANAGER_H

#include "esp_err.h"
#include <time.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Constantes
#define TIME_MANAGER_MAX_TIMESTAMP_LEN 32

// Estados del Time Manager
typedef enum {
    TIME_MANAGER_STATE_INIT,         // Estado inicial
    TIME_MANAGER_STATE_UNSYNCHRONIZED, // No sincronizado con NTP
    TIME_MANAGER_STATE_SYNCHRONIZED,  // Sincronizado con NTP
    TIME_MANAGER_STATE_ERROR         // Error en sincronización
} time_manager_state_t;

// Inicialización
esp_err_t time_manager_init(void);

// Sincronización NTP
esp_err_t time_manager_sync_time(void);
esp_err_t time_manager_check_sync(void);

// Obtención de tiempo
esp_err_t time_manager_get_timestamp(char *timestamp_out, size_t max_len);
esp_err_t time_manager_get_iso8601(char *timestamp_out, size_t max_len);
time_t time_manager_get_time(void);
esp_err_t time_manager_get_formatted(char *time_out, size_t max_len, const char* format);

// Estado
bool time_manager_is_synchronized(void);
time_manager_state_t time_manager_get_state(void);

// Conversión de timestamps
int64_t time_manager_timestamp_to_epoch(const char *timestamp);
esp_err_t time_manager_epoch_to_iso8601(time_t epoch, char *iso_out, size_t max_len);

// Utilidades específicas para el formato Lima, Perú
esp_err_t time_manager_get_lima_time_str(char *time_out, size_t max_len);

// ← NUEVAS FUNCIONES PARA MANEJO SEGURO DE NETWORK INFO
/**
 * @brief Verifica si hay que enviar network info después de sincronización NTP
 * 
 * @return true si hay que enviar, false si no
 */
bool time_manager_should_send_network_info(void);

/**
 * @brief Marca network info como enviado (limpiar flag)
 */
void time_manager_mark_network_info_sent(void);

void time_manager_reset_network_info_sent(void);

#ifdef __cplusplus
}
#endif

#endif // TIME_MANAGER_H