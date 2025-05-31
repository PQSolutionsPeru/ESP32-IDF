#include "simple_http_server.h"
#include <string.h>
#include <sys/param.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_system.h"
#include "esp_log.h"
#include "lwip/sockets.h"
#include "lwip/netdb.h"
#include "cJSON.h"
#include "wifi_manager.h"

#define TAG "SIMPLE_HTTP"

// HTML assets
extern const char index_html[];

// Estructura del contexto del servidor HTTP
typedef struct {
    int server_socket;
    TaskHandle_t task_handle;
    bool is_running;
} simple_http_server_t;

// Función para enviar respuestas HTTP
static esp_err_t send_response(int sock, const char* status, const char* content_type, const char* body, int body_len) {
    char header[256];
    int header_len = snprintf(header, sizeof(header),
                             "HTTP/1.1 %s\r\n"
                             "Content-Type: %s\r\n"
                             "Content-Length: %d\r\n"
                             "Connection: close\r\n"
                             "\r\n",
                             status, content_type, body_len);
    
    // Enviar encabezado
    int res = send(sock, header, header_len, 0);
    if (res < 0) {
        ESP_LOGE(TAG, "Error sending HTTP header: %d", res);
        return ESP_FAIL;
    }
    
    // Enviar cuerpo si existe
    if (body_len > 0) {
        res = send(sock, body, body_len, 0);
        if (res < 0) {
            ESP_LOGE(TAG, "Error sending HTTP body: %d", res);
            return ESP_FAIL;
        }
    }
    
    return ESP_OK;
}

// Función para enviar redirección
static esp_err_t send_redirect(int sock, const char* location) {
    char header[256];
    int header_len = snprintf(header, sizeof(header),
                             "HTTP/1.1 302 Found\r\n"
                             "Location: %s\r\n"
                             "Content-Length: 0\r\n"
                             "Connection: close\r\n"
                             "\r\n",
                             location);
    
    int res = send(sock, header, header_len, 0);
    if (res < 0) {
        ESP_LOGE(TAG, "Error sending redirect: %d", res);
        return ESP_FAIL;
    }
    
    return ESP_OK;
}

// Función para manejar peticiones a la página principal
static esp_err_t handle_index(int sock) {
    return send_response(sock, "200 OK", "text/html", index_html, strlen(index_html));
}

// Función para manejar peticiones a la API de estado WiFi
static esp_err_t handle_status_api(int sock) {
    char resp_str[256];
    char ssid[33] = {0};
    bool is_connected = wifi_manager_is_connected();
    
    if (is_connected) {
        wifi_manager_get_configured_ssid(ssid, sizeof(ssid));
    }
    
    snprintf(resp_str, sizeof(resp_str), 
             "{\"connected\": %s, \"ssid\": \"%s\"}", 
             is_connected ? "true" : "false", ssid);
    
    return send_response(sock, "200 OK", "application/json", resp_str, strlen(resp_str));
}

// Reemplazar la función handle_scan_api() en simple_http_server.c (alrededor de línea 60):

