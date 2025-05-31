#include "wifi_manager.h"
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "esp_wifi.h"
#include "esp_log.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_mac.h"
#include "esp_system.h"
#include "nvs_flash.h"   // Para nvs_flash_init
#include <inttypes.h>    // Para PRIu32, PRIi32
#include "config_manager.h"     // Añadido para las funciones de config_manager
#include "esp32_id_manager.h"   // Añadido para ESP32_ID_LENGTH
#include "esp_timer.h"

#define TAG "WIFI_MGR"

// Definición de bits para el grupo de eventos
#define WIFI_CONNECTED_BIT BIT0
#define WIFI_DISCONNECTED_BIT BIT1
#define WIFI_AP_STARTED_BIT BIT2
#define WIFI_SCAN_DONE_BIT BIT3
#define WIFI_CONNECT_FAIL_BIT BIT4

// Máximo de intentos de reconexión antes de dar por fallida la conexión
#define MAX_RECONNECT_ATTEMPTS 5

// Tiempo máximo de espera para conexión (en milisegundos)
#define WIFI_CONNECT_TIMEOUT_MS 20000

// Tiempo entre intentos de reconexión (en milisegundos)
#define RECONNECT_DELAY_MS 2000

// IP por defecto para el modo AP
#define DEFAULT_AP_IP "192.168.4.1"

// Estructura para manejar el estado del WiFi Manager
typedef struct {
    wifi_manager_state_t state;
    EventGroupHandle_t event_group;
    esp_netif_t *sta_netif;
    esp_netif_t *ap_netif;
    char ssid[33];
    char password[65];
    char ap_ssid[33];
    char ap_password[65];
    bool credentials_saved;
    int reconnect_attempts;
    wifi_ap_record_t ap_info;
    wifi_scan_config_t scan_config;
    uint16_t scan_ap_count;
    wifi_ap_record_t *scan_ap_list;
    bool scan_in_progress;
    bool temporary_apsta_mode;  // NUEVO: indica si cambiamos temporalmente a AP+STA para scan
    void (*state_callback)(wifi_manager_state_t state, void *user_data);
    void *user_data;
} wifi_manager_context_t;

// Instancia única del contexto del WiFi Manager (patrón singleton)
static wifi_manager_context_t s_wifi_manager_ctx = {0};

