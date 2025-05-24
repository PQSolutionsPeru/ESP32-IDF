#include "mqtt_ssl_setup.h"
#include "esp_log.h"
#include "esp_heap_caps.h"
#include "esp_tls.h"
#include "esp_crt_bundle.h"  // Ahora disponible con certificate bundle habilitado
#include <inttypes.h>

#define TAG "MQTT_SSL"

esp_err_t mqtt_ssl_setup_minimal_config(esp_mqtt_client_config_t *mqtt_cfg) {
    if (mqtt_cfg == NULL) {
        ESP_LOGE(TAG, "MQTT config is NULL");
        return ESP_ERR_INVALID_ARG;
    }
    
    ESP_LOGI(TAG, "Setting up CERT_NONE SSL configuration (equivalent to MicroPython)");
    
    // Verificar memoria disponible antes de configurar SSL
    uint32_t free_heap = esp_get_free_heap_size();
    uint32_t largest_block = (uint32_t)heap_caps_get_largest_free_block(MALLOC_CAP_8BIT);
    
    ESP_LOGI(TAG, "Memory before SSL setup - Free: %" PRIu32 " bytes, Largest block: %" PRIu32 " bytes", 
            free_heap, largest_block);
    
    if (free_heap < 30000) {
        ESP_LOGW(TAG, "Low memory before SSL setup (%" PRIu32 " bytes), may cause issues", free_heap);
    }
    
    // ********** CONFIGURACIÓN ORIGINAL QUE FUNCIONABA **********
    
    // 1. USAR certificate bundle pero sin verificar (como el código original)
    mqtt_cfg->broker.verification.crt_bundle_attach = esp_crt_bundle_attach;
    
    // 2. NO verificar el nombre común del certificado (equivalente a CERT_NONE)
    mqtt_cfg->broker.verification.skip_cert_common_name_check = true;
    
    // 3. NO usar certificados personalizados
    mqtt_cfg->broker.verification.certificate = NULL;
    mqtt_cfg->broker.verification.certificate_len = 0;
    
    // 4. NO usar el store global de CA
    mqtt_cfg->broker.verification.use_global_ca_store = false;
    
    // 5. Configuración adicional de TLS para compatibilidad
    mqtt_cfg->broker.verification.alpn_protos = NULL;
    
    // ********** CONFIGURACIÓN DE RED OPTIMIZADA **********
    mqtt_cfg->network.disable_auto_reconnect = false;
    mqtt_cfg->network.reconnect_timeout_ms = 30000; // 30 segundos
    mqtt_cfg->network.timeout_ms = 30000;          // 30 segundos
    
    // ********** CONFIGURACIÓN DE TAREA OPTIMIZADA **********
    mqtt_cfg->task.stack_size = 8192;  // Stack adecuado para SSL con bundle
    mqtt_cfg->task.priority = 5;
    
    // ********** CONFIGURACIÓN DE BUFFER OPTIMIZADA **********
    mqtt_cfg->buffer.size = 1024;      // Buffer moderado
    mqtt_cfg->buffer.out_size = 1024;  // Buffer de salida
    
    ESP_LOGI(TAG, "CERT_NONE SSL configuration applied successfully (with certificate bundle)");
    
    // Verificar memoria después de configurar
    uint32_t after_heap = esp_get_free_heap_size();
    ESP_LOGI(TAG, "Memory after SSL setup - Free: %" PRIu32 " bytes", after_heap);
    
    return ESP_OK;
}