// Función para manejar peticiones a la API de escaneo WiFi
static esp_err_t handle_scan_api(int sock) {
    ESP_LOGI(TAG, "Starting WiFi scan request");
    
    // Iniciar escaneo
    esp_err_t scan_ret = wifi_manager_start_scan();
    if (scan_ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to start WiFi scan: %s", esp_err_to_name(scan_ret));
        
        // Enviar respuesta de error
        cJSON *error_root = cJSON_CreateObject();
        cJSON_AddBoolToObject(error_root, "success", false);
        cJSON_AddStringToObject(error_root, "error", "Failed to start WiFi scan");
        cJSON_AddItemToObject(error_root, "networks", cJSON_CreateArray());
        
        char *error_json = cJSON_Print(error_root);
        esp_err_t ret = send_response(sock, "500 Internal Server Error", "application/json", error_json, strlen(error_json));
        
        cJSON_Delete(error_root);
        free(error_json);
        return ret;
    }
    
    // Esperar a que termine - aumentar tiempo de espera para modo switching
    ESP_LOGI(TAG, "Waiting for scan completion...");
    
    // Esperar hasta 8 segundos para que termine el scan (incluye tiempo de cambio de modo)
    int max_wait_cycles = 40; // 40 * 200ms = 8 segundos
    int wait_cycles = 0;
    
    while (wait_cycles < max_wait_cycles) {
        vTaskDelay(pdMS_TO_TICKS(200));
        wait_cycles++;
        
        // Verificar si el scan terminó
        wifi_scan_result_t test_networks[1];
        size_t test_num = 0;
        esp_err_t test_ret = wifi_manager_get_scan_results(test_networks, 1, &test_num);
        
        if (test_ret == ESP_OK || test_ret == ESP_ERR_NOT_FOUND) {
            // El scan terminó (con o sin resultados)
            break;
        } else if (test_ret != ESP_ERR_TIMEOUT) {
            // Error real, no timeout
            ESP_LOGE(TAG, "Error getting scan results: %s", esp_err_to_name(test_ret));
            break;
        }
        // Si es timeout, continuar esperando
    }
    
    if (wait_cycles >= max_wait_cycles) {
        ESP_LOGW(TAG, "Scan timeout after %d seconds", max_wait_cycles * 200 / 1000);
    }
    
    // Obtener resultados del escaneo
    wifi_scan_result_t networks[20];
    size_t num_networks = 0;
    esp_err_t ret = wifi_manager_get_scan_results(networks, 20, &num_networks);
    
    // Crear respuesta JSON
    cJSON *root = cJSON_CreateObject();
    cJSON *nets_array = cJSON_CreateArray();
    
    if (ret == ESP_OK && num_networks > 0) {
        ESP_LOGI(TAG, "Scan completed successfully, found %zu networks", num_networks);
        
        for (size_t i = 0; i < num_networks; i++) {
            // Filtrar SSIDs vacíos o muy cortos
            if (strlen(networks[i].ssid) > 0) {
                cJSON *network = cJSON_CreateObject();
                cJSON_AddStringToObject(network, "ssid", networks[i].ssid);
                cJSON_AddNumberToObject(network, "rssi", networks[i].rssi);
                cJSON_AddNumberToObject(network, "auth", networks[i].auth_mode);
                
                // Agregar información de seguridad legible
                const char* security = "Open";
                switch (networks[i].auth_mode) {
                    case WIFI_AUTH_WEP:
                        security = "WEP";
                        break;
                    case WIFI_AUTH_WPA_PSK:
                        security = "WPA";
                        break;
                    case WIFI_AUTH_WPA2_PSK:
                        security = "WPA2";
                        break;
                    case WIFI_AUTH_WPA_WPA2_PSK:
                        security = "WPA/WPA2";
                        break;
                    case WIFI_AUTH_WPA3_PSK:
                        security = "WPA3";
                        break;
                    default:
                        security = "Unknown";
                        break;
                }
                cJSON_AddStringToObject(network, "security", security);
                
                cJSON_AddItemToArray(nets_array, network);
            }
        }
        
        cJSON_AddBoolToObject(root, "success", true);
    } else if (ret == ESP_ERR_NOT_FOUND || num_networks == 0) {
        ESP_LOGW(TAG, "Scan completed but no networks found");
        cJSON_AddBoolToObject(root, "success", true);
        cJSON_AddStringToObject(root, "message", "No networks found");
    } else {
        ESP_LOGE(TAG, "Scan failed: %s", esp_err_to_name(ret));
        cJSON_AddBoolToObject(root, "success", false);
        cJSON_AddStringToObject(root, "error", "Scan failed or timed out");
    }
    
    cJSON_AddItemToObject(root, "networks", nets_array);
    
    char *json_str = cJSON_Print(root);
    ret = send_response(sock, "200 OK", "application/json", json_str, strlen(json_str));
    
    cJSON_Delete(root);
    free(json_str);
    
    return ret;
}

