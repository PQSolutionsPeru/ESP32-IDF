#ifndef CONFIG_PROCESSOR_H
#define CONFIG_PROCESSOR_H

#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief Process ESP32 configuration received from MQTT
 * 
 * @param config_json JSON string with configuration
 * @return ESP_OK on success
 */
esp_err_t process_esp32_configuration(const char *config_json);

#ifdef __cplusplus
}
#endif

#endif // CONFIG_PROCESSOR_H