// Handler de eventos WiFi
static void wifi_event_handler(void *arg, esp_event_base_t event_base,
                               int32_t event_id, void *event_data)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (event_base == WIFI_EVENT) {
        switch (event_id) {
            case WIFI_EVENT_STA_START:
                ESP_LOGI(TAG, "WiFi station started");
                break;
            
            case WIFI_EVENT_STA_CONNECTED:
                ESP_LOGI(TAG, "Connected to WiFi network: %s", ctx->ssid);
                /* La IP se asignará en el evento IP_EVENT_STA_GOT_IP */
                break;
            
            case WIFI_EVENT_STA_DISCONNECTED: {
                wifi_event_sta_disconnected_t *disconn = (wifi_event_sta_disconnected_t*) event_data;
                // Usar %d en lugar de %" PRIi32" para evitar problemas de formato
                ESP_LOGW(TAG, "Disconnected from WiFi network: %s, reason: %d", 
                        ctx->ssid, (int)disconn->reason);
                
                // Marcar como desconectado solo si no estamos en modo combinado
                if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
                    xEventGroupClearBits(ctx->event_group, WIFI_CONNECTED_BIT);
                    xEventGroupSetBits(ctx->event_group, WIFI_DISCONNECTED_BIT);
                }
                
                // Si teníamos credenciales, intentar reconexión automática
                if (ctx->credentials_saved && ctx->reconnect_attempts < MAX_RECONNECT_ATTEMPTS) {
                    ctx->reconnect_attempts++;
                    ESP_LOGI(TAG, "Attempting reconnection %d/%d in %d ms...", 
                            ctx->reconnect_attempts, MAX_RECONNECT_ATTEMPTS, RECONNECT_DELAY_MS);
                    
                    // Actualizar estado solo si no estamos en modo combinado
                    if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
                        ctx->state = WIFI_MANAGER_STATE_CONNECTING;
                        if (ctx->state_callback) {
                            ctx->state_callback(ctx->state, ctx->user_data);
                        }
                    }
                    
                    // Esperar antes de reconectar
                    vTaskDelay(pdMS_TO_TICKS(RECONNECT_DELAY_MS));
                    esp_wifi_connect();
                } else if (ctx->reconnect_attempts >= MAX_RECONNECT_ATTEMPTS) {
                    // Límite de intentos alcanzado
                    ESP_LOGE(TAG, "Max reconnection attempts reached");
                    xEventGroupSetBits(ctx->event_group, WIFI_CONNECT_FAIL_BIT);
                    
                    // Si estamos en modo combinado, permanecemos en ese modo
                    if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
                        ctx->state = WIFI_MANAGER_STATE_ERROR;
                        if (ctx->state_callback) {
                            ctx->state_callback(ctx->state, ctx->user_data);
                        }
                    }
                } else {
                    // Actualizar estado solo si no estamos en modo combinado
                    if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
                        ctx->state = WIFI_MANAGER_STATE_DISCONNECTED;
                        if (ctx->state_callback) {
                            ctx->state_callback(ctx->state, ctx->user_data);
                        }
                    }
                }
                break;
            }
            
            case WIFI_EVENT_AP_START:
                ESP_LOGI(TAG, "WiFi Access Point started");
                xEventGroupSetBits(ctx->event_group, WIFI_AP_STARTED_BIT);
                
                // Solo actualizamos el estado si no estamos en modo combinado
                if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
                    ctx->state = WIFI_MANAGER_STATE_AP_MODE;
                    if (ctx->state_callback) {
                        ctx->state_callback(ctx->state, ctx->user_data);
                    }
                }
                break;
                
            case WIFI_EVENT_AP_STOP:
                ESP_LOGI(TAG, "WiFi Access Point stopped");
                xEventGroupClearBits(ctx->event_group, WIFI_AP_STARTED_BIT);
                break;
                
            case WIFI_EVENT_AP_STACONNECTED: {
                wifi_event_ap_staconnected_t* event = (wifi_event_ap_staconnected_t*) event_data;
                ESP_LOGI(TAG, "Station connected to AP - MAC: " MACSTR, MAC2STR(event->mac));
                break;
            }
                
            case WIFI_EVENT_AP_STADISCONNECTED: {
                wifi_event_ap_stadisconnected_t* event = (wifi_event_ap_stadisconnected_t*) event_data;
                ESP_LOGI(TAG, "Station disconnected from AP - MAC: " MACSTR, MAC2STR(event->mac));
                break;
            }
                
            case WIFI_EVENT_SCAN_DONE: {
                wifi_event_sta_scan_done_t *scan_done = (wifi_event_sta_scan_done_t*) event_data;
                ESP_LOGI(TAG, "WiFi scan completed, status: %d, found APs: %u",
                         (int)scan_done->status, (unsigned int)scan_done->number);
                
                // Marcar el escaneo como completado
                ctx->scan_in_progress = false;
                
                if (scan_done->status == 0) {
                    // Liberar los resultados anteriores si existen
                    if (ctx->scan_ap_list != NULL) {
                        free(ctx->scan_ap_list);
                        ctx->scan_ap_list = NULL;
                    }
                    
                    ctx->scan_ap_count = scan_done->number;
                    if (ctx->scan_ap_count > 0) {
                        ctx->scan_ap_list = (wifi_ap_record_t*)malloc(ctx->scan_ap_count * sizeof(wifi_ap_record_t));
                        if (ctx->scan_ap_list == NULL) {
                            ESP_LOGE(TAG, "Failed to allocate memory for scan results");
                            ctx->scan_ap_count = 0;
                        } else {
                            // IMPORTANTE: Obtener resultados ANTES de cambiar modo
                            esp_err_t get_ret = esp_wifi_scan_get_ap_records(&ctx->scan_ap_count, ctx->scan_ap_list);
                            if (get_ret == ESP_OK) {
                                ESP_LOGI(TAG, "Successfully retrieved %u scan results", ctx->scan_ap_count);
                                
                                // Ordenar por RSSI (de más fuerte a más débil)
                                for (int i = 0; i < ctx->scan_ap_count - 1; i++) {
                                    for (int j = i + 1; j < ctx->scan_ap_count; j++) {
                                        if (ctx->scan_ap_list[j].rssi > ctx->scan_ap_list[i].rssi) {
                                            wifi_ap_record_t temp = ctx->scan_ap_list[i];
                                            ctx->scan_ap_list[i] = ctx->scan_ap_list[j];
                                            ctx->scan_ap_list[j] = temp;
                                        }
                                    }
                                }
                            } else {
                                ESP_LOGE(TAG, "Failed to get scan records: %s", esp_err_to_name(get_ret));
                                free(ctx->scan_ap_list);
                                ctx->scan_ap_list = NULL;
                                ctx->scan_ap_count = 0;
                            }
                        }
                    }
                }
                
                // AHORA SÍ cambiar modo después de obtener resultados
                if (ctx->temporary_apsta_mode) {
                    ESP_LOGI(TAG, "Reverting to AP mode after scan completion");
                    ctx->temporary_apsta_mode = false;
                    
                    // Solo volver a AP si no estamos conectados como STA
                    if (!wifi_manager_is_connected()) {
                        esp_err_t mode_ret = esp_wifi_set_mode(WIFI_MODE_AP);
                        if (mode_ret != ESP_OK) {
                            ESP_LOGE(TAG, "Failed to revert to AP mode: %s", esp_err_to_name(mode_ret));
                        }
                    }
                }
                
                xEventGroupSetBits(ctx->event_group, WIFI_SCAN_DONE_BIT);
                break;
            }
            
            default:
                // Usar %d en lugar de %" PRIi32" para evitar problemas de formato
                ESP_LOGD(TAG, "Unhandled WiFi event: %d", (int)event_id);
                break;
        }
    } else if (event_base == IP_EVENT) {
        switch (event_id) {
            case IP_EVENT_STA_GOT_IP: {
                ip_event_got_ip_t *event = (ip_event_got_ip_t*) event_data;
                ESP_LOGI(TAG, "Got IP address: " IPSTR, IP2STR(&event->ip_info.ip));
                
                // Marcar como conectado
                xEventGroupClearBits(ctx->event_group, WIFI_DISCONNECTED_BIT | WIFI_CONNECT_FAIL_BIT);
                xEventGroupSetBits(ctx->event_group, WIFI_CONNECTED_BIT);
                
                // Reiniciar contador de intentos de reconexión
                ctx->reconnect_attempts = 0;
                
                // Actualizar estado
                if (ctx->state == WIFI_MANAGER_STATE_AP_MODE || 
                    ctx->state == WIFI_MANAGER_STATE_STA_AP_MODE) {
                    // Si estamos en modo AP o combinado, mantenemos ese estado
                    ctx->state = WIFI_MANAGER_STATE_STA_AP_MODE;
                } else {
                    // De lo contrario, estamos solo en modo conectado
                    ctx->state = WIFI_MANAGER_STATE_CONNECTED;
                }
                
                if (ctx->state_callback) {
                    ctx->state_callback(ctx->state, ctx->user_data);
                }
                
                // Obtener información del AP
                if (esp_wifi_sta_get_ap_info(&ctx->ap_info) != ESP_OK) {
                    ESP_LOGW(TAG, "Failed to get AP info");
                }
                break;
            }
            
            case IP_EVENT_STA_LOST_IP:
                ESP_LOGW(TAG, "Lost IP address");
                if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
                    xEventGroupClearBits(ctx->event_group, WIFI_CONNECTED_BIT);
                }
                break;
                
            default:
                // Usar %d en lugar de %" PRIi32" para evitar problemas de formato
                ESP_LOGD(TAG, "Unhandled IP event: %d", (int)event_id);
                break;
        }
    }
}