// Función para manejar peticiones a la API de conexión WiFi
static esp_err_t handle_connect_api(int sock, char* body, int body_len) {
    esp_err_t ret = ESP_FAIL;
    
    // Asegurarnos de que el cuerpo tenga un 0 al final para tratarlo como string
    if (body_len < 256) {
        body[body_len] = '\0';
        
        // Parsear JSON
        cJSON *root = cJSON_Parse(body);
        if (root) {
            cJSON *ssid_json = cJSON_GetObjectItem(root, "ssid");
            cJSON *password_json = cJSON_GetObjectItem(root, "password");
            
            if (ssid_json && cJSON_IsString(ssid_json)) {
                char *ssid = ssid_json->valuestring;
                char *password = password_json && cJSON_IsString(password_json) ? password_json->valuestring : "";
                
                ESP_LOGI(TAG, "Connecting to WiFi: %s", ssid);
                
                // Intentar conectar a la red WiFi
                esp_err_t connect_ret = wifi_manager_connect(ssid, password, true);
                
                cJSON *resp_json = cJSON_CreateObject();
                
                if (connect_ret == ESP_OK) {
                    ESP_LOGI(TAG, "Successfully connected to WiFi");
                    cJSON_AddBoolToObject(resp_json, "success", true);
                } else {
                    ESP_LOGE(TAG, "Failed to connect to WiFi: %s", esp_err_to_name(connect_ret));
                    cJSON_AddBoolToObject(resp_json, "success", false);
                    cJSON_AddStringToObject(resp_json, "message", "Failed to connect to WiFi");
                }
                
                char *json_str = cJSON_Print(resp_json);
                ret = send_response(sock, "200 OK", "application/json", json_str, strlen(json_str));
                
                cJSON_Delete(resp_json);
                free(json_str);
            } else {
                ret = send_response(sock, "400 Bad Request", "application/json", "{\"error\":\"Missing or invalid SSID\"}", 33);
            }
            
            cJSON_Delete(root);
        } else {
            ret = send_response(sock, "400 Bad Request", "application/json", "{\"error\":\"Invalid JSON format\"}", 30);
        }
    } else {
        ret = send_response(sock, "400 Bad Request", "application/json", "{\"error\":\"Content too long\"}", 28);
    }
    
    return ret;
}

// Extrae el método y la URI de la primera línea de la petición HTTP
static esp_err_t parse_request_line(char* line, char* method, size_t method_len, char* uri, size_t uri_len) {
    // Formato esperado: "METHOD URI HTTP/1.x"
    char *method_end = strchr(line, ' ');
    if (!method_end) {
        return ESP_FAIL;
    }
    
    int method_size = method_end - line;
    if (method_size >= method_len) {
        return ESP_FAIL;
    }
    
    strncpy(method, line, method_size);
    method[method_size] = '\0';
    
    char *uri_start = method_end + 1;
    char *uri_end = strchr(uri_start, ' ');
    if (!uri_end) {
        return ESP_FAIL;
    }
    
    int uri_size = uri_end - uri_start;
    if (uri_size >= uri_len) {
        return ESP_FAIL;
    }
    
    strncpy(uri, uri_start, uri_size);
    uri[uri_size] = '\0';
    
    return ESP_OK;
}

// Encuentra la longitud del contenido en los encabezados HTTP
static int find_content_length(char* headers) {
    char *content_len_str = strstr(headers, "Content-Length:");
    if (!content_len_str) {
        return 0;
    }
    
    content_len_str += 15; // Saltar "Content-Length:"
    while (*content_len_str == ' ') {
        content_len_str++; // Saltar espacios
    }
    
    return atoi(content_len_str);
}

// Tarea principal del servidor HTTP
static void http_server_task(void *pvParameters) {
    simple_http_server_t *server = (simple_http_server_t *)pvParameters;
    
    while (server->is_running) {
        struct sockaddr_in client_addr;
        socklen_t client_addr_len = sizeof(client_addr);
        
        // Aceptar conexión
        int client_sock = accept(server->server_socket, (struct sockaddr *)&client_addr, &client_addr_len);
        if (client_sock < 0) {
            ESP_LOGE(TAG, "Unable to accept connection: %d", client_sock);
            continue;
        }
        
        // Establecer timeout para recepción
        struct timeval tv;
        tv.tv_sec = 5;
        tv.tv_usec = 0;
        setsockopt(client_sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        
        // Buffer para leer la petición
        char rx_buffer[1024];
        int rx_len = recv(client_sock, rx_buffer, sizeof(rx_buffer) - 1, 0);
        if (rx_len <= 0) {
            ESP_LOGE(TAG, "Error reading HTTP request: %d", rx_len);
            close(client_sock);
            continue;
        }
        
        // Asegurar que el buffer esté terminado con un 0
        rx_buffer[rx_len] = '\0';
        
        // Extraer método y URI
        char method[10];
        char uri[256];
        if (parse_request_line(rx_buffer, method, sizeof(method), uri, sizeof(uri)) != ESP_OK) {
            ESP_LOGE(TAG, "Error parsing HTTP request line");
            close(client_sock);
            continue;
        }
        
        ESP_LOGI(TAG, "Request: %s %s", method, uri);
        
        // Manejar diferentes URIs
        if (strcmp(uri, "/") == 0) {
            handle_index(client_sock);
        } else if (strcmp(uri, "/api/status") == 0) {
            handle_status_api(client_sock);
        } else if (strcmp(uri, "/api/scan") == 0) {
            handle_scan_api(client_sock);
        } else if (strcmp(uri, "/api/connect") == 0 && strcmp(method, "POST") == 0) {
            // Para POST, necesitamos extraer el cuerpo
            int content_length = find_content_length(rx_buffer);
            if (content_length > 0 && content_length < 256) {
                // Encontrar inicio del cuerpo (después de doble CRLF)
                char *body_start = strstr(rx_buffer, "\r\n\r\n");
                if (body_start) {
                    body_start += 4; // Saltar CRLF
                    int body_offset = body_start - rx_buffer;
                    int body_received = rx_len - body_offset;
                    
                    // Si ya tenemos todo el cuerpo en el buffer
                    if (body_received >= content_length) {
                        handle_connect_api(client_sock, body_start, content_length);
                    } else {
                        // Si necesitamos recibir más datos para el cuerpo
                        char body_buffer[256];
                        memcpy(body_buffer, body_start, body_received);
                        
                        int remaining = content_length - body_received;
                        int received = recv(client_sock, body_buffer + body_received, remaining, 0);
                        
                        if (received == remaining) {
                            handle_connect_api(client_sock, body_buffer, content_length);
                        } else {
                            ESP_LOGE(TAG, "Error receiving complete body");
                            send_response(client_sock, "400 Bad Request", "application/json", "{\"error\":\"Incomplete body\"}", 25);
                        }
                    }
                } else {
                    ESP_LOGE(TAG, "Error finding body start");
                    send_response(client_sock, "400 Bad Request", "application/json", "{\"error\":\"Body not found\"}", 26);
                }
            } else {
                ESP_LOGE(TAG, "Invalid content length: %d", content_length);
                send_response(client_sock, "400 Bad Request", "application/json", "{\"error\":\"Invalid content length\"}", 34);
            }
        } else {
            // Cualquier otra URI redirige a la página principal (portal cautivo)
            send_redirect(client_sock, "http://192.168.4.1/");
        }
        
        close(client_sock);
    }
    
    vTaskDelete(NULL);
}

esp_err_t simple_http_server_start(const simple_http_server_config_t* config, simple_http_server_handle_t* handle) {
    if (!config || !handle) {
        return ESP_ERR_INVALID_ARG;
    }
    
    // Crear contexto del servidor
    simple_http_server_t *server = calloc(1, sizeof(simple_http_server_t));
    if (!server) {
        return ESP_ERR_NO_MEM;
    }
    
    // Crear socket del servidor
    server->server_socket = socket(AF_INET, SOCK_STREAM, IPPROTO_IP);
    if (server->server_socket < 0) {
        ESP_LOGE(TAG, "Unable to create socket: %d", server->server_socket);
        free(server);
        return ESP_FAIL;
    }
    
    // Configurar socket para reutilizar dirección
    int opt = 1;
    setsockopt(server->server_socket, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    
    // Configurar dirección del servidor
    struct sockaddr_in server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_addr.s_addr = htonl(INADDR_ANY);
    server_addr.sin_port = htons(config->port);
    
    // Hacer bind
    int res = bind(server->server_socket, (struct sockaddr *)&server_addr, sizeof(server_addr));
    if (res != 0) {
        ESP_LOGE(TAG, "Socket bind failed: %d", res);
        close(server->server_socket);
        free(server);
        return ESP_FAIL;
    }
    
    // Escuchar
    res = listen(server->server_socket, config->max_connections);
    if (res != 0) {
        ESP_LOGE(TAG, "Socket listen failed: %d", res);
        close(server->server_socket);
        free(server);
        return ESP_FAIL;
    }
    
    ESP_LOGI(TAG, "Socket listening on port %d", config->port);
    
    // Iniciar tarea
    server->is_running = true;
    if (xTaskCreate(http_server_task, "http_server", config->stack_size, server, 5, &server->task_handle) != pdPASS) {
        ESP_LOGE(TAG, "Failed to create HTTP server task");
        close(server->server_socket);
        free(server);
        return ESP_FAIL;
    }
    
    *handle = server;
    return ESP_OK;
}

esp_err_t simple_http_server_stop(simple_http_server_handle_t handle) {
    simple_http_server_t *server = (simple_http_server_t *)handle;
    if (!server) {
        return ESP_ERR_INVALID_ARG;
    }
    
    // Detener tarea
    server->is_running = false;
    vTaskDelay(pdMS_TO_TICKS(100)); // Dar tiempo para que termine
    
    // Cerrar socket
    close(server->server_socket);
    
    // Liberar memoria
    free(server);
    
    return ESP_OK;
}