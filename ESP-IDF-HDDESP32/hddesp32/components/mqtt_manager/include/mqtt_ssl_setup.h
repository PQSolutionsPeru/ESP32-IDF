#ifndef MQTT_SSL_SETUP_H
#define MQTT_SSL_SETUP_H

#include "esp_err.h"
#include "mqtt_client.h"

/**
 * @brief Configura opciones mínimas de SSL para MQTT
 * 
 * Esta función implementa una configuración SSL mínima equivalente
 * a la utilizada en MicroPython, sin verificación de certificados.
 * 
 * @param mqtt_cfg Puntero a la configuración MQTT a modificar
 * @return ESP_OK si se configuró correctamente
 */
esp_err_t mqtt_ssl_setup_minimal_config(esp_mqtt_client_config_t *mqtt_cfg);

#endif /* MQTT_SSL_SETUP_H */