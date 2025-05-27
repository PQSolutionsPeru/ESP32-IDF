#ifndef MQTT_MANAGER_H
#define MQTT_MANAGER_H

#include <stdbool.h>
#include "esp_err.h"

// ESP32 ID Configuration - Inherited from esp32_id_manager.h
#ifndef ESP32_ID_LENGTH
#define ESP32_ID_LENGTH 8
#endif
#define ESP32_ID_BUFFER_SIZE (ESP32_ID_LENGTH + 1)  // +1 for null terminator

// MAC Address Configuration - Use esp32_id_manager format (12 chars without separators)
#ifndef ESP32_MAC_STR_LENGTH  
#define ESP32_MAC_STR_LENGTH 12  // 12 characters for MAC string without separators
#endif
#define ESP32_MAC_BUFFER_SIZE (ESP32_MAC_STR_LENGTH + 1)  // +1 for null terminator

// MQTT Buffer Size Constants - Generous sizes to avoid format-truncation warnings
// These sizes provide significant safety margins for GCC strict checking

// Topic lengths: Extended safety margin for all topic variations
#define MQTT_TOPIC_MAX_LENGTH 256

// Broker URL: Generous buffer for any broker URL
#define MQTT_BROKER_MAX_LENGTH 128

// Client ID, Username, Password: Generous buffers for credentials
#define MQTT_CLIENT_ID_MAX_LENGTH 64
#define MQTT_USERNAME_MAX_LENGTH 64
#define MQTT_PASSWORD_MAX_LENGTH 64

// LWT Message: Large buffer for JSON LWT messages
#define MQTT_LWT_MESSAGE_MAX_LENGTH 512

// URI: Large buffer for complete MQTT URIs
#define MQTT_URI_MAX_LENGTH 256

// Timestamp: Generous buffer for timestamp strings
#define MQTT_TIMESTAMP_MAX_LENGTH 64

// JSON Buffer: Large buffer for complex JSON messages
#define MQTT_JSON_BUFFER_MAX_LENGTH 1024

/**
 * @brief Estados del MQTT Manager
 */
typedef enum {
    MQTT_MANAGER_STATE_INIT,            // Estado inicial
    MQTT_MANAGER_STATE_DISCONNECTED,    // MQTT desconectado
    MQTT_MANAGER_STATE_CONNECTING,      // Intentando conectar
    MQTT_MANAGER_STATE_CONNECTED,       // Conectado al broker MQTT
    MQTT_MANAGER_STATE_RECONNECTING,    // Intentando reconectar
    MQTT_MANAGER_STATE_ERROR            // Error en la conexión
} mqtt_manager_state_t;

/**
 * @brief Callback para mensajes recibidos
 */
typedef void (*mqtt_manager_message_callback_t)(const char *topic, const char *data, int data_len, void *user_data);

/**
 * @brief Callback para cambios de estado
 */
typedef void (*mqtt_manager_state_callback_t)(mqtt_manager_state_t state, void *user_data);

/**
 * @brief Inicializa el MQTT Manager
 * 
 * @return ESP_OK si se inicializó correctamente, de lo contrario un código de error
 */
esp_err_t mqtt_manager_init(void);

/**
 * @brief Configura credenciales MQTT basadas en el ESP32 ID
 * 
 * @param esp32_id ID único del ESP32
 * @return ESP_OK si se configuró correctamente, de lo contrario un código de error
 */
esp_err_t mqtt_manager_set_esp32_id(const char *esp32_id);

/**
 * @brief Inicia conexión al broker MQTT
 * 
 * @return ESP_OK si la conexión se inició correctamente, de lo contrario un código de error
 */
esp_err_t mqtt_manager_connect(void);

/**
 * @brief Desconecta del broker MQTT
 * 
 * @return ESP_OK si la desconexión se realizó correctamente, de lo contrario un código de error
 */
esp_err_t mqtt_manager_disconnect(void);

/**
 * @brief Comprueba si está conectado al broker MQTT
 * 
 * @return true si está conectado, false si no
 */
bool mqtt_manager_is_connected(void);

/**
 * @brief Suscribe a un tópico MQTT
 * 
 * @param topic Tópico al que suscribirse
 * @param qos QoS para la suscripción (0, 1, 2)
 * @return ESP_OK si la suscripción se realizó correctamente, de lo contrario un código de error
 */
esp_err_t mqtt_manager_subscribe(const char *topic, int qos);

/**
 * @brief Desuscribe de un tópico MQTT
 * 
 * @param topic Tópico del que desuscribirse
 * @return ESP_OK si la desuscripción se realizó correctamente, de lo contrario un código de error
 */
esp_err_t mqtt_manager_unsubscribe(const char *topic);

/**
 * @brief Publica un mensaje MQTT
 * 
 * @param topic Tópico donde publicar
 * @param data Datos a publicar
 * @param data_len Longitud de los datos (-1 para calcular automáticamente)
 * @param qos QoS para la publicación (0, 1, 2)
 * @param retain Flag para retener el mensaje
 * @return ESP_OK si la publicación se realizó correctamente, de lo contrario un código de error
 */
esp_err_t mqtt_manager_publish(const char *topic, const char *data, int data_len, int qos, bool retain);

/**
 * @brief Publica un evento JSON
 * 
 * @param topic Tópico donde publicar
 * @param json_data Datos JSON a publicar
 * @param qos QoS para la publicación (0, 1, 2)
 * @param retain Flag para retener el mensaje
 * @return ESP_OK si la publicación se realizó correctamente, de lo contrario un código de error
 */
esp_err_t mqtt_manager_publish_json(const char *topic, const char *json_data, int qos, bool retain);

/**
 * @brief Procesa los mensajes MQTT entrantes (debe llamarse periódicamente)
 * 
 * @param timeout_ms Tiempo máximo de espera en ms
 * @return ESP_OK si se procesó correctamente, de lo contrario un código de error
 */
esp_err_t mqtt_manager_loop(int timeout_ms);

/**
 * @brief Establece el callback para mensajes recibidos
 * 
 * @param callback Función callback a llamar cuando se reciba un mensaje
 * @param user_data Datos de usuario a pasar al callback
 * @return ESP_OK si se estableció correctamente, de lo contrario un código de error
 */
esp_err_t mqtt_manager_set_message_callback(mqtt_manager_message_callback_t callback, void *user_data);

/**
 * @brief Establece el callback para cambios de estado
 * 
 * @param callback Función callback a llamar cuando cambie el estado
 * @param user_data Datos de usuario a pasar al callback
 * @return ESP_OK si se estableció correctamente, de lo contrario un código de error
 */
esp_err_t mqtt_manager_set_state_callback(mqtt_manager_state_callback_t callback, void *user_data);

/**
 * @brief Obtiene el estado actual del MQTT Manager
 * 
 * @return Estado actual del MQTT Manager
 */
mqtt_manager_state_t mqtt_manager_get_state(void);

/**
 * @brief Envia información de red y estado del dispositivo
 *
 * @return ESP_OK si la información se envió correctamente, de lo contrario un código de error
 */
esp_err_t mqtt_manager_send_network_info(void);

/**
 * @brief Envía un heartbeat al broker
 * 
 * @return ESP_OK si el heartbeat se envió correctamente, de lo contrario un código de error
 */
esp_err_t mqtt_manager_send_heartbeat(void);

#endif /* MQTT_MANAGER_H */