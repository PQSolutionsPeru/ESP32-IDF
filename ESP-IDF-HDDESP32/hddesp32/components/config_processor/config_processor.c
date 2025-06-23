#include <string.h>
#include <stdlib.h>
#include "esp_log.h"
#include "cJSON.h"
#include "config_manager.h"
#include "mqtt_manager.h"
#include "relay_manager.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "CONFIG_PROC";

typedef struct {
    char client_id[32];
    char panel_id[32];
    char esp32_id[32];
    bool success;
} config_task_params_t;

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
        
        ESP_LOGI(TAG, "Subscribing to panel topics");
        
        char topic[MQTT_TOPIC_MAX_LENGTH];
        
        snprintf(topic, sizeof(topic), "clients/%s/panels/%s/status", 
                 params->client_id, params->panel_id);
        mqtt_manager_subscribe(topic, 1);
        vTaskDelay(pdMS_TO_TICKS(100));
        
        snprintf(topic, sizeof(topic), "clients/%s/panels/%s/relay_config", 
                 params->client_id, params->panel_id);
        mqtt_manager_subscribe(topic, 1);
        vTaskDelay(pdMS_TO_TICKS(100));
        
        snprintf(topic, sizeof(topic), "clients/%s/panels/%s/command", 
                 params->client_id, params->panel_id);
        mqtt_manager_subscribe(topic, 1);
        vTaskDelay(pdMS_TO_TICKS(100));
        
        vTaskDelay(pdMS_TO_TICKS(1000));
        
        ESP_LOGI(TAG, "Sending initial relay states report");
        if (relay_manager_get_mgr_state() == RELAY_MGR_STATE_RUNNING) {
            relay_manager_report_initial_states();
        }
        
        char online_json[256];
        snprintf(online_json, sizeof(online_json),
                 "{\"esp32_id\":\"%s\",\"status\":\"ONLINE\",\"client_id\":\"%s\",\"panel_id\":\"%s\"}",
                 params->esp32_id, params->client_id, params->panel_id);
        
        mqtt_manager_publish("esp32/status", online_json, strlen(online_json), 1, false);
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
    
    cJSON *root = cJSON_Parse(config_json);
    if (!root) {
        ESP_LOGE(TAG, "Failed to parse configuration JSON");
        return ESP_ERR_INVALID_ARG;
    }
    
    config_task_params_t *params = calloc(1, sizeof(config_task_params_t));
    if (!params) {
        cJSON_Delete(root);
        return ESP_ERR_NO_MEM;
    }
    
    cJSON *client_id = cJSON_GetObjectItem(root, "client_id");
    cJSON *panel_id = cJSON_GetObjectItem(root, "panel_id");
    cJSON *panel_name = cJSON_GetObjectItem(root, "panel_name");
    cJSON *location = cJSON_GetObjectItem(root, "location");
    
    if (!cJSON_IsString(client_id) || !cJSON_IsString(panel_id)) {
        ESP_LOGE(TAG, "Missing required fields");
        free(params);
        cJSON_Delete(root);
        return ESP_ERR_INVALID_ARG;
    }
    
    strncpy(params->client_id, client_id->valuestring, sizeof(params->client_id) - 1);
    strncpy(params->panel_id, panel_id->valuestring, sizeof(params->panel_id) - 1);
    
    ESP_LOGI(TAG, "Saving configuration: client=%s, panel=%s", 
             params->client_id, params->panel_id);
    
    if (config_manager_set_str("client_id", params->client_id) != ESP_OK) {
        ESP_LOGE(TAG, "Failed to save client_id");
        free(params);
        cJSON_Delete(root);
        return ESP_FAIL;
    }
    
    if (config_manager_set_str("panel_id", params->panel_id) != ESP_OK) {
        ESP_LOGE(TAG, "Failed to save panel_id");
        free(params);
        cJSON_Delete(root);
        return ESP_FAIL;
    }
    
    if (cJSON_IsString(panel_name)) {
        config_manager_set_str("panel_name", panel_name->valuestring);
    }
    
    if (cJSON_IsString(location)) {
        config_manager_set_str("location", location->valuestring);
    }
    
    esp_err_t ret = mqtt_manager_set_panel_config(params->client_id, params->panel_id);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to set MQTT panel config");
        free(params);
        cJSON_Delete(root);
        return ret;
    }
    
    ESP_LOGI(TAG, "Configuration applied successfully");
    
    config_manager_get_str("esp32_id", params->esp32_id, sizeof(params->esp32_id));
    if (strlen(params->esp32_id) == 0) {
        strcpy(params->esp32_id, "42A8ACA0");
    }
    
    params->success = true;
    
    cJSON_Delete(root);
    
    // STACK CALCULADO: 6144 bytes (6KB) - basado en análisis detallado de operaciones MQTT
    BaseType_t task_created = xTaskCreate(
        config_response_task,
        "cfg_resp",
        6144,  // CALCULADO: 612 bytes variables + 2500 bytes MQTT + 1500 bytes overhead + 1500 bytes margen
        params,
        5,
        NULL
    );
    
    if (task_created != pdPASS) {
        ESP_LOGE(TAG, "Failed to create response task");
        free(params);
        return ESP_FAIL;
    }
    
    ESP_LOGI(TAG, "Config response task created with calculated stack (6144 bytes)");
    return ESP_OK;
}