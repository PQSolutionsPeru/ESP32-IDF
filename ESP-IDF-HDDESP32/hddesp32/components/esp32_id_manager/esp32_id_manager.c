#include "esp32_id_manager.h"
#include "config_manager.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_mac.h" // Añade esta cabecera para esp_efuse_mac_get_default
#include "nvs_flash.h" // Asegúrate de incluir esta cabecera para los códigos NVS
#include <string.h>
#include <stdbool.h>

static const char *TAG = "ESP32_ID_MGR";
static const char *ID_KEY = "esp32_id";
// static const char *ID_HISTORY_KEY = "id_history"; // Comentado porque no se usa
static const char *ID_EVENT_KEY = "id_event";

static char esp32_id[ESP32_ID_LENGTH + 1] = {0};
static char mac_address[ESP32_MAC_STR_LENGTH + 1] = {0};
static bool is_initialized = false;
static bool is_valid = false;

// Obtiene la dirección MAC como cadena
static esp_err_t get_mac_address(char *mac_out, size_t max_len) {
    uint8_t mac[6];
    esp_err_t err = esp_efuse_mac_get_default(mac);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to get MAC address: %s", esp_err_to_name(err));
        return err;
    }
    
    // Formatea la dirección MAC como una cadena (hexadecimal sin separadores)
    snprintf(mac_out, max_len, "%02X%02X%02X%02X%02X%02X", 
             mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    
    return ESP_OK;
}

// Genera un ID único basado en la dirección MAC
static esp_err_t generate_unique_id(char *id_out, size_t max_len) {
    char mac[ESP32_MAC_STR_LENGTH + 1];
    esp_err_t err = get_mac_address(mac, sizeof(mac));
    if (err != ESP_OK) {
        return err;
    }
    
    // Crea ID usando el formato del código original: last_4_mac + "AC" + first_2_mac
    if (max_len < ESP32_ID_LENGTH + 1) {
        ESP_LOGE(TAG, "Buffer too small for ID");
        return ESP_ERR_INVALID_SIZE;
    }
    
    // Obtiene los últimos 4 caracteres de MAC
    const char *mac_end = mac + strlen(mac) - 4;
    // Obtiene los primeros 2 caracteres de MAC
    char mac_start[3] = {mac[0], mac[1], '\0'};
    
    // Crea ID (8 caracteres: 4 + 2 + 2)
    snprintf(id_out, max_len, "%s%s%s", mac_end, "AC", mac_start);
    
    // Añade al historial de eventos
    char event_data[64];
    snprintf(event_data, sizeof(event_data), "Generated new ID: %s", id_out);
    config_manager_set_str(ID_EVENT_KEY, event_data);
    
    return ESP_OK;
}

static esp_err_t load_or_generate_id(void) {
    // Intenta cargar un ID existente
    esp_err_t err = config_manager_get_str(ID_KEY, esp32_id, sizeof(esp32_id));
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "Loaded existing ID: %s", esp32_id);
        
        // Añade al historial de eventos
        char event_data[64];
        snprintf(event_data, sizeof(event_data), "Loaded existing ID: %s", esp32_id);
        config_manager_set_str(ID_EVENT_KEY, event_data);
        
        is_valid = true;
        return ESP_OK;
    }
    
    // Si no se encuentra, genera un nuevo ID
    err = generate_unique_id(esp32_id, sizeof(esp32_id));
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to generate ID: %s", esp_err_to_name(err));
        return err;
    }
    
    // Guarda el nuevo ID
    err = config_manager_set_str(ID_KEY, esp32_id);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to save ID: %s", esp_err_to_name(err));
        return err;
    }
    
    ESP_LOGI(TAG, "Generated and saved new ID: %s", esp32_id);
    is_valid = true;
    return ESP_OK;
}

esp_err_t esp32_id_manager_init(void) {
    if (is_initialized) {
        return ESP_OK;
    }
    
    ESP_LOGI(TAG, "Initializing ESP32 ID Manager");
    
    // Obtiene y almacena la dirección MAC
    esp_err_t err = get_mac_address(mac_address, sizeof(mac_address));
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to get MAC address: %s", esp_err_to_name(err));
        return err;
    }
    
    ESP_LOGI(TAG, "MAC Address: %s", mac_address);
    
    // Carga o genera el ID de ESP32
    err = load_or_generate_id();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to initialize ID: %s", esp_err_to_name(err));
        is_valid = false;
        return err;
    }
    
    is_initialized = true;
    ESP_LOGI(TAG, "ESP32 ID Manager initialized successfully with ID: %s", esp32_id);
    return ESP_OK;
}

esp_err_t esp32_id_manager_get_id(char *id_out, size_t max_len) {
    if (!is_initialized) {
        ESP_LOGE(TAG, "ESP32 ID Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (max_len < ESP32_ID_LENGTH + 1) {
        ESP_LOGE(TAG, "Buffer too small for ID");
        return ESP_ERR_INVALID_SIZE;
    }
    
    strncpy(id_out, esp32_id, max_len - 1);
    id_out[max_len - 1] = '\0';
    return ESP_OK;
}

esp_err_t esp32_id_manager_get_mac(char *mac_out, size_t max_len) {
    if (!is_initialized) {
        ESP_LOGE(TAG, "ESP32 ID Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (max_len < ESP32_MAC_STR_LENGTH + 1) {
        ESP_LOGE(TAG, "Buffer too small for MAC address");
        return ESP_ERR_INVALID_SIZE;
    }
    
    strncpy(mac_out, mac_address, max_len - 1);
    mac_out[max_len - 1] = '\0';
    return ESP_OK;
}

esp_err_t esp32_id_manager_reset(void) {
    if (!is_initialized) {
        ESP_LOGE(TAG, "ESP32 ID Manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGW(TAG, "Resetting ESP32 ID");
    
    // Borra el ID existente
    esp_err_t err = config_manager_erase_key(ID_KEY);
    if (err != ESP_OK && err != ESP_ERR_NOT_FOUND) { // Corregido aquí - ESP_ERR_NOT_FOUND en lugar de ESP_ERR_NVS_NOT_FOUND
        ESP_LOGE(TAG, "Failed to erase ID: %s", esp_err_to_name(err));
        return err;
    }
    
    // Genera y guarda un nuevo ID
    err = generate_unique_id(esp32_id, sizeof(esp32_id));
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to generate new ID: %s", esp_err_to_name(err));
        is_valid = false;
        return err;
    }
    
    // Guarda el nuevo ID
    err = config_manager_set_str(ID_KEY, esp32_id);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to save new ID: %s", esp_err_to_name(err));
        is_valid = false;
        return err;
    }
    
    ESP_LOGI(TAG, "ESP32 ID reset successfully, new ID: %s", esp32_id);
    is_valid = true;
    return ESP_OK;
}

bool esp32_id_manager_is_valid(void) {
    return is_initialized && is_valid;
}