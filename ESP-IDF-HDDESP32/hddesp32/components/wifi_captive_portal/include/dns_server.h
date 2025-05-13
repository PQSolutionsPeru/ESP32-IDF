#ifndef DNS_SERVER_H
#define DNS_SERVER_H

#include <esp_err.h>
#include <lwip/ip4_addr.h>

typedef struct dns_server* dns_server_handle_t;

// Definición de callback para resolución de DNS personalizada
typedef ip4_addr_t (*dns_server_resolve_cb_t)(const char *hostname);

// Configuración del servidor DNS
typedef struct {
    dns_server_resolve_cb_t resolve_cb;
    uint16_t port;
} dns_server_config_t;

// Configuración por defecto
#define DNS_SERVER_CONFIG_DEFAULT() { \
    .resolve_cb = NULL, \
    .port = 53, \
}

/**
 * @brief Inicia el servidor DNS
 * 
 * @param config Configuración del servidor
 * @param[out] handle Puntero donde se almacenará el handle del servidor
 * @return esp_err_t 
 */
esp_err_t dns_server_start(const dns_server_config_t *config, dns_server_handle_t *handle);

/**
 * @brief Detiene el servidor DNS
 * 
 * @param handle Handle del servidor
 * @return esp_err_t 
 */
esp_err_t dns_server_stop(dns_server_handle_t handle);

#endif /* DNS_SERVER_H */