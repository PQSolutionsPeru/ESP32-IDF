#include "mqtt_ssl_setup.h"
#include "esp_log.h"
#include "esp_heap_caps.h"
#include "esp_tls.h"
#include "esp_crt_bundle.h"
#include <inttypes.h>

#define TAG "MQTT_SSL"

esp_err_t mqtt_ssl_setup_minimal_config(esp_mqtt_client_config_t *mqtt_cfg) {
    if (mqtt_cfg == NULL) {
        ESP_LOGE(TAG, "MQTT config is NULL");
        return ESP_ERR_INVALID_ARG;
    }
    
    ESP_LOGI(TAG, "Setting up SSL with ESP-IDF Certificate Bundle");

    // Verificar memoria disponible antes de configurar SSL
    uint32_t free_heap = esp_get_free_heap_size();
    uint32_t largest_block = (uint32_t)heap_caps_get_largest_free_block(MALLOC_CAP_8BIT);

    ESP_LOGI(TAG, "Memory before SSL setup - Free: %" PRIu32 " bytes, Largest block: %" PRIu32 " bytes",
            free_heap, largest_block);

    if (free_heap < 30000) {
        ESP_LOGW(TAG, "Low memory before SSL setup (%" PRIu32 " bytes), may cause issues", free_heap);
    }

    // Usar ESP-IDF Certificate Bundle - incluye Let's Encrypt y otros CAs confiables
    mqtt_cfg->broker.verification.certificate = NULL;
    mqtt_cfg->broker.verification.certificate_len = 0;
    mqtt_cfg->broker.verification.skip_cert_common_name_check = false;  // Verify domain name matches certificate
    mqtt_cfg->broker.verification.crt_bundle_attach = esp_crt_bundle_attach;
    mqtt_cfg->broker.verification.use_global_ca_store = false;
    mqtt_cfg->broker.verification.alpn_protos = NULL;
    
    // Configuración de red con timeouts más largos
    mqtt_cfg->network.disable_auto_reconnect = false;
    mqtt_cfg->network.reconnect_timeout_ms = 60000;
    mqtt_cfg->network.timeout_ms = 60000;
    
    // Configuración de tarea optimizada
    mqtt_cfg->task.stack_size = 6144;
    mqtt_cfg->task.priority = 5;
    
    // Configuración de buffer
    mqtt_cfg->buffer.size = 1024;
    mqtt_cfg->buffer.out_size = 1024;
    
    ESP_LOGI(TAG, "SSL configuration applied successfully with ESP-IDF Certificate Bundle");
    
    // Verificar memoria después de configurar
    uint32_t after_heap = esp_get_free_heap_size();
    ESP_LOGI(TAG, "Memory after SSL setup - Free: %" PRIu32 " bytes", after_heap);
    
    return ESP_OK;
}