// Inicialización del WiFi Manager
esp_err_t wifi_manager_init(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    esp_err_t ret = ESP_OK;
    
    // Evitar inicialización múltiple
    if (ctx->event_group != NULL) {
        ESP_LOGW(TAG, "WiFi Manager already initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Initializing WiFi Manager");
    
    // Inicializar estado
    ctx->state = WIFI_MANAGER_STATE_INIT;
    ctx->scan_in_progress = false;
    ctx->temporary_apsta_mode = false;  // NUEVO: inicializar flag de modo temporal
    ctx->event_group = xEventGroupCreate();
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "Failed to create event group");
        return ESP_ERR_NO_MEM;
    }
    
    // Inicializar NVS para almacenar configuración WiFi si es necesario
    ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_LOGW(TAG, "NVS needs to be erased");
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);
    
    // Inicializar TCP/IP stack
    ESP_LOGI(TAG, "Initializing TCP/IP stack");
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    
    // Crear netif para modo estación
    ESP_LOGI(TAG, "Creating WiFi station netif");
    ctx->sta_netif = esp_netif_create_default_wifi_sta();
    if (ctx->sta_netif == NULL) {
        ESP_LOGE(TAG, "Failed to create station netif");
        vEventGroupDelete(ctx->event_group);
        ctx->event_group = NULL;
        return ESP_FAIL;
    }
    
    // Crear netif para modo AP
    ESP_LOGI(TAG, "Creating WiFi AP netif");
    ctx->ap_netif = esp_netif_create_default_wifi_ap();
    if (ctx->ap_netif == NULL) {
        ESP_LOGE(TAG, "Failed to create AP netif");
        esp_netif_destroy(ctx->sta_netif);
        vEventGroupDelete(ctx->event_group);
        ctx->event_group = NULL;
        return ESP_FAIL;
    }
    
    // Configuración WiFi por defecto
    wifi_init_config_t wifi_init_config = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&wifi_init_config));
    
    // Registrar handlers de eventos
    ESP_LOGI(TAG, "Registering event handlers");
    ESP_ERROR_CHECK(esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                                       &wifi_event_handler, NULL, NULL));
    ESP_ERROR_CHECK(esp_event_handler_instance_register(IP_EVENT, ESP_EVENT_ANY_ID,
                                                       &wifi_event_handler, NULL, NULL));
    
    // Inicializar WiFi en modo estación por defecto
    ESP_LOGI(TAG, "Setting WiFi to station mode");
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_start());
    
    // Cargar credenciales guardadas
    ret = config_manager_get_str("wifi_ssid", ctx->ssid, sizeof(ctx->ssid));
    if (ret == ESP_OK) {
        ret = config_manager_get_str("wifi_password", ctx->password, sizeof(ctx->password));
        if (ret == ESP_OK) {
            ESP_LOGI(TAG, "Found saved WiFi credentials for SSID: %s", ctx->ssid);
            ctx->credentials_saved = true;
        } else {
            ctx->credentials_saved = false;
        }
    } else {
        ctx->credentials_saved = false;
    }
    
    // Configurar para escaneo
    memset(&ctx->scan_config, 0, sizeof(ctx->scan_config));
    ctx->scan_config.show_hidden = true;
    ctx->scan_config.scan_type = WIFI_SCAN_TYPE_ACTIVE;
    ctx->scan_config.scan_time.active.min = 100;
    ctx->scan_config.scan_time.active.max = 300;
    
    // Inicialización completada
    ctx->state = WIFI_MANAGER_STATE_DISCONNECTED;
    xEventGroupSetBits(ctx->event_group, WIFI_DISCONNECTED_BIT);
    
    ESP_LOGI(TAG, "WiFi Manager initialized successfully");
    return ESP_OK;
}

