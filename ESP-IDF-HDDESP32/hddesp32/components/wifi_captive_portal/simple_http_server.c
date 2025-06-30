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

typedef struct {
    int server_socket;
    TaskHandle_t task_handle;
    bool is_running;
} simple_http_server_t;

static simple_http_server_t g_http_server_context = {0};
static bool g_http_server_in_use = false;

extern const char index_html[];

static esp_err_t send_response(int sock, const char* status, const char* content_type, const char* body, int body_len) {
    char header[256];
    int header_len = snprintf(header, sizeof(header),
                             "HTTP/1.1 %s\r\n"
                             "Content-Type: %s\r\n"
                             "Content-Length: %d\r\n"
                             "Connection: close\r\n"
                             "\r\n",
                             status, content_type, body_len);
    
    int res = send(sock, header, header_len, 0);
    if (res < 0) {
        ESP_LOGE(TAG, "Error sending HTTP header: %d", res);
        return ESP_FAIL;
    }
    
    if (body_len > 0) {
        res = send(sock, body, body_len, 0);
        if (res < 0) {
            ESP_LOGE(TAG, "Error sending HTTP body: %d", res);
            return ESP_FAIL;
        }
    }
    
    return ESP_OK;
}

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

static esp_err_t handle_index(int sock) {
    return send_response(sock, "200 OK", "text/html", index_html, strlen(index_html));
}

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

static esp_err_t handle_scan_api(int sock) {
    ESP_LOGI(TAG, "Starting WiFi scan request");
    
    esp_err_t scan_ret = wifi_manager_start_scan();
    if (scan_ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to start WiFi scan: %s", esp_err_to_name(scan_ret));
        
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
    
    ESP_LOGI(TAG, "Waiting for scan completion...");
    
    int max_wait_cycles = 40;
    int wait_cycles = 0;
    
    while (wait_cycles < max_wait_cycles) {
        vTaskDelay(pdMS_TO_TICKS(200));
        wait_cycles++;
        
        wifi_scan_result_t test_networks[1];
        size_t test_num = 0;
        esp_err_t test_ret = wifi_manager_get_scan_results(test_networks, 1, &test_num);
        
        if (test_ret == ESP_OK || test_ret == ESP_ERR_NOT_FOUND) {
            break;
        } else if (test_ret != ESP_ERR_TIMEOUT) {
            ESP_LOGE(TAG, "Error getting scan results: %s", esp_err_to_name(test_ret));
            break;
        }
    }
    
    if (wait_cycles >= max_wait_cycles) {
        ESP_LOGW(TAG, "Scan timeout after %d seconds", max_wait_cycles * 200 / 1000);
    }
    
    wifi_scan_result_t networks[20];
    size_t num_networks = 0;
    esp_err_t ret = wifi_manager_get_scan_results(networks, 20, &num_networks);
    
    cJSON *root = cJSON_CreateObject();
    cJSON *nets_array = cJSON_CreateArray();
    
    if (ret == ESP_OK && num_networks > 0) {
        ESP_LOGI(TAG, "Scan completed successfully, found %zu networks", num_networks);
        
        for (size_t i = 0; i < num_networks; i++) {
            if (strlen(networks[i].ssid) > 0) {
                cJSON *network = cJSON_CreateObject();
                cJSON_AddStringToObject(network, "ssid", networks[i].ssid);
                cJSON_AddNumberToObject(network, "rssi", networks[i].rssi);
                cJSON_AddNumberToObject(network, "auth", networks[i].auth_mode);
                
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

static esp_err_t handle_connect_api(int sock, char* body, int body_len) {
    static char response_buffer[256];
    int response_pos = 0;
    
    if (body_len >= 256) {
        response_pos = snprintf(response_buffer, sizeof(response_buffer),
            "{\"error\":\"Content too long\"}");
        return send_response(sock, "400 Bad Request", "application/json", response_buffer, response_pos);
    }
    
    body[body_len] = '\0';
    
    char ssid_value[64] = {0};
    char password_value[64] = {0};
    bool ssid_found = false;
    bool password_found = false;
    
    char *ssid_start = strstr(body, "\"ssid\":");
    if (ssid_start) {
        ssid_start += 7;
        while (*ssid_start == ' ' || *ssid_start == '\t') ssid_start++;
        
        if (*ssid_start == '"') {
            ssid_start++;
            char *ssid_end = strchr(ssid_start, '"');
            if (ssid_end) {
                size_t len = ssid_end - ssid_start;
                if (len < sizeof(ssid_value)) {
                    memcpy(ssid_value, ssid_start, len);
                    ssid_value[len] = '\0';
                    ssid_found = true;
                }
            }
        }
    }
    
    char *password_start = strstr(body, "\"password\":");
    if (password_start) {
        password_start += 11;
        while (*password_start == ' ' || *password_start == '\t') password_start++;
        
        if (*password_start == '"') {
            password_start++;
            char *password_end = strchr(password_start, '"');
            if (password_end) {
                size_t len = password_end - password_start;
                if (len < sizeof(password_value)) {
                    memcpy(password_value, password_start, len);
                    password_value[len] = '\0';
                    password_found = true;
                }
            }
        }
    }
    
    if (!ssid_found) {
        response_pos = snprintf(response_buffer, sizeof(response_buffer),
            "{\"error\":\"Missing or invalid SSID\"}");
        return send_response(sock, "400 Bad Request", "application/json", response_buffer, response_pos);
    }
    
    ESP_LOGI(TAG, "Connecting to WiFi: %s", ssid_value);
    
    esp_err_t connect_ret = wifi_manager_connect(ssid_value, password_found ? password_value : "", true);
    
    if (connect_ret == ESP_OK) {
        ESP_LOGI(TAG, "Successfully connected to WiFi");
        response_pos = snprintf(response_buffer, sizeof(response_buffer),
            "{\"success\":true}");
    } else {
        ESP_LOGE(TAG, "Failed to connect to WiFi: %s", esp_err_to_name(connect_ret));
        response_pos = snprintf(response_buffer, sizeof(response_buffer),
            "{\"success\":false,\"message\":\"Failed to connect to WiFi\"}");
    }
    
    return send_response(sock, "200 OK", "application/json", response_buffer, response_pos);
}

static esp_err_t parse_request_line(char* line, char* method, size_t method_len, char* uri, size_t uri_len) {
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

static int find_content_length(char* headers) {
    char *content_len_str = strstr(headers, "Content-Length:");
    if (!content_len_str) {
        return 0;
    }
    
    content_len_str += 15;
    while (*content_len_str == ' ') {
        content_len_str++;
    }
    
    return atoi(content_len_str);
}

static void http_server_task(void *pvParameters) {
    simple_http_server_t *server = (simple_http_server_t *)pvParameters;
    
    while (server->is_running) {
        struct sockaddr_in client_addr;
        socklen_t client_addr_len = sizeof(client_addr);
        
        int client_sock = accept(server->server_socket, (struct sockaddr *)&client_addr, &client_addr_len);
        if (client_sock < 0) {
            ESP_LOGE(TAG, "Unable to accept connection: %d", client_sock);
            continue;
        }
        
        struct timeval tv;
        tv.tv_sec = 5;
        tv.tv_usec = 0;
        setsockopt(client_sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        
        char rx_buffer[1024];
        int rx_len = recv(client_sock, rx_buffer, sizeof(rx_buffer) - 1, 0);
        if (rx_len <= 0) {
            ESP_LOGE(TAG, "Error reading HTTP request: %d", rx_len);
            close(client_sock);
            continue;
        }
        
        rx_buffer[rx_len] = '\0';
        
        char method[10];
        char uri[256];
        if (parse_request_line(rx_buffer, method, sizeof(method), uri, sizeof(uri)) != ESP_OK) {
            ESP_LOGE(TAG, "Error parsing HTTP request line");
            close(client_sock);
            continue;
        }
        
        ESP_LOGI(TAG, "Request: %s %s", method, uri);
        
        if (strcmp(uri, "/") == 0) {
            handle_index(client_sock);
        } else if (strcmp(uri, "/api/status") == 0) {
            handle_status_api(client_sock);
        } else if (strcmp(uri, "/api/scan") == 0) {
            handle_scan_api(client_sock);
        } else if (strcmp(uri, "/api/connect") == 0 && strcmp(method, "POST") == 0) {
            int content_length = find_content_length(rx_buffer);
            if (content_length > 0 && content_length < 256) {
                char *body_start = strstr(rx_buffer, "\r\n\r\n");
                if (body_start) {
                    body_start += 4;
                    int body_offset = body_start - rx_buffer;
                    int body_received = rx_len - body_offset;
                    
                    if (body_received >= content_length) {
                        handle_connect_api(client_sock, body_start, content_length);
                    } else {
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
    
    if (g_http_server_in_use) {
        ESP_LOGE(TAG, "HTTP server already in use");
        return ESP_ERR_INVALID_STATE;
    }
    
    simple_http_server_t *server = &g_http_server_context;
    memset(server, 0, sizeof(simple_http_server_t));
    
    server->server_socket = socket(AF_INET, SOCK_STREAM, IPPROTO_IP);
    if (server->server_socket < 0) {
        ESP_LOGE(TAG, "Unable to create socket: %d", server->server_socket);
        return ESP_FAIL;
    }
    
    int opt = 1;
    setsockopt(server->server_socket, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    
    struct sockaddr_in server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_addr.s_addr = htonl(INADDR_ANY);
    server_addr.sin_port = htons(config->port);
    
    int res = bind(server->server_socket, (struct sockaddr *)&server_addr, sizeof(server_addr));
    if (res != 0) {
        ESP_LOGE(TAG, "Socket bind failed: %d", res);
        close(server->server_socket);
        return ESP_FAIL;
    }
    
    res = listen(server->server_socket, config->max_connections);
    if (res != 0) {
        ESP_LOGE(TAG, "Socket listen failed: %d", res);
        close(server->server_socket);
        return ESP_FAIL;
    }
    
    ESP_LOGI(TAG, "Socket listening on port %d", config->port);
    
    server->is_running = true;
    if (xTaskCreate(http_server_task, "http_server", config->stack_size, server, 5, &server->task_handle) != pdPASS) {
        ESP_LOGE(TAG, "Failed to create HTTP server task");
        close(server->server_socket);
        return ESP_FAIL;
    }
    
    g_http_server_in_use = true;
    *handle = server;
    return ESP_OK;
}

esp_err_t simple_http_server_stop(simple_http_server_handle_t handle) {
    simple_http_server_t *server = (simple_http_server_t *)handle;
    if (!server || server != &g_http_server_context) {
        return ESP_ERR_INVALID_ARG;
    }
    
    server->is_running = false;
    vTaskDelay(pdMS_TO_TICKS(100));
    
    close(server->server_socket);
    
    g_http_server_in_use = false;
    memset(&g_http_server_context, 0, sizeof(g_http_server_context));
    
    return ESP_OK;
}