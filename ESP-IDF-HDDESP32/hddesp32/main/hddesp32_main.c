#include <stdio.h>
#include <inttypes.h>  // Añadido para PRIu32
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_system.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "config_manager.h"
#include "esp32_id_manager.h"
#include "wifi_manager.h"
#include "wifi_captive_portal.h"
#include "mqtt_manager.h"
#include "time_manager.h" // Añadido Time Manager
#include "esp_heap_caps.h"  // Añadido para heap_caps_get_largest_free_block

static const char *TAG = "HDDESP32";

// Función para monitorear el uso de memoria - Movida ANTES de ser usada
static void print_memory_info(void) {
    ESP_LOGI(TAG, "=== Memory Status ===");
    ESP_LOGI(TAG, "Free heap: %ld bytes", (long)esp_get_free_heap_size());
    ESP_LOGI(TAG, "Minimum free heap: %ld bytes", (long)esp_get_minimum_free_heap_size());
    ESP_LOGI(TAG, "Largest free block: %ld bytes", (long)heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));
    
    // Información de tareas (opcional - comentar si no se necesita)
    #ifdef CONFIG_FREERTOS_USE_TRACE_FACILITY
    char buffer[1024];
    vTaskList(buffer);
    ESP_LOGI(TAG, "Task List:\n%s", buffer);
    #endif
}

// Callback para manejar los cambios de estado del MQTT
static void mqtt_state_callback(mqtt_manager_state_t state, void *user_data) {
    switch (state) {
        case MQTT_MANAGER_STATE_INIT:
            ESP_LOGI(TAG, "MQTT state: INIT");
            break;
        case MQTT_MANAGER_STATE_DISCONNECTED:
            ESP_LOGI(TAG, "MQTT state: DISCONNECTED");
            break;
        case MQTT_MANAGER_STATE_CONNECTING:
            ESP_LOGI(TAG, "MQTT state: CONNECTING");
            break;
        case MQTT_MANAGER_STATE_CONNECTED:
            ESP_LOGI(TAG, "MQTT state: CONNECTED");
            
            // Cuando MQTT está conectado, enviar información de red
            ESP_LOGI(TAG, "Before network_info - Free heap: %" PRIu32, esp_get_free_heap_size());
            mqtt_manager_send_network_info();
            ESP_LOGI(TAG, "After network_info - Free heap: %" PRIu32, esp_get_free_heap_size());
            break;
        case MQTT_MANAGER_STATE_RECONNECTING:
            ESP_LOGI(TAG, "MQTT state: RECONNECTING");
            break;
        case MQTT_MANAGER_STATE_ERROR:
            ESP_LOGI(TAG, "MQTT state: ERROR");
            break;
        default:
            ESP_LOGI(TAG, "MQTT state: UNKNOWN");
            break;
    }
}

// Callback para manejar los mensajes MQTT recibidos
static void mqtt_message_callback(const char *topic, const char *data, int data_len, void *user_data) {
    ESP_LOGI(TAG, "MQTT message received on topic: %s", topic);
    ESP_LOGI(TAG, "Message data (%d bytes): %.*s", data_len, data_len, data);
    
    // Aquí podríamos procesar mensajes específicos como actualizaciones de configuración,
    // comandos de reset, etc.
}