// Conexión a una red WiFi
esp_err_t wifi_manager_connect(const char *ssid, const char *password, bool save)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ssid == NULL) {
        ESP_LOGE(TAG, "SSID is NULL");
        return ESP_ERR_INVALID_ARG;
    }
    
    // Verificar longitud de SSID y password
    if (strlen(ssid) > 32) {
        ESP_LOGE(TAG, "SSID too long");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (password != NULL && strlen(password) > 64) {
        ESP_LOGE(TAG, "Password too long");
        return ESP_ERR_INVALID_ARG;
    }
    
    ESP_LOGI(TAG, "Connecting to WiFi network: %s", ssid);
    
    // Desconectar si ya estábamos conectados en modo STA puro
    if (ctx->state == WIFI_MANAGER_STATE_CONNECTED || 
        ctx->state == WIFI_MANAGER_STATE_CONNECTING) {
        ESP_LOGI(TAG, "Disconnecting from current network before connecting to new one");
        ESP_ERROR_CHECK(esp_wifi_disconnect());
        
        // Esperar a que se desconecte
        EventBits_t bits = xEventGroupWaitBits(ctx->event_group, 
                                              WIFI_DISCONNECTED_BIT,
                                              pdFALSE, pdFALSE, pdMS_TO_TICKS(5000));
        if ((bits & WIFI_DISCONNECTED_BIT) == 0) {
            ESP_LOGW(TAG, "Timeout waiting for disconnection");
        }
    }
    
    // Guardar credenciales
    strncpy(ctx->ssid, ssid, sizeof(ctx->ssid) - 1);
    ctx->ssid[sizeof(ctx->ssid) - 1] = '\0';
    
    if (password != NULL) {
        strncpy(ctx->password, password, sizeof(ctx->password) - 1);
        ctx->password[sizeof(ctx->password) - 1] = '\0';
    } else {
        ctx->password[0] = '\0';
    }
    
    // Guardar credenciales en NVS si se solicita
    if (save) {
        ESP_LOGI(TAG, "Saving WiFi credentials");
        ESP_ERROR_CHECK(config_manager_set_str("wifi_ssid", ctx->ssid));
        ESP_ERROR_CHECK(config_manager_set_str("wifi_password", ctx->password));
        ctx->credentials_saved = true;
    }
    
    // Configurar WiFi
    wifi_config_t wifi_config = {0};
    strncpy((char *)wifi_config.sta.ssid, ctx->ssid, sizeof(wifi_config.sta.ssid) - 1);
    if (ctx->password[0] != '\0') {
        strncpy((char *)wifi_config.sta.password, ctx->password, sizeof(wifi_config.sta.password) - 1);
    }
    
    // Configurar para reconexión automática
    wifi_config.sta.scan_method = WIFI_FAST_SCAN;
    wifi_config.sta.sort_method = WIFI_CONNECT_AP_BY_SIGNAL;
    wifi_config.sta.threshold.rssi = -127;
    wifi_config.sta.threshold.authmode = WIFI_AUTH_WPA2_PSK;
    
    // Verificar si estamos en modo AP o combinado
    wifi_mode_t current_mode;
    ESP_ERROR_CHECK(esp_wifi_get_mode(&current_mode));
    
    if (current_mode == WIFI_MODE_AP) {
        // Cambiar a modo combinado
        ESP_LOGI(TAG, "Changing from AP to STA+AP mode");
        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_APSTA));
        ctx->state = WIFI_MANAGER_STATE_STA_AP_MODE;
    } else if (current_mode == WIFI_MODE_APSTA) {
        // Ya estamos en modo combinado, solo actualizamos la configuración STA
        ESP_LOGI(TAG, "Updating STA configuration in STA+AP mode");
    } else {
        // Asegurar que estamos en modo STA
        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    }
    
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wifi_config));
    
    // Actualizar estado
    if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
        ctx->state = WIFI_MANAGER_STATE_CONNECTING;
    }
    ctx->reconnect_attempts = 0;
    if (ctx->state_callback) {
        ctx->state_callback(ctx->state, ctx->user_data);
    }
    
    // Iniciar conexión
    ESP_ERROR_CHECK(esp_wifi_connect());
    
    // Esperar conexión con timeout
    EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                          WIFI_CONNECTED_BIT | WIFI_CONNECT_FAIL_BIT,
                                          pdFALSE, pdFALSE, pdMS_TO_TICKS(WIFI_CONNECT_TIMEOUT_MS));
    
    // Verificar resultado
    if (bits & WIFI_CONNECTED_BIT) {
        ESP_LOGI(TAG, "Successfully connected to WiFi network");
        return ESP_OK;
    } else if (bits & WIFI_CONNECT_FAIL_BIT) {
        ESP_LOGE(TAG, "Failed to connect to WiFi network");
        // Solo actualizamos el estado si no estamos en modo combinado
        if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
            ctx->state = WIFI_MANAGER_STATE_ERROR;
            if (ctx->state_callback) {
                ctx->state_callback(ctx->state, ctx->user_data);
            }
        }
        return ESP_FAIL;
    } else {
        ESP_LOGW(TAG, "Connection attempt timed out");
        // Solo actualizamos el estado si no estamos en modo combinado
        if (ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
            ctx->state = WIFI_MANAGER_STATE_ERROR;
            if (ctx->state_callback) {
                ctx->state_callback(ctx->state, ctx->user_data);
            }
        }
        return ESP_ERR_TIMEOUT;
    }
}

// Conexión usando credenciales guardadas
esp_err_t wifi_manager_connect_saved(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (!ctx->credentials_saved) {
        ESP_LOGW(TAG, "No saved WiFi credentials");
        return ESP_ERR_NOT_FOUND;
    }
    
    ESP_LOGI(TAG, "Connecting with saved credentials, SSID: %s", ctx->ssid);
    return wifi_manager_connect(ctx->ssid, ctx->password, false);
}

// Desconexión de la red WiFi
esp_err_t wifi_manager_disconnect(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ctx->state != WIFI_MANAGER_STATE_CONNECTED && 
        ctx->state != WIFI_MANAGER_STATE_CONNECTING &&
        ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
        ESP_LOGW(TAG, "Not connected to WiFi");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Disconnecting from WiFi network: %s", ctx->ssid);
    ESP_ERROR_CHECK(esp_wifi_disconnect());
    
    // Si estamos en modo combinado, solo desconectamos la parte STA pero mantenemos el AP
    if (ctx->state == WIFI_MANAGER_STATE_STA_AP_MODE) {
        ESP_LOGI(TAG, "Remaining in AP mode after STA disconnection");
        ctx->state = WIFI_MANAGER_STATE_AP_MODE;
        if (ctx->state_callback) {
            ctx->state_callback(ctx->state, ctx->user_data);
        }
        return ESP_OK;
    }
    
    // Para otros modos, esperamos la desconexión completa
    EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                          WIFI_DISCONNECTED_BIT,
                                          pdFALSE, pdFALSE, pdMS_TO_TICKS(5000));
    
    if (bits & WIFI_DISCONNECTED_BIT) {
        ESP_LOGI(TAG, "Successfully disconnected from WiFi");
        return ESP_OK;
    } else {
        ESP_LOGW(TAG, "Timeout waiting for disconnection");
        return ESP_ERR_TIMEOUT;
    }
}

