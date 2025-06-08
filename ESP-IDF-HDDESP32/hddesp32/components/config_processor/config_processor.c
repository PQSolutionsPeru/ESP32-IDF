#include <string.h>
#include <stdlib.h>
#include "esp_log.h"
#include "cJSON.h"
#include "config_manager.h"
#include "mqtt_manager.h"
#include "relay_manager.h"

static const char *TAG = "CONFIG_PROC";

esp_err_t process_esp32_configuration(const char *config_json) {
    if (!config_json) {
        ESP_LOGE(TAG, "Invalid configuration JSON");
        return ESP_ERR_INVALID_ARG;
    }
    
    ESP_LOGI(TAG, "Processing configuration: %s", config_json);
    
    // Parse JSON
    cJSON *root = cJSON_Parse(config_json);
    if (!root) {
        ESP_LOGE(TAG, "Failed to parse configuration JSON");
        mqtt_manager_send_config_response(false, "Invalid JSON format");
        return ESP_ERR_INVALID_ARG;
    }
    
    esp_err_t ret = ESP_OK;
    bool config_applied = false;
    
    // Extract required fields
    cJSON *client_id = cJSON_GetObjectItem(root, "client_id");
    cJSON *panel_id = cJSON_GetObjectItem(root, "panel_id");
    cJSON *panel_name = cJSON_GetObjectItem(root, "panel_name");
    cJSON *location = cJSON_GetObjectItem(root, "location");
    cJSON *status = cJSON_GetObjectItem(root, "status");
    
    if (!cJSON_IsString(client_id) || !cJSON_IsString(panel_id)) {
        ESP_LOGE(TAG, "Missing required fields: client_id or panel_id");
        mqtt_manager_send_config_response(false, "Missing required fields");
        ret = ESP_ERR_INVALID_ARG;
        goto cleanup;
    }
    
    // Save configuration to NVS
    ESP_LOGI(TAG, "Saving configuration: client=%s, panel=%s", 
             client_id->valuestring, panel_id->valuestring);
    
    // Save client_id
    if (config_manager_set_str("client_id", client_id->valuestring) != ESP_OK) {
        ESP_LOGE(TAG, "Failed to save client_id");
        ret = ESP_FAIL;
        goto cleanup;
    }
    
    // Save panel_id
    if (config_manager_set_str("panel_id", panel_id->valuestring) != ESP_OK) {
        ESP_LOGE(TAG, "Failed to save panel_id");
        ret = ESP_FAIL;
        goto cleanup;
    }
    
    // Save panel_name if present
    if (cJSON_IsString(panel_name)) {
        config_manager_set_str("panel_name", panel_name->valuestring);
    }
    
    // Save location if present
    if (cJSON_IsString(location)) {
        config_manager_set_str("location", location->valuestring);
    }
    
    // Configure MQTT with panel info
    ret = mqtt_manager_set_panel_config(client_id->valuestring, panel_id->valuestring);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to set MQTT panel config");
        mqtt_manager_send_config_response(false, "Failed to configure MQTT");
        goto cleanup;
    }
    
    // Process relays configuration if present
    cJSON *relays = cJSON_GetObjectItem(root, "relays");
    if (cJSON_IsObject(relays)) {
        ESP_LOGI(TAG, "Processing relay configuration");
        
        cJSON *relay = NULL;
        cJSON_ArrayForEach(relay, relays) {
            const char *relay_name = relay->string;
            if (relay_name && cJSON_IsObject(relay)) {
                cJSON *relay_status = cJSON_GetObjectItem(relay, "status");
                if (cJSON_IsString(relay_status)) {
                    ESP_LOGI(TAG, "Relay %s initial status: %s", 
                            relay_name, relay_status->valuestring);
                    // Here you could initialize relay states if needed
                }
            }
        }
    }
    
    // Subscribe to panel-specific topics
    char status_topic[MQTT_TOPIC_MAX_LENGTH];
    char relay_topic[MQTT_TOPIC_MAX_LENGTH];
    char command_topic[MQTT_TOPIC_MAX_LENGTH];
    
    snprintf(status_topic, sizeof(status_topic), 
             "clients/%s/panels/%s/status", 
             client_id->valuestring, panel_id->valuestring);
    
    snprintf(relay_topic, sizeof(relay_topic), 
             "clients/%s/panels/%s/relay_config", 
             client_id->valuestring, panel_id->valuestring);
    
    snprintf(command_topic, sizeof(command_topic), 
             "clients/%s/panels/%s/command", 
             client_id->valuestring, panel_id->valuestring);
    
    // Subscribe to topics
    mqtt_manager_subscribe(status_topic, 1);
    mqtt_manager_subscribe(relay_topic, 1);
    mqtt_manager_subscribe(command_topic, 1);
    
    config_applied = true;
    ESP_LOGI(TAG, "Configuration applied successfully");
    
    // Send success response
    mqtt_manager_send_config_response(true, "Configuration applied successfully");
    
    // Send status update to confirm we're now REGISTERED/ONLINE
    char online_json[256];
    snprintf(online_json, sizeof(online_json),
             "{\"esp32_id\":\"%s\",\"status\":\"ONLINE\",\"client_id\":\"%s\",\"panel_id\":\"%s\"}",
             client_id->valuestring,  // This should be esp32_id but we don't have it here
             client_id->valuestring,
             panel_id->valuestring);
    
    // Publish to both generic and specific status topics
    mqtt_manager_publish("esp32/status", online_json, strlen(online_json), 1, false);
    mqtt_manager_publish(status_topic, online_json, strlen(online_json), 1, false);
    
cleanup:
    cJSON_Delete(root);
    
    if (!config_applied && ret != ESP_OK) {
        mqtt_manager_send_config_response(false, "Configuration processing failed");
    }
    
    return ret;
}