// Callback para manejar los cambios de estado del WiFi
static void wifi_state_callback(wifi_manager_state_t state, void *user_data) {
    switch (state) {
        case WIFI_MANAGER_STATE_INIT:
            ESP_LOGI(TAG, "WiFi state: INIT");
            break;
        case WIFI_MANAGER_STATE_DISCONNECTED:
            ESP_LOGI(TAG, "WiFi state: DISCONNECTED");
            break;
        case WIFI_MANAGER_STATE_CONNECTING:
            ESP_LOGI(TAG, "WiFi state: CONNECTING");
            break;
        case WIFI_MANAGER_STATE_CONNECTED:
            ESP_LOGI(TAG, "WiFi state: CONNECTED");
            
            // Mostrar dirección IP
            char ip_address[16];
            if (wifi_manager_get_ip(ip_address, sizeof(ip_address)) == ESP_OK) {
                ESP_LOGI(TAG, "IP Address: %s", ip_address);
            }
            
            // Obtener potencia de señal
            int8_t rssi;
            if (wifi_manager_get_rssi(&rssi) == ESP_OK) {
                ESP_LOGI(TAG, "Signal strength (RSSI): %d dBm", rssi);
            }
            
            // Sincronizar hora con NTP cuando WiFi se conecta
            ESP_LOGI(TAG, "WiFi connected, synchronizing time");
            time_manager_sync_time();
            
            // Si estamos en modo STA+AP, cambiar a modo STATION solamente
            wifi_mode_t current_mode;
            if (esp_wifi_get_mode(&current_mode) == ESP_OK && current_mode == WIFI_MODE_APSTA) {
                ESP_LOGI(TAG, "Switching from AP+STA mode to STATION-only mode");
                esp_err_t ret = esp_wifi_set_mode(WIFI_MODE_STA);
                if (ret != ESP_OK) {
                    ESP_LOGE(TAG, "Failed to switch to station-only mode: %s", esp_err_to_name(ret));
                }
            }
            
            // Cuando WiFi está conectado, iniciar la conexión MQTT
            if (!mqtt_manager_is_connected()) {
                ESP_LOGI(TAG, "WiFi connected, starting MQTT connection");
                // Obtener el ID del ESP32 y configurar MQTT
                char esp32_id[ESP32_ID_LENGTH + 1];
                if (esp32_id_manager_get_id(esp32_id, sizeof(esp32_id)) == ESP_OK) {
                    ESP_LOGI(TAG, "Configuring MQTT with ESP32 ID: %s", esp32_id);
                    mqtt_manager_set_esp32_id(esp32_id);
                    
                    // Intentar conexión MQTT con más información de depuración
                    ESP_LOGI(TAG, "Attempting MQTT connection...");
                    esp_err_t mqtt_result = mqtt_manager_connect();
                    if (mqtt_result != ESP_OK) {
                        ESP_LOGE(TAG, "MQTT connection failed with error: %s (0x%x)", 
                                esp_err_to_name(mqtt_result), mqtt_result);
                    } else {
                        ESP_LOGI(TAG, "MQTT connection started successfully");
                    }
                } else {
                    ESP_LOGE(TAG, "Failed to get ESP32 ID");
                }
            }
            break;
        case WIFI_MANAGER_STATE_AP_MODE:
            ESP_LOGI(TAG, "WiFi state: AP_MODE");
            
            // Mostrar IP del AP
            char ap_ip[16];
            if (wifi_manager_get_ap_ip(ap_ip, sizeof(ap_ip)) == ESP_OK) {
                ESP_LOGI(TAG, "AP IP Address: %s", ap_ip);
            }
            break;
        case WIFI_MANAGER_STATE_STA_AP_MODE:
            ESP_LOGI(TAG, "WiFi state: STA_AP_MODE (Captive Portal)");
            break;
        case WIFI_MANAGER_STATE_ERROR:
            ESP_LOGI(TAG, "WiFi state: ERROR");
            break;
        default:
            ESP_LOGI(TAG, "WiFi state: UNKNOWN");
            break;
    }
}

// Callback cuando el usuario configura WiFi a través del portal cautivo
static void on_wifi_connect_callback(void *user_data) {
    ESP_LOGI(TAG, "WiFi configured successfully via captive portal!");
    
    // Detener el portal cautivo 
    if (wifi_captive_portal_is_active()) {
        wifi_captive_portal_stop();
        
        // Una vez conectado al WiFi del cliente, cambiar a modo STATION solamente
        // Esto desactiva el AP del ESP32 y solo mantiene la conexión al router
        esp_err_t ret = esp_wifi_set_mode(WIFI_MODE_STA);
        if (ret != ESP_OK) {
            ESP_LOGE(TAG, "Failed to switch to station-only mode: %s", esp_err_to_name(ret));
        } else {
            ESP_LOGI(TAG, "Successfully switched to station-only mode");
        }
    }
}

void app_main(void)
{
    ESP_LOGI(TAG, "Initializing HDD ESP32 Monitor...");

    // Inicializar el gestor de configuración
    ESP_ERROR_CHECK(config_manager_init());
    ESP_LOGI(TAG, "Configuration manager initialized successfully");
    
    // Inicializar el gestor de ID de ESP32
    ESP_ERROR_CHECK(esp32_id_manager_init());
    
    // Obtener y mostrar el ID de ESP32
    char esp32_id[ESP32_ID_LENGTH + 1];
    ESP_ERROR_CHECK(esp32_id_manager_get_id(esp32_id, sizeof(esp32_id)));
    ESP_LOGI(TAG, "ESP32 ID: %s", esp32_id);
    
    // Obtener y mostrar la dirección MAC
    char mac_address[ESP32_MAC_STR_LENGTH + 1];
    ESP_ERROR_CHECK(esp32_id_manager_get_mac(mac_address, sizeof(mac_address)));
    ESP_LOGI(TAG, "MAC Address: %s", mac_address);
    
    // Inicializar WiFi Manager
    ESP_ERROR_CHECK(wifi_manager_init());
    ESP_LOGI(TAG, "WiFi Manager initialized successfully");
    
    // Registrar callback para recibir cambios de estado WiFi
    ESP_ERROR_CHECK(wifi_manager_set_state_callback(wifi_state_callback, NULL));
    
    // Inicializar Time Manager
    ESP_ERROR_CHECK(time_manager_init());
    ESP_LOGI(TAG, "Time Manager initialized successfully");
    
    // Inicializar MQTT Manager
    ESP_ERROR_CHECK(mqtt_manager_init());
    ESP_LOGI(TAG, "MQTT Manager initialized successfully");
    
    // Registrar callbacks para MQTT
    ESP_ERROR_CHECK(mqtt_manager_set_state_callback(mqtt_state_callback, NULL));
    ESP_ERROR_CHECK(mqtt_manager_set_message_callback(mqtt_message_callback, NULL));
    
    // Configurar MQTT con el ID del ESP32
    ESP_ERROR_CHECK(mqtt_manager_set_esp32_id(esp32_id));
    
    // Intentar conectar con credenciales guardadas
    esp_err_t ret = wifi_manager_connect_saved();
    if (ret != ESP_OK) {
        ESP_LOGW(TAG, "No saved credentials or connection failed");
        
        // Generar SSID único para el AP basado en el ID del ESP32
        char ap_ssid[33];
        ESP_ERROR_CHECK(wifi_manager_generate_ap_ssid(ap_ssid, sizeof(ap_ssid), "FirePanel"));
        
        // Iniciar portal cautivo para configuración
        ESP_LOGI(TAG, "Starting captive portal with SSID: %s", ap_ssid);
        ESP_ERROR_CHECK(wifi_captive_portal_start(ap_ssid, "firepanel"));
        
        // Registrar callback para cuando se configure el WiFi
        ESP_ERROR_CHECK(wifi_captive_portal_set_on_connect_callback(on_wifi_connect_callback, NULL));
    } else {
        // Si WiFi está conectado, iniciar MQTT
        if (wifi_manager_is_connected()) {
            ESP_LOGI(TAG, "WiFi connected, starting MQTT connection");
            // Intentar sincronizar hora primero
            time_manager_sync_time();
            mqtt_manager_connect();
        }
    }
    
    // Bucle principal
    int counter = 0;
    bool mqtt_connection_attempted = false;

    while (1) {
        counter++;

        if (counter % 60 == 0) {  // Cada 5 minutos (60 * 5 segundos)
            print_memory_info();
        }
        
        // Comprobar si la hora está sincronizada
        if (wifi_manager_is_connected() && !time_manager_is_synchronized()) {
            time_manager_check_sync();
        }
        
        // Verificar estado WiFi y MQTT
        if (wifi_manager_is_connected()) {
            // Si WiFi está conectado pero MQTT no, intentar iniciar la conexión MQTT
            if (!mqtt_manager_is_connected() && !mqtt_connection_attempted) {
                ESP_LOGI(TAG, "WiFi connected, starting MQTT connection");
                mqtt_connection_attempted = true;
                
                // Obtener el ID del ESP32 y configurar MQTT
                char esp32_id_local[ESP32_ID_LENGTH + 1];
                if (esp32_id_manager_get_id(esp32_id_local, sizeof(esp32_id_local)) == ESP_OK) {
                    mqtt_manager_set_esp32_id(esp32_id_local);
                    mqtt_manager_connect();
                }
            }
            
            // Procesar MQTT (mensajes entrantes, reconexiones, etc.)
            mqtt_manager_loop(0);
            
            // Verificar si estamos en modo AP+STA y cambiar a solo STA
            wifi_mode_t current_mode;
            if (esp_wifi_get_mode(&current_mode) == ESP_OK && current_mode == WIFI_MODE_APSTA) {
                ESP_LOGI(TAG, "WiFi connected but still in AP+STA mode, switching to STA only");
                esp_wifi_set_mode(WIFI_MODE_STA);
            }
            
            if (mqtt_manager_is_connected()) {
                // Mostrar la hora actual si está sincronizada
                if (time_manager_is_synchronized()) {
                    char time_str[32];
                    time_manager_get_lima_time_str(time_str, sizeof(time_str));
                    ESP_LOGI(TAG, "System running with ID: %s, WiFi and MQTT connected (iteration %d), time: %s", 
                             esp32_id, counter, time_str);
                } else {
                    ESP_LOGI(TAG, "System running with ID: %s, WiFi and MQTT connected (iteration %d)", esp32_id, counter);
                }
            } else {
                ESP_LOGI(TAG, "System running with ID: %s, WiFi connected, MQTT disconnected (iteration %d)", esp32_id, counter);
                
                // Reintentar MQTT cada 30 iteraciones (150 segundos = 2.5 minutos)
                if (counter % 30 == 0) {
                    ESP_LOGI(TAG, "Retrying MQTT connection periodically");
                    mqtt_manager_connect();
                }
            }
        } else {
            ESP_LOGI(TAG, "System running with ID: %s, WiFi not connected (iteration %d)", esp32_id, counter);
            mqtt_connection_attempted = false;  // Resetear el flag cuando WiFi se desconecta
            
            // Manejar la desconexión WiFi - intentar reconectar o activar AP si falla
            wifi_manager_handle_disconnection("FirePanel", "firepanel", 5);
        }
        
        vTaskDelay(5000 / portTICK_PERIOD_MS);
    }
}