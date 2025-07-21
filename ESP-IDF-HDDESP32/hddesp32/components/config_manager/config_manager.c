#include "config_manager.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/timers.h"
#include <string.h>

static const char *TAG = "CONFIG_MGR";
static nvs_handle_t config_handle = 0;
static bool is_initialized = false;

static uint32_t write_operations_count = 0;
static int64_t last_commit_time = 0;
static bool pending_commit = false;
static TimerHandle_t commit_timer = NULL;

#define MIN_COMMIT_INTERVAL_MS 5000
#define MAX_PENDING_TIME_MS 30000

static void delayed_commit_callback(TimerHandle_t xTimer) {
    if (pending_commit && config_handle) {
        esp_err_t err = nvs_commit(config_handle);
        if (err == ESP_OK) {
            last_commit_time = esp_timer_get_time() / 1000;
            pending_commit = false;
            ESP_LOGD(TAG, "Delayed commit completed, total writes: %lu", write_operations_count);
        } else {
            ESP_LOGW(TAG, "Delayed commit failed: %s", esp_err_to_name(err));
        }
    }
}

static esp_err_t schedule_commit(void) {
    int64_t current_time = esp_timer_get_time() / 1000;
    
    if (!pending_commit) {
        pending_commit = true;
        if (commit_timer) {
            xTimerReset(commit_timer, pdMS_TO_TICKS(100));
        }
    }
    return ESP_OK;
}

static bool value_changed_str(const char *key, const char *new_value) {
    char current_value[CONFIG_MAX_STRING_LENGTH];
    esp_err_t err = nvs_get_str(config_handle, key, current_value, &(size_t){sizeof(current_value)});
    
    if (err == ESP_ERR_NVS_NOT_FOUND) {
        return true;
    } else if (err == ESP_OK) {
        return strcmp(current_value, new_value) != 0;
    }
    
    return true;
}

static bool value_changed_i32(const char *key, int32_t new_value) {
    int32_t current_value;
    esp_err_t err = nvs_get_i32(config_handle, key, &current_value);
    
    if (err == ESP_ERR_NVS_NOT_FOUND) {
        return true;
    } else if (err == ESP_OK) {
        return current_value != new_value;
    }
    
    return true;
}

static bool value_changed_blob(const char *key, const void *new_value, size_t length) {
    void *current_value = malloc(length);
    if (!current_value) {
        return true;
    }
    
    size_t current_length = length;
    esp_err_t err = nvs_get_blob(config_handle, key, current_value, &current_length);
    
    bool changed = true;
    if (err == ESP_OK && current_length == length) {
        changed = memcmp(current_value, new_value, length) != 0;
    }
    
    free(current_value);
    return changed;
}

esp_err_t config_manager_init(void) {
    if (is_initialized) {
        return ESP_OK;
    }
    
    ESP_LOGI(TAG, "Initializing configuration manager with wear protection");
    
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_LOGW(TAG, "NVS partition was truncated and needs to be erased");
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);
    
    err = nvs_open(CONFIG_NVS_NAMESPACE, NVS_READWRITE, &config_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error opening NVS namespace: %s", esp_err_to_name(err));
        return err;
    }
    
    commit_timer = xTimerCreate(
        "nvs_commit",
        pdMS_TO_TICKS(MIN_COMMIT_INTERVAL_MS),
        pdFALSE,
        NULL,
        delayed_commit_callback
    );
    
    if (!commit_timer) {
        ESP_LOGW(TAG, "Failed to create commit timer, using immediate commits");
    }
    
    write_operations_count = 0;
    last_commit_time = esp_timer_get_time() / 1000;
    pending_commit = false;
    is_initialized = true;
    
    ESP_LOGI(TAG, "Configuration manager initialized with wear leveling protection");
    return ESP_OK;
}

esp_err_t config_manager_deinit(void) {
    if (!is_initialized) {
        return ESP_OK;
    }
    
    if (pending_commit && config_handle) {
        nvs_commit(config_handle);
    }
    
    if (commit_timer) {
        xTimerDelete(commit_timer, portMAX_DELAY);
        commit_timer = NULL;
    }
    
    nvs_close(config_handle);
    config_handle = 0;
    is_initialized = false;
    
    ESP_LOGI(TAG, "Configuration manager deinitialized, total writes: %lu", write_operations_count);
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
    
    if (!value_changed_str(key, value)) {
        ESP_LOGD(TAG, "Value unchanged for key '%s', skipping write", key);
        return ESP_OK;
    }
    
    esp_err_t err = nvs_set_str(config_handle, key, value);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error setting string '%s': %s", key, esp_err_to_name(err));
        return err;
    }
    
    write_operations_count++;
    ESP_LOGD(TAG, "Write operation #%lu for key '%s'", write_operations_count, key);
    
    return schedule_commit();
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
    
    if (!value_changed_i32(key, value)) {
        ESP_LOGD(TAG, "Value unchanged for key '%s', skipping write", key);
        return ESP_OK;
    }
    
    esp_err_t err = nvs_set_i32(config_handle, key, value);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error setting i32 '%s': %s", key, esp_err_to_name(err));
        return err;
    }
    
    write_operations_count++;
    ESP_LOGD(TAG, "Write operation #%lu for key '%s'", write_operations_count, key);
    
    return schedule_commit();
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
    
    if (!value_changed_blob(key, value, length)) {
        ESP_LOGD(TAG, "Value unchanged for key '%s', skipping write", key);
        return ESP_OK;
    }
    
    esp_err_t err = nvs_set_blob(config_handle, key, value, length);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error setting blob '%s': %s", key, esp_err_to_name(err));
        return err;
    }
    
    write_operations_count++;
    ESP_LOGD(TAG, "Write operation #%lu for key '%s'", write_operations_count, key);
    
    return schedule_commit();
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
        return ESP_ERR_NOT_FOUND;
    } else if (err != ESP_OK) {
        ESP_LOGE(TAG, "Error erasing key '%s': %s", key, esp_err_to_name(err));
        return err;
    }
    
    write_operations_count++;
    ESP_LOGD(TAG, "Erase operation #%lu for key '%s'", write_operations_count, key);
    
    return schedule_commit();
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
    
    write_operations_count++;
    ESP_LOGW(TAG, "All configuration erased, operation #%lu", write_operations_count);
    
    err = nvs_commit(config_handle);
    if (err == ESP_OK) {
        last_commit_time = esp_timer_get_time() / 1000;
        pending_commit = false;
    }
    
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
    
    int32_t value;
    err = nvs_get_i32(config_handle, key, &value);
    if (err == ESP_OK) {
        return true;
    }
    
    err = nvs_get_blob(config_handle, key, NULL, &length);
    if (err == ESP_OK && length > 0) {
        return true;
    }
    
    return false;
}

uint32_t config_manager_get_write_count(void) {
    return write_operations_count;
}

esp_err_t config_manager_force_commit(void) {
    if (!is_initialized || !config_handle) {
        return ESP_ERR_INVALID_STATE;
    }
    
    esp_err_t err = nvs_commit(config_handle);
    if (err == ESP_OK) {
        last_commit_time = esp_timer_get_time() / 1000;
        pending_commit = false;
        ESP_LOGI(TAG, "Forced commit completed");
    }
    
    return err;
}