// Iniciar modo Access Point
esp_err_t wifi_manager_start_ap_mode(const char *ap_ssid, const char *ap_password)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ap_ssid == NULL) {
        ESP_LOGE(TAG, "AP SSID is NULL");
        return ESP_ERR_INVALID_ARG;
    }
    
    // Verificar longitud de SSID y password
    if (strlen(ap_ssid) > 32) {
        ESP_LOGE(TAG, "AP SSID too long");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (ap_password != NULL && strlen(ap_password) > 64) {
        ESP_LOGE(TAG, "AP Password too long");
        return ESP_ERR_INVALID_ARG;
    }
    
    ESP_LOGI(TAG, "Starting WiFi Access Point: %s", ap_ssid);
    
    // Desconectar si estábamos en modo estación
    if (ctx->state == WIFI_MANAGER_STATE_CONNECTED || 
        ctx->state == WIFI_MANAGER_STATE_CONNECTING) {
        ESP_LOGI(TAG, "Disconnecting from current network before starting AP");
        ESP_ERROR_CHECK(esp_wifi_disconnect());
        
        // Esperar a que se desconecte
        EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                              WIFI_DISCONNECTED_BIT,
                                              pdFALSE, pdFALSE, pdMS_TO_TICKS(5000));
        if ((bits & WIFI_DISCONNECTED_BIT) == 0) {
            ESP_LOGW(TAG, "Timeout waiting for disconnection");
        }
    }
    
    // Guardar configuración AP
    strncpy(ctx->ap_ssid, ap_ssid, sizeof(ctx->ap_ssid) - 1);
    ctx->ap_ssid[sizeof(ctx->ap_ssid) - 1] = '\0';
    
    if (ap_password != NULL) {
        strncpy(ctx->ap_password, ap_password, sizeof(ctx->ap_password) - 1);
        ctx->ap_password[sizeof(ctx->ap_password) - 1] = '\0';
    } else {
        ctx->ap_password[0] = '\0';
    }
    
    // Configurar modo AP
    wifi_config_t ap_config = {0};
    strncpy((char *)ap_config.ap.ssid, ctx->ap_ssid, sizeof(ap_config.ap.ssid) - 1);
    ap_config.ap.ssid_len = strlen(ctx->ap_ssid);
    
    if (ctx->ap_password[0] != '\0' && strlen(ctx->ap_password) >= 8) {
        strncpy((char *)ap_config.ap.password, ctx->ap_password, sizeof(ap_config.ap.password) - 1);
        ap_config.ap.authmode = WIFI_AUTH_WPA2_PSK;
    } else {
        ap_config.ap.authmode = WIFI_AUTH_OPEN;
    }
    
    ap_config.ap.max_connection = 4;
    ap_config.ap.beacon_interval = 100;
    
    // Cambiar a modo AP
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &ap_config));
    
    // Esperar a que el AP inicie
    EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                          WIFI_AP_STARTED_BIT,
                                          pdFALSE, pdFALSE, pdMS_TO_TICKS(5000));
    
    if (bits & WIFI_AP_STARTED_BIT) {
        ESP_LOGI(TAG, "WiFi Access Point started successfully");
        return ESP_OK;
    } else {
        ESP_LOGW(TAG, "Timeout waiting for AP to start");
        return ESP_ERR_TIMEOUT;
    }
}

// Iniciar modo combinado STA+AP
esp_err_t wifi_manager_start_sta_ap_mode(const char *ap_ssid, const char *ap_password)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ap_ssid == NULL) {
        ESP_LOGE(TAG, "AP SSID is NULL");
        return ESP_ERR_INVALID_ARG;
    }
    
    // Verificar longitud de SSID y password
    if (strlen(ap_ssid) > 32) {
        ESP_LOGE(TAG, "AP SSID too long");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (ap_password != NULL && strlen(ap_password) > 64) {
        ESP_LOGE(TAG, "AP Password too long");
        return ESP_ERR_INVALID_ARG;
    }
    
    ESP_LOGI(TAG, "Starting WiFi STA+AP mode with AP: %s", ap_ssid);
    
    // Guardar configuración AP
    strncpy(ctx->ap_ssid, ap_ssid, sizeof(ctx->ap_ssid) - 1);
    ctx->ap_ssid[sizeof(ctx->ap_ssid) - 1] = '\0';
    
    if (ap_password != NULL) {
        strncpy(ctx->ap_password, ap_password, sizeof(ctx->ap_password) - 1);
        ctx->ap_password[sizeof(ctx->ap_password) - 1] = '\0';
    } else {
        ctx->ap_password[0] = '\0';
    }
    
    // Configurar modo AP
    wifi_config_t ap_config = {0};
    strncpy((char *)ap_config.ap.ssid, ctx->ap_ssid, sizeof(ap_config.ap.ssid) - 1);
    ap_config.ap.ssid_len = strlen(ctx->ap_ssid);
    
    if (ctx->ap_password[0] != '\0' && strlen(ctx->ap_password) >= 8) {
        strncpy((char *)ap_config.ap.password, ctx->ap_password, sizeof(ap_config.ap.password) - 1);
        ap_config.ap.authmode = WIFI_AUTH_WPA2_PSK;
    } else {
        ap_config.ap.authmode = WIFI_AUTH_OPEN;
    }
    
    ap_config.ap.max_connection = 4;
    ap_config.ap.beacon_interval = 100;
    
    // Cambiar a modo combinado
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_APSTA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &ap_config));
    
    // Si hay credenciales guardadas, intentar conectar en modo STA
    if (ctx->credentials_saved) {
        // Configurar WiFi STA
        wifi_config_t sta_config = {0};
        strncpy((char *)sta_config.sta.ssid, ctx->ssid, sizeof(sta_config.sta.ssid) - 1);
        if (ctx->password[0] != '\0') {
            strncpy((char *)sta_config.sta.password, ctx->password, sizeof(sta_config.sta.password) - 1);
        }
        
        // Configurar para reconexión automática
        sta_config.sta.scan_method = WIFI_FAST_SCAN;
        sta_config.sta.sort_method = WIFI_CONNECT_AP_BY_SIGNAL;
        sta_config.sta.threshold.rssi = -127;
        sta_config.sta.threshold.authmode = WIFI_AUTH_WPA2_PSK;
        
        ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &sta_config));
        
        // Iniciar conexión
        ESP_LOGI(TAG, "Trying to connect to WiFi: %s in STA+AP mode", ctx->ssid);
        ESP_ERROR_CHECK(esp_wifi_connect());
    }
    
    // Esperar a que el AP inicie
    EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                          WIFI_AP_STARTED_BIT,
                                          pdFALSE, pdFALSE, pdMS_TO_TICKS(5000));
    
    if (bits & WIFI_AP_STARTED_BIT) {
        ctx->state = WIFI_MANAGER_STATE_STA_AP_MODE;
        if (ctx->state_callback) {
            ctx->state_callback(ctx->state, ctx->user_data);
        }
        ESP_LOGI(TAG, "WiFi STA+AP mode started successfully");
        return ESP_OK;
    } else {
        ESP_LOGW(TAG, "Timeout waiting for AP to start in STA+AP mode");
        return ESP_ERR_TIMEOUT;
    }
}

