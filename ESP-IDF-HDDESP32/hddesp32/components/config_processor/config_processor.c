#include <string.h>
#include <stdlib.h>
#include "esp_log.h"
#include "config_manager.h"
#include "mqtt_manager.h"
#include "relay_manager.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_heap_caps.h"

static const char *TAG = "CONFIG_PROC";

typedef struct {
    char client_id[32];
    char panel_id[32];
    char esp32_id[32];
    bool success;
} config_task_params_t;

static char* find_json_value(const char *json, const char *key, char *value_buf, size_t buf_size) {
    char search_key[64];
    snprintf(search_key, sizeof(search_key), "\"%s\":", key);
    
    char *start = strstr(json, search_key);
    if (!start) {
        return NULL;
    }
    
    start += strlen(search_key);
    while (*start == ' ' || *start == '\t') start++;
    
    if (*start == '"') {
        start++;
        char *end = strchr(start, '"');
        if (!end) return NULL;
        
        size_t len = end - start;
        if (len >= buf_size) len = buf_size - 1;
        
        memcpy(value_buf, start, len);
        value_buf[len] = '\0';
        return value_buf;
    }
    
    return NULL;
}

static void config_response_task(void *pvParameters) {
    config_task_params_t *params = (config_task_params_t *)pvParameters;
    
    if (!params) {
        vTaskDelete(NULL);
        return;
    }
    
    vTaskDelay(pdMS_TO_TICKS(500));
    
    if (params->success) {
        mqtt_manager_send_config_response(true, "Configuration applied successfully");
        
        vTaskDelay(pdMS_TO_TICKS(500));
        
        ESP_LOGI(TAG, "Setting up panel subscriptions");
        
        mqtt_manager_cleanup_panel_subscriptions();
        vTaskDelay(pdMS_TO_TICKS(200));
        
        esp_err_t sub_ret = mqtt_manager_setup_panel_subscriptions();
        if (sub_ret == ESP_OK) {
            ESP_LOGI(TAG, "Panel subscriptions configured successfully");
        } else {
            ESP_LOGW(TAG, "Panel subscriptions setup failed: %s", esp_err_to_name(sub_ret));
        }
        
        vTaskDelay(pdMS_TO_TICKS(1000));
        
        ESP_LOGI(TAG, "Sending initial relay states report");
        if (relay_manager_get_mgr_state() == RELAY_MGR_STATE_RUNNING) {
            relay_manager_report_initial_states();
        }
        
        char *online_json = heap_caps_malloc(256, MALLOC_CAP_8BIT);
        if (online_json) {
            snprintf(online_json, 256,
                     "{\"esp32_id\":\"%s\",\"status\":\"ONLINE\",\"client_id\":\"%s\",\"panel_id\":\"%s\"}",
                     params->esp32_id, params->client_id, params->panel_id);
            
            mqtt_manager_publish("esp32/status", online_json, strlen(online_json), 1, false);
            free(online_json);
        }
    } else {
        mqtt_manager_send_config_response(false, "Configuration failed");
    }
    
    ESP_LOGI(TAG, "Config response task completed");
    
    free(params);
    vTaskDelete(NULL);
}

esp_err_t process_esp32_configuration(const char *config_json) {
    if (!config_json) {
        ESP_LOGE(TAG, "Invalid configuration JSON");
        return ESP_ERR_INVALID_ARG;
    }
    
    ESP_LOGI(TAG, "Processing configuration: %s", config_json);
    
    config_task_params_t *params = heap_caps_calloc(1, sizeof(config_task_params_t), MALLOC_CAP_8BIT);
    if (!params) {
        return ESP_ERR_NO_MEM;
    }
    
    char client_id_buf[32], panel_id_buf[32], panel_name_buf[64], location_buf[64];
    
    if (!find_json_value(config_json, "client_id", client_id_buf, sizeof(client_id_buf)) ||
        !find_json_value(config_json, "panel_id", panel_id_buf, sizeof(panel_id_buf))) {
        ESP_LOGE(TAG, "Missing required fields");
        free(params);
        return ESP_ERR_INVALID_ARG;
    }
    
    strncpy(params->client_id, client_id_buf, sizeof(params->client_id) - 1);
    strncpy(params->panel_id, panel_id_buf, sizeof(params->panel_id) - 1);
    
    ESP_LOGI(TAG, "Saving configuration: client=%s, panel=%s", 
             params->client_id, params->panel_id);
    
    if (config_manager_set_str("client_id", params->client_id) != ESP_OK) {
        ESP_LOGE(TAG, "Failed to save client_id");
        free(params);
        return ESP_FAIL;
    }
    
    if (config_manager_set_str("panel_id", params->panel_id) != ESP_OK) {
        ESP_LOGE(TAG, "Failed to save panel_id");
        free(params);
        return ESP_FAIL;
    }
    
    if (find_json_value(config_json, "panel_name", panel_name_buf, sizeof(panel_name_buf))) {
        config_manager_set_str("panel_name", panel_name_buf);
    }
    
    if (find_json_value(config_json, "location", location_buf, sizeof(location_buf))) {
        config_manager_set_str("location", location_buf);
    }
    
    esp_err_t ret = mqtt_manager_set_panel_config(params->client_id, params->panel_id);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to set MQTT panel config");
        free(params);
        return ret;
    }
    
    ESP_LOGI(TAG, "Configuration applied successfully");
    
    config_manager_get_str("esp32_id", params->esp32_id, sizeof(params->esp32_id));
    if (strlen(params->esp32_id) == 0) {
        strcpy(params->esp32_id, "42A8ACA0");
    }
    
    params->success = true;
    
    BaseType_t task_created = xTaskCreate(
        config_response_task,
        "cfg_resp",
        8192,
        params,
        5,
        NULL
    );
    
    if (task_created != pdPASS) {
        ESP_LOGE(TAG, "Failed to create response task");
        free(params);
        return ESP_FAIL;
    }
    
    ESP_LOGI(TAG, "Config response task created with 8KB stack");
    return ESP_OK;
}