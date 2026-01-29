#ifndef SIMPLE_HTTP_SERVER_H
#define SIMPLE_HTTP_SERVER_H

#include <esp_err.h>

typedef void* simple_http_server_handle_t;

typedef struct {
    int port;
    int max_connections;
    int stack_size;
} simple_http_server_config_t;

/**
 * @brief Inicializa un servidor HTTP simple
 * 
 * @param config Configuración del servidor
 * @param handle Puntero donde se almacenará el handle del servidor
 * @return ESP_OK si se inicializó correctamente, de lo contrario un código de error
 */
esp_err_t simple_http_server_start(const simple_http_server_config_t* config, simple_http_server_handle_t* handle);

/**
 * @brief Detiene un servidor HTTP simple
 * 
 * @param handle Handle del servidor
 * @return ESP_OK si se detuvo correctamente, de lo contrario un código de error
 */
esp_err_t simple_http_server_stop(simple_http_server_handle_t handle);

#endif /* SIMPLE_HTTP_SERVER_H */