// Detener modo Access Point
esp_err_t wifi_manager_stop_ap_mode(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ctx->state != WIFI_MANAGER_STATE_AP_MODE && 
        ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
        ESP_LOGW(TAG, "Not in AP or STA+AP mode");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Stopping WiFi Access Point");
    
    // Si estamos en modo combinado y conectados, cambiar a modo STA
    // Si no, cambiar a modo desconectado
    if (ctx->state == WIFI_MANAGER_STATE_STA_AP_MODE && 
        (xEventGroupGetBits(ctx->event_group) & WIFI_CONNECTED_BIT)) {
        ESP_LOGI(TAG, "Switching from STA+AP to STA mode");
        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
        ctx->state = WIFI_MANAGER_STATE_CONNECTED;
    } else {
        ESP_LOGI(TAG, "Switching to STA mode (disconnected)");
        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
        ctx->state = WIFI_MANAGER_STATE_DISCONNECTED;
    }
    
    if (ctx->state_callback) {
        ctx->state_callback(ctx->state, ctx->user_data);
    }
    
    return ESP_OK;
}

esp_err_t wifi_manager_set_sta_mode(void) {
    ESP_LOGI(TAG, "Switching to station-only mode");
    
    // Desconectar y detener AP
    wifi_manager_stop_ap_mode();
    
    // Cambiar a modo station
    esp_err_t ret = esp_wifi_set_mode(WIFI_MODE_STA);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to set WiFi mode: %s", esp_err_to_name(ret));
        return ret;
    }
    
    return ESP_OK;
}

// Verificar si está conectado a WiFi
bool wifi_manager_is_connected(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return false;
    }
    
    EventBits_t bits = xEventGroupGetBits(ctx->event_group);
    return (bits & WIFI_CONNECTED_BIT) != 0;
}

// Obtener la dirección IP en modo STA
esp_err_t wifi_manager_get_ip(char *ip, size_t max_len)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ip == NULL || max_len < 16) {
        ESP_LOGE(TAG, "Invalid buffer for IP address");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (!wifi_manager_is_connected()) {
        ESP_LOGW(TAG, "Not connected to WiFi");
        strncpy(ip, "0.0.0.0", max_len);
        return ESP_ERR_INVALID_STATE;
    }
    
    // Obtener información IP
    esp_netif_ip_info_t ip_info;
    ESP_ERROR_CHECK(esp_netif_get_ip_info(ctx->sta_netif, &ip_info));
    
    // Convertir a string
    snprintf(ip, max_len, IPSTR, IP2STR(&ip_info.ip));
    return ESP_OK;
}

// Obtener IP del Access Point
esp_err_t wifi_manager_get_ap_ip(char *ip, size_t max_len)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ip == NULL || max_len < 16) {
        ESP_LOGE(TAG, "Invalid buffer for IP address");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (ctx->state != WIFI_MANAGER_STATE_AP_MODE && 
        ctx->state != WIFI_MANAGER_STATE_STA_AP_MODE) {
        ESP_LOGW(TAG, "Not in AP or STA+AP mode");
        strncpy(ip, "0.0.0.0", max_len);
        return ESP_ERR_INVALID_STATE;
    }
    
    // Obtener información IP del AP
    esp_netif_ip_info_t ip_info;
    ESP_ERROR_CHECK(esp_netif_get_ip_info(ctx->ap_netif, &ip_info));
    
    // Convertir a string
    snprintf(ip, max_len, IPSTR, IP2STR(&ip_info.ip));
    return ESP_OK;
}

// Obtener el RSSI actual
esp_err_t wifi_manager_get_rssi(int8_t *rssi)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (rssi == NULL) {
        ESP_LOGE(TAG, "RSSI pointer is NULL");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (!wifi_manager_is_connected()) {
        ESP_LOGW(TAG, "Not connected to WiFi");
        *rssi = -127;
        return ESP_ERR_INVALID_STATE;
    }
    
    // Actualizar información AP
    if (esp_wifi_sta_get_ap_info(&ctx->ap_info) != ESP_OK) {
        ESP_LOGW(TAG, "Failed to get AP info");
        *rssi = -127;
        return ESP_FAIL;
    }
    
    *rssi = ctx->ap_info.rssi;
    return ESP_OK;
}

// Obtener el estado actual
wifi_manager_state_t wifi_manager_get_state(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        return WIFI_MANAGER_STATE_INIT;
    }
    
    return ctx->state;
}

// Obtener el SSID configurado
esp_err_t wifi_manager_get_configured_ssid(char *ssid, size_t max_len)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ssid == NULL || max_len < 1) {
        ESP_LOGE(TAG, "Invalid buffer for SSID");
        return ESP_ERR_INVALID_ARG;
    }
    
    if (!ctx->credentials_saved) {
        ESP_LOGW(TAG, "No saved WiFi credentials");
        ssid[0] = '\0';
        return ESP_ERR_NOT_FOUND;
    }
    
    strncpy(ssid, ctx->ssid, max_len - 1);
    ssid[max_len - 1] = '\0';
    
    return ESP_OK;
}

// Olvidar red WiFi guardada
esp_err_t wifi_manager_forget_network(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Forgetting saved WiFi network");
    
    // Desconectar si estamos conectados
    if (ctx->state == WIFI_MANAGER_STATE_CONNECTED || 
        ctx->state == WIFI_MANAGER_STATE_CONNECTING) {
        ESP_LOGI(TAG, "Disconnecting from current network");
        ESP_ERROR_CHECK(esp_wifi_disconnect());
        
        // Esperar a que se desconecte
        EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                              WIFI_DISCONNECTED_BIT,
                                              pdFALSE, pdFALSE, pdMS_TO_TICKS(5000));
        if ((bits & WIFI_DISCONNECTED_BIT) == 0) {
            ESP_LOGW(TAG, "Timeout waiting for disconnection");
        }
    }
    
    // Borrar credenciales de config_manager
    ESP_ERROR_CHECK(config_manager_erase_key("wifi_ssid"));
    ESP_ERROR_CHECK(config_manager_erase_key("wifi_password"));
    
    // Limpiar variables internas
    ctx->ssid[0] = '\0';
    ctx->password[0] = '\0';
    ctx->credentials_saved = false;
    
    // Si estamos en modo combinado, mantener el modo AP
    if (ctx->state == WIFI_MANAGER_STATE_STA_AP_MODE) {
        ctx->state = WIFI_MANAGER_STATE_AP_MODE;
    } else {
        ctx->state = WIFI_MANAGER_STATE_DISCONNECTED;
    }
    
    if (ctx->state_callback) {
        ctx->state_callback(ctx->state, ctx->user_data);
    }
    
    return ESP_OK;
}

// Iniciar escaneo de redes WiFi - FUNCIÓN CORREGIDA
esp_err_t wifi_manager_start_scan(void)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Starting WiFi scan");
    
    // Verificar si ya hay un scan en progreso
    if (ctx->scan_in_progress) {
        ESP_LOGW(TAG, "Scan already in progress");
        return ESP_ERR_INVALID_STATE;
    }
    
    // Verificar modo WiFi actual
    wifi_mode_t current_mode;
    esp_err_t ret = esp_wifi_get_mode(&current_mode);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to get WiFi mode: %s", esp_err_to_name(ret));
        return ret;
    }
    
    // Si estamos en modo AP puro, necesitamos cambiar a AP+STA temporalmente
    if (current_mode == WIFI_MODE_AP) {
        ESP_LOGI(TAG, "Switching from AP to AP+STA mode for scanning");
        ret = esp_wifi_set_mode(WIFI_MODE_APSTA);
        if (ret != ESP_OK) {
            ESP_LOGE(TAG, "Failed to switch to AP+STA mode: %s", esp_err_to_name(ret));
            return ret;
        }
        
        ctx->temporary_apsta_mode = true;
        
        // Dar tiempo para que se configure el modo
        vTaskDelay(pdMS_TO_TICKS(500));
    } else {
        ctx->temporary_apsta_mode = false;
    }
    
    // Limpiar bits de escaneo previo
    xEventGroupClearBits(ctx->event_group, WIFI_SCAN_DONE_BIT);
    
    // Marcar como escaneo en progreso
    ctx->scan_in_progress = true;
    
    // Iniciar escaneo
    ret = esp_wifi_scan_start(&ctx->scan_config, false);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to start WiFi scan: %s", esp_err_to_name(ret));
        ctx->scan_in_progress = false;
        
        // Si cambiamos el modo, intentar volver al modo AP
        if (ctx->temporary_apsta_mode) {
            ESP_LOGW(TAG, "Reverting to AP mode after scan failure");
            ctx->temporary_apsta_mode = false;
            esp_err_t revert_ret = esp_wifi_set_mode(WIFI_MODE_AP);
            if (revert_ret != ESP_OK) {
                ESP_LOGE(TAG, "Failed to revert to AP mode: %s", esp_err_to_name(revert_ret));
            }
        }
        
        return ret;
    }
    
    ESP_LOGI(TAG, "WiFi scan started successfully");
    return ESP_OK;
}

// Obtener resultados del escaneo
esp_err_t wifi_manager_get_scan_results(wifi_scan_result_t *results, size_t max_networks, size_t *num_networks)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (results == NULL || num_networks == NULL) {
        ESP_LOGE(TAG, "Invalid output parameters");
        return ESP_ERR_INVALID_ARG;
    }
    
    // Si hay un escaneo en progreso, esperar a que termine
    if (ctx->scan_in_progress) {
        ESP_LOGI(TAG, "Waiting for scan to complete");
        
        // Esperar a que termine el escaneo
        EventBits_t bits = xEventGroupWaitBits(ctx->event_group,
                                              WIFI_SCAN_DONE_BIT,
                                              pdFALSE, pdFALSE, pdMS_TO_TICKS(10000));
        
        if ((bits & WIFI_SCAN_DONE_BIT) == 0) {
            ESP_LOGW(TAG, "Timeout waiting for scan to complete");
            return ESP_ERR_TIMEOUT;
        }
    }
    
    // Verificar si hay resultados de escaneo
    if (ctx->scan_ap_list == NULL || ctx->scan_ap_count == 0) {
        ESP_LOGW(TAG, "No scan results available");
        *num_networks = 0;
        return ESP_ERR_NOT_FOUND;
    }
    
    // Copiar resultados
    size_t count = (ctx->scan_ap_count < max_networks) ? ctx->scan_ap_count : max_networks;
    for (size_t i = 0; i < count; i++) {
        strncpy(results[i].ssid, (char *)ctx->scan_ap_list[i].ssid, sizeof(results[i].ssid) - 1);
        results[i].ssid[sizeof(results[i].ssid) - 1] = '\0';
        results[i].rssi = ctx->scan_ap_list[i].rssi;
        results[i].auth_mode = ctx->scan_ap_list[i].authmode;
    }
    
    *num_networks = count;
    ESP_LOGI(TAG, "Returning %zu scan results", count);
    return ESP_OK;
}

// Registrar callback para cambios de estado
esp_err_t wifi_manager_set_state_callback(void (*callback)(wifi_manager_state_t state, void *user_data), void *user_data)
{
    wifi_manager_context_t *ctx = &s_wifi_manager_ctx;
    
    if (ctx->event_group == NULL) {
        ESP_LOGE(TAG, "WiFi Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ctx->state_callback = callback;
    ctx->user_data = user_data;
    
    return ESP_OK;
}

/**
 * @brief Maneja la pérdida de conexión WiFi, intentando reconectar y activando el modo AP si falla
 * 
 * Esta función debe ser llamada periódicamente desde el bucle principal cuando
 * se detecta que el WiFi está desconectado.
 * 
 * @param ap_ssid_prefix Prefijo para el SSID del AP (se añadirá el ID del dispositivo)
 * @param ap_password Contraseña para el AP
 * @param max_attempts Número máximo de intentos de reconexión antes de activar el AP
 * @return ESP_OK si está gestionando correctamente, ESP_FAIL en caso de error crítico
 */
esp_err_t wifi_manager_handle_disconnection(const char *ap_ssid_prefix, const char *ap_password, int max_attempts) {
    static int reconnect_attempts = 0;
    static int64_t last_reconnect_time = 0;
    int64_t current_time = esp_timer_get_time() / 1000;  // Convertir a ms
    
    // Si WiFi ya está conectado, resetear contador y salir
    if (wifi_manager_is_connected()) {
        reconnect_attempts = 0;
        return ESP_OK;
    }
    
    // Si han pasado al menos 10 segundos desde el último intento
    if (current_time - last_reconnect_time > 10000) {
        last_reconnect_time = current_time;
        
        // Incrementar contador de intentos
        reconnect_attempts++;
        ESP_LOGI(TAG, "WiFi disconnected, reconnection attempt %d/%d", 
                 reconnect_attempts, max_attempts);
        
        // Intentar reconectar con credenciales guardadas
        esp_err_t ret = wifi_manager_connect_saved();
        
        // Si hemos alcanzado el número máximo de intentos y sigue fallando
        if (reconnect_attempts >= max_attempts && ret != ESP_OK) {
            ESP_LOGI(TAG, "Failed to reconnect to WiFi after %d attempts, activating AP mode", 
                     reconnect_attempts);
            
            // Generar SSID único para el AP
            char ap_ssid[33];
            esp_err_t ap_ret = wifi_manager_generate_ap_ssid(ap_ssid, sizeof(ap_ssid), ap_ssid_prefix);
            if (ap_ret != ESP_OK) {
                ESP_LOGE(TAG, "Failed to generate AP SSID");
                return ESP_FAIL;
            }
            
            // Iniciar AP y portal cautivo
            ESP_LOGI(TAG, "Starting AP with SSID: %s", ap_ssid);
            
            // Iniciar AP
            ap_ret = wifi_manager_start_ap_mode(ap_ssid, ap_password);
            if (ap_ret != ESP_OK) {
                ESP_LOGE(TAG, "Failed to start AP mode");
                return ESP_FAIL;
            }
            
            // Iniciar portal cautivo (esto es opcional, depende de tu implementación)
            // Si tu aplicación tiene el módulo wifi_captive_portal, puedes llamarlo así:
            // wifi_captive_portal_start(ap_ssid, ap_password);
            
            // Resetear contador de intentos
            reconnect_attempts = 0;
        }
    }
    
    return ESP_OK;
}

// Generar SSID único para AP basado en ID del dispositivo
esp_err_t wifi_manager_generate_ap_ssid(char *ap_ssid, size_t max_len, const char *prefix)
{
    if (ap_ssid == NULL || max_len < 8 || prefix == NULL) {
        ESP_LOGE(TAG, "Invalid parameters for AP SSID generation");
        return ESP_ERR_INVALID_ARG;
    }
    
    // Obtener el ID del ESP32
    char esp32_id[ESP32_ID_LENGTH + 1];
    esp_err_t ret = esp32_id_manager_get_id(esp32_id, sizeof(esp32_id));
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to get ESP32 ID");
        return ret;
    }
    
    // Generar SSID con formato: PREFIX_ABCDEF (últimos 6 caracteres del ID)
    size_t id_len = strlen(esp32_id);
    size_t prefix_len = strlen(prefix);
    
    if (id_len < 6 || prefix_len + 7 > max_len) {
        ESP_LOGE(TAG, "ID too short or buffer too small");
        return ESP_ERR_INVALID_ARG;
    }
    
    snprintf(ap_ssid, max_len, "%s_%s", prefix, &esp32_id[id_len - 6]);
    
    ESP_LOGI(TAG, "Generated AP SSID: %s", ap_ssid);
    return ESP_OK;
}