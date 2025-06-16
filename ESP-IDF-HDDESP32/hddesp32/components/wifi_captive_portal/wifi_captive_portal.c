#include "wifi_captive_portal.h"
#include <string.h>
#include "esp_log.h"
#include "cJSON.h"
#include "dns_server.h"
#include "esp_wifi.h"
#include "esp_mac.h"
#include "esp32_id_manager.h"
#include "simple_http_server.h"

#define TAG "WIFI_PORTAL"

// HTML assets
extern const char index_html[];

// Estructura del contexto del portal cautivo
typedef struct {
    simple_http_server_handle_t server;
    dns_server_handle_t dns_server;
    bool is_active;
    void (*on_connect_callback)(void *user_data);
    void *user_data;
} captive_portal_context_t;

// Instancia única del contexto del portal cautivo
static captive_portal_context_t s_portal_ctx = {0};

// Iniciar el servidor DNS para el portal cautivo
static esp_err_t start_dns_server(void)
{
    captive_portal_context_t *ctx = &s_portal_ctx;
    
    dns_server_config_t dns_config = DNS_SERVER_CONFIG_DEFAULT();
    dns_config.resolve_cb = NULL; // Use default callback
    
    esp_err_t ret = dns_server_start(&dns_config, &ctx->dns_server);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to start DNS server");
        return ret;
    }
    
    ESP_LOGI(TAG, "DNS server started successfully");
    return ESP_OK;
}

// Iniciar el servidor HTTP para el portal cautivo
static esp_err_t start_web_server(void)
{
    captive_portal_context_t *ctx = &s_portal_ctx;
    
    // Configurar el servidor HTTP simple - STACK OPTIMIZADO
    simple_http_server_config_t config = {
        .port = 80,
        .max_connections = 4,
        .stack_size = 5120  // REDUCIDO DE 8192 A 5120
    };
    
    ESP_LOGI(TAG, "Starting HTTP server on port: %d with optimized stack", config.port);
    
    esp_err_t ret = simple_http_server_start(&config, &ctx->server);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to start HTTP server");
        return ESP_FAIL;
    }
    
    ESP_LOGI(TAG, "HTTP server started successfully with %d bytes stack", config.stack_size);
    return ESP_OK;
}

esp_err_t wifi_captive_portal_start(const char *ap_ssid, const char *ap_password)
{
    captive_portal_context_t *ctx = &s_portal_ctx;
    esp_err_t ret;
    
    // Verificar si ya está activo
    if (ctx->is_active) {
        ESP_LOGW(TAG, "Captive portal already active");
        return ESP_ERR_INVALID_STATE;
    }
    
    // Si no se proporcionó un SSID, generar uno basado en el ID del ESP32
    char generated_ssid[33];
    if (ap_ssid == NULL) {
        ret = wifi_manager_generate_ap_ssid(generated_ssid, sizeof(generated_ssid), "FirePanel");
        if (ret != ESP_OK) {
            ESP_LOGE(TAG, "Failed to generate AP SSID");
            return ret;
        }
        ap_ssid = generated_ssid;
    }
    
    // Iniciar modo AP
    ret = wifi_manager_start_sta_ap_mode(ap_ssid, ap_password);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to start AP mode");
        return ret;
    }
    
    // Iniciar servidor DNS para redirección
    ret = start_dns_server();
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to start DNS server");
        wifi_manager_stop_ap_mode();
        return ret;
    }
    
    // Iniciar servidor web
    ret = start_web_server();
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to start web server");
        dns_server_stop(ctx->dns_server);
        ctx->dns_server = NULL;
        wifi_manager_stop_ap_mode();
        return ret;
    }
    
    ctx->is_active = true;
    ESP_LOGI(TAG, "Captive portal started successfully with optimized memory usage");
    return ESP_OK;
}

esp_err_t wifi_captive_portal_stop(void)
{
    captive_portal_context_t *ctx = &s_portal_ctx;
    
    // Verificar si está activo
    if (!ctx->is_active) {
        ESP_LOGW(TAG, "Captive portal not active");
        return ESP_ERR_INVALID_STATE;
    }
    
    // Detener servidor web
    if (ctx->server) {
        simple_http_server_stop(ctx->server);
        ctx->server = NULL;
    }
    
    // Detener servidor DNS
    if (ctx->dns_server) {
        dns_server_stop(ctx->dns_server);
        ctx->dns_server = NULL;
    }
    
    // Detener modo AP
    wifi_manager_stop_ap_mode();
    
    ctx->is_active = false;
    ESP_LOGI(TAG, "Captive portal stopped successfully");
    return ESP_OK;
}

bool wifi_captive_portal_is_active(void)
{
    return s_portal_ctx.is_active;
}

esp_err_t wifi_captive_portal_set_on_connect_callback(void (*callback)(void *user_data), void *user_data)
{
    captive_portal_context_t *ctx = &s_portal_ctx;
    
    ctx->on_connect_callback = callback;
    ctx->user_data = user_data;
    
    return ESP_OK;
}