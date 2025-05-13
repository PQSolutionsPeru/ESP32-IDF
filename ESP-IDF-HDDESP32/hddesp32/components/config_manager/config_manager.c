#include "config_manager.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_log.h"
#include <string.h>

static const char *TAG = "CONFIG_MGR";
static nvs_handle_t config_handle = 0;  // Cambiado de nvs_handle a config_handle
static bool is_initialized = false;

esp_err_t config_manager_init(void) {
    if (is_initialized) {
        return ESP_OK;
    }
    
    ESP_LOGI(TAG, "Initializing configuration manager");
    
    // Inicializar NVS
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_LOGW(TAG, "NVS partition was truncated and needs to be erased");
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);
    
    // Abrir namespace NVS
    err = nvs_open(CONFIG_NVS_NAMESPACE, NVS_READWRITE, &config_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error opening NVS namespace: %s", esp_err_to_name(err));
        return err;
    }
    
    is_initialized = true;
    ESP_LOGI(TAG, "Configuration manager initialized successfully");
    return ESP_OK;
}

esp_err_t config_manager_deinit(void) {
    if (!is_initialized) {
        return ESP_OK;
    }
    
    nvs_close(config_handle);
    config_handle = 0;
    is_initialized = false;
    
    ESP_LOGI(TAG, "Configuration manager deinitialized");
    return ESP_OK;
}

esp_err_t config_manager_get_str(const char *key, char *out_value, size_t max_len) {
    if (!is_initialized) {
        ESP_LOGE(TAG, "Configuration manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (!key || !out_value || max_len == 0) {
        return ESP_ERR_INVALID_ARG;
    }
    
    size_t required_size = max_len;
    esp_err_t err = nvs_get_str(config_handle, key, out_value, &required_size);
    
    if (err == ESP_ERR_NVS_NOT_FOUND) {
        ESP_LOGD(TAG, "Key '%s' not found", key);
    } else if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error getting string '%s': %s", key, esp_err_to_name(err));
    }
    
    return err;
}

esp_err_t config_manager_set_str(const char *key, const char *value) {
    if (!is_initialized) {
        ESP_LOGE(TAG, "Configuration manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (!key || !value) {
        return ESP_ERR_INVALID_ARG;
    }
    
    esp_err_t err = nvs_set_str(config_handle, key, value);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error setting string '%s': %s", key, esp_err_to_name(err));
        return err;
    }
    
    err = nvs_commit(config_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error committing changes: %s", esp_err_to_name(err));
    }
    
    return err;
}

esp_err_t config_manager_get_i32(const char *key, int32_t *out_value) {
    if (!is_initialized) {
        ESP_LOGE(TAG, "Configuration manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (!key || !out_value) {
        return ESP_ERR_INVALID_ARG;
    }
    
    esp_err_t err = nvs_get_i32(config_handle, key, out_value);
    
    if (err == ESP_ERR_NVS_NOT_FOUND) {
        ESP_LOGD(TAG, "Key '%s' not found", key);
    } else if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error getting i32 '%s': %s", key, esp_err_to_name(err));
    }
    
    return err;
}

esp_err_t config_manager_set_i32(const char *key, int32_t value) {
    if (!is_initialized) {
        ESP_LOGE(TAG, "Configuration manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (!key) {
        return ESP_ERR_INVALID_ARG;
    }
    
    esp_err_t err = nvs_set_i32(config_handle, key, value);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error setting i32 '%s': %s", key, esp_err_to_name(err));
        return err;
    }
    
    err = nvs_commit(config_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error committing changes: %s", esp_err_to_name(err));
    }
    
    return err;
}

esp_err_t config_manager_get_blob(const char *key, void *out_value, size_t *length) {
    if (!is_initialized) {
        ESP_LOGE(TAG, "Configuration manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (!key || !out_value || !length) {
        return ESP_ERR_INVALID_ARG;
    }
    
    esp_err_t err = nvs_get_blob(config_handle, key, out_value, length);
    
    if (err == ESP_ERR_NVS_NOT_FOUND) {
        ESP_LOGD(TAG, "Key '%s' not found", key);
    } else if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error getting blob '%s': %s", key, esp_err_to_name(err));
    }
    
    return err;
}

esp_err_t config_manager_set_blob(const char *key, const void *value, size_t length) {
    if (!is_initialized) {
        ESP_LOGE(TAG, "Configuration manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (!key || !value || length == 0) {
        return ESP_ERR_INVALID_ARG;
    }
    
    esp_err_t err = nvs_set_blob(config_handle, key, value, length);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error setting blob '%s': %s", key, esp_err_to_name(err));
        return err;
    }
    
    err = nvs_commit(config_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error committing changes: %s", esp_err_to_name(err));
    }
    
    return err;
}

esp_err_t config_manager_erase_key(const char *key) {
    if (!is_initialized) {
        ESP_LOGE(TAG, "Configuration manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (!key) {
        return ESP_ERR_INVALID_ARG;
    }
    
    esp_err_t err = nvs_erase_key(config_handle, key);
    if (err == ESP_ERR_NVS_NOT_FOUND) {
        // Key not found is not an error for erase operation
        return ESP_ERR_NOT_FOUND;
    } else if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error erasing key '%s': %s", key, esp_err_to_name(err));
        return err;
    }
    
    err = nvs_commit(config_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error committing changes: %s", esp_err_to_name(err));
    }
    
    return ESP_OK;
}

esp_err_t config_manager_erase_all(void) {
    if (!is_initialized) {
        ESP_LOGE(TAG, "Configuration manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    esp_err_t err = nvs_erase_all(config_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error erasing all keys: %s", esp_err_to_name(err));
        return err;
    }
    
    err = nvs_commit(config_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error committing changes: %s", esp_err_to_name(err));
    }
    
    ESP_LOGW(TAG, "All configuration erased");
    return err;
}

esp_err_t config_manager_get_namespace_stats(size_t *used_entries, size_t *free_entries) {
    if (!is_initialized) {
        ESP_LOGE(TAG, "Configuration manager not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (!used_entries || !free_entries) {
        return ESP_ERR_INVALID_ARG;
    }
    
    nvs_stats_t stats;
    esp_err_t err = nvs_get_stats(NULL, &stats);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error getting NVS stats: %s", esp_err_to_name(err));
        return err;
    }
    
    *used_entries = stats.used_entries;
    *free_entries = stats.free_entries;
    
    return ESP_OK;
}

bool config_manager_key_exists(const char *key) {
    if (!is_initialized || !key) {
        return false;
    }
    
    size_t length = 0;
    esp_err_t err = nvs_get_str(config_handle, key, NULL, &length);
    
    if (err == ESP_OK && length > 0) {
        return true;
    }
    
    // Try as integer
    int32_t value;
    err = nvs_get_i32(config_handle, key, &value);
    if (err == ESP_OK) {
        return true;
    }
    
    // Try as blob
    err = nvs_get_blob(config_handle, key, NULL, &length);
    if (err == ESP_OK && length > 0) {
        return true;
    }
    
    return false;
}