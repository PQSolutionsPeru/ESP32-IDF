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
            
            // ✅ SOLO LOG - El mensaje se enviará automáticamente después de NTP sync
            ESP_LOGI(TAG, "MQTT connected. Network info will be sent after time synchronization.");
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

// WiFi callback ULTRA-OPTIMIZADO para ESP32 4MB
static void wifi_state_callback(wifi_manager_state_t state, void *user_data) {
    static bool mqtt_setup_done = false;  // Flag para evitar setup múltiple
    
    switch (state) {
        case WIFI_MANAGER_STATE_INIT:
            ESP_LOGI(TAG, "WiFi: INIT");
            mqtt_setup_done = false;
            break;
        case WIFI_MANAGER_STATE_DISCONNECTED:
            ESP_LOGI(TAG, "WiFi: DISCONNECTED");
            mqtt_setup_done = false;
            break;
        case WIFI_MANAGER_STATE_CONNECTING:
            ESP_LOGI(TAG, "WiFi: CONNECTING");
            break;
        case WIFI_MANAGER_STATE_CONNECTED:
            ESP_LOGI(TAG, "WiFi: CONNECTED");
            
            // Información básica sin arrays grandes
            char ip[16];
            if (wifi_manager_get_ip(ip, sizeof(ip)) == ESP_OK) {
                ESP_LOGI(TAG, "IP: %s", ip);
            }
            
            int8_t rssi;
            if (wifi_manager_get_rssi(&rssi) == ESP_OK) {
                ESP_LOGI(TAG, "Signal: %d dBm", rssi);
            }
            
            // Setup MQTT una sola vez
            if (!mqtt_setup_done) {
                mqtt_setup_done = true;
                
                // Sincronizar tiempo
                ESP_LOGI(TAG, "Synchronizing time");
                time_manager_sync_time();
                
                // Cambiar a modo STA si es necesario
                wifi_mode_t mode;
                if (esp_wifi_get_mode(&mode) == ESP_OK && mode == WIFI_MODE_APSTA) {
                    ESP_LOGI(TAG, "Switching to STA-only mode");
                    esp_err_t ret = esp_wifi_set_mode(WIFI_MODE_STA);
                    if (ret != ESP_OK) {
                        ESP_LOGE(TAG, "Failed to switch mode: %s", esp_err_to_name(ret));
                    }
                }
                
                // Setup MQTT minimalista
                mqtt_manager_state_t mqtt_state = mqtt_manager_get_state();
                if (mqtt_state != MQTT_MANAGER_STATE_CONNECTED && 
                    mqtt_state != MQTT_MANAGER_STATE_CONNECTING) {
                    
                    char esp32_id[ESP32_ID_LENGTH + 1];
                    if (esp32_id_manager_get_id(esp32_id, sizeof(esp32_id)) == ESP_OK) {
                        ESP_LOGI(TAG, "Configuring MQTT with ID: %s", esp32_id);
                        if (mqtt_manager_set_esp32_id(esp32_id) == ESP_OK) {
                            ESP_LOGI(TAG, "Attempting MQTT connection...");
                            esp_err_t result = mqtt_manager_connect();
                            if (result != ESP_OK) {
                                ESP_LOGE(TAG, "MQTT connection failed: %s", esp_err_to_name(result));
                            } else {
                                ESP_LOGI(TAG, "MQTT connection started");
                            }
                        } else {
                            ESP_LOGE(TAG, "Failed to set ESP32 ID");
                        }
                    } else {
                        ESP_LOGE(TAG, "Failed to get ESP32 ID");
                    }
                } else {
                    ESP_LOGI(TAG, "MQTT already in progress, state: %d", mqtt_state);
                }
            }
            break;
        case WIFI_MANAGER_STATE_AP_MODE:
            ESP_LOGI(TAG, "WiFi: AP_MODE");
            mqtt_setup_done = false;
            
            char ap_ip[16];
            if (wifi_manager_get_ap_ip(ap_ip, sizeof(ap_ip)) == ESP_OK) {
                ESP_LOGI(TAG, "AP IP: %s", ap_ip);
            }
            break;
        case WIFI_MANAGER_STATE_STA_AP_MODE:
            ESP_LOGI(TAG, "WiFi: STA_AP_MODE");
            break;
        case WIFI_MANAGER_STATE_ERROR:
            ESP_LOGI(TAG, "WiFi: ERROR");
            mqtt_setup_done = false;
            break;
        default:
            ESP_LOGI(TAG, "WiFi: UNKNOWN");
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
    
    // Bucle principal ULTRA-OPTIMIZADO para ESP32 4MB
    int counter = 0;
    int last_memory_check = 0;
    int last_mqtt_retry = 0;

    while (1) {
        counter++;

        // Reporte de memoria solo cada 10 minutos para reducir overhead
        if (counter - last_memory_check >= 120) {  // 120 * 5s = 10 minutos
            print_memory_info();
            last_memory_check = counter;
        }
        
        // Verificar tiempo de forma minimalista
        if (wifi_manager_is_connected() && !time_manager_is_synchronized()) {
            time_manager_check_sync();
        }
        
        // Estados simples sin variables complejas
        bool wifi_ok = wifi_manager_is_connected();
        mqtt_manager_state_t mqtt_state = mqtt_manager_get_state();
        bool mqtt_ok = (mqtt_state == MQTT_MANAGER_STATE_CONNECTED);
        
        if (wifi_ok) {
            // Cambio de modo WiFi minimalista
            wifi_mode_t mode;
            if (esp_wifi_get_mode(&mode) == ESP_OK && mode == WIFI_MODE_APSTA) {
                ESP_LOGI(TAG, "Still in AP+STA mode, switching to STA only");
                esp_wifi_set_mode(WIFI_MODE_STA);
            }
            
            // Procesar MQTT
            mqtt_manager_loop(0);
            
            if (mqtt_ok) {
                // Log cada 2 minutos cuando todo funciona
                if (counter % 24 == 0) {  // 24 * 5s = 2 minutos
                    if (time_manager_is_synchronized()) {
                        char time_str[32];
                        time_manager_get_lima_time_str(time_str, sizeof(time_str));
                        ESP_LOGI(TAG, "System OK - WiFi+MQTT connected, Time: %s", time_str);
                    } else {
                        ESP_LOGI(TAG, "System OK - WiFi+MQTT connected");
                    }
                }
            } else {
                // Log cada minuto cuando MQTT falla
                if (counter % 12 == 0) {  // 12 * 5s = 1 minuto
                    ESP_LOGI(TAG, "WiFi OK, MQTT: %s", 
                            mqtt_state == MQTT_MANAGER_STATE_CONNECTING ? "CONNECTING" :
                            mqtt_state == MQTT_MANAGER_STATE_RECONNECTING ? "RECONNECTING" :
                            mqtt_state == MQTT_MANAGER_STATE_ERROR ? "ERROR" : "DISCONNECTED");
                }
                
                // Reintentar MQTT cada 2 minutos
                if ((counter - last_mqtt_retry) >= 24 && 
                    mqtt_state != MQTT_MANAGER_STATE_CONNECTING) {
                    
                    ESP_LOGI(TAG, "Retrying MQTT connection (state: %d)", mqtt_state);
                    char esp32_id_local[ESP32_ID_LENGTH + 1];
                    if (esp32_id_manager_get_id(esp32_id_local, sizeof(esp32_id_local)) == ESP_OK) {
                        mqtt_manager_set_esp32_id(esp32_id_local);
                        mqtt_manager_connect();
                    }
                    last_mqtt_retry = counter;
                }
            }
        } else {
            // WiFi desconectado - log cada minuto
            if (counter % 12 == 0) {
                ESP_LOGI(TAG, "System running - WiFi DISCONNECTED");
            }
            
            // Manejo simple de desconexión
            wifi_manager_handle_disconnection("FirePanel", "firepanel", 3);  // Menos reintentos
        }
        
        // Pausa eficiente
        vTaskDelay(pdMS_TO_TICKS(5000));
    }
}