#include "spiffs_log.h"

#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>
#include "esp_spiffs.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

#define LOG_FILE       "/spiffs/log.txt"
#define PREV_LOG_FILE  "/spiffs/log.prev"
#define LOG_MAX_SIZE   (300 * 1024)  // 300KB

static const char *TAG = "LOG_STORAGE";
static SemaphoreHandle_t s_log_mutex;
static bool s_log_initialized = false;

static esp_err_t check_rotate(FILE *f) {
    if (!f) {
        return ESP_FAIL;
    }
    
    long size = ftell(f);
    if (size < 0) {
        return ESP_FAIL;
    }
    
    // Solo rotar si el archivo es demasiado grande
    if (size < LOG_MAX_SIZE) {
        return ESP_OK;
    }
    
    ESP_LOGI(TAG, "Log file size %ld exceeds limit %d, rotating", size, LOG_MAX_SIZE);
    
    fclose(f);
    
    // Eliminar log.prev anterior si existe
    if (access(PREV_LOG_FILE, F_OK) == 0) {
        ESP_LOGI(TAG, "Removing existing log.prev");
        unlink(PREV_LOG_FILE);
    }
    
    // Mover log.txt a log.prev
    if (rename(LOG_FILE, PREV_LOG_FILE) != 0) {
        ESP_LOGE(TAG, "Failed to rename log.txt to log.prev");
        return ESP_FAIL;
    }
    
    // Crear nuevo log.txt vacío
    FILE *nf = fopen(LOG_FILE, "w");
    if (nf) {
        fclose(nf);
        ESP_LOGI(TAG, "New log.txt created after rotation");
    } else {
        ESP_LOGE(TAG, "Failed to create new log.txt");
        return ESP_FAIL;
    }
    
    return ESP_OK;
}

esp_err_t log_storage_init(void) {
    if (s_log_initialized) {
        ESP_LOGW(TAG, "Log storage already initialized");
        return ESP_OK;
    }
    
    s_log_mutex = xSemaphoreCreateMutex();
    if (!s_log_mutex) {
        ESP_LOGE(TAG, "Failed to create log mutex");
        return ESP_ERR_NO_MEM;
    }

    esp_vfs_spiffs_conf_t conf = {
        .base_path = "/spiffs",
        .partition_label = NULL,
        .max_files = 5,
        .format_if_mount_failed = true
    };
    
    esp_err_t ret = esp_vfs_spiffs_register(&conf);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "SPIFFS mount failed: %s", esp_err_to_name(ret));
        vSemaphoreDelete(s_log_mutex);
        s_log_mutex = NULL;
        return ret;
    }
    
    // Verificar integridad del sistema de archivos
    size_t total = 0, used = 0;
    ret = esp_spiffs_info(conf.partition_label, &total, &used);
    if (ret == ESP_OK) {
        ESP_LOGI(TAG, "SPIFFS: %d KB total, %d KB used", total / 1024, used / 1024);
    } else {
        ESP_LOGW(TAG, "Failed to get SPIFFS info: %s", esp_err_to_name(ret));
    }
    
    s_log_initialized = true;
    ESP_LOGI(TAG, "Log storage initialized successfully");
    return ESP_OK;
}

void log_storage_deinit(void) {
    if (!s_log_initialized) {
        return;
    }
    
    if (s_log_mutex) {
        vSemaphoreDelete(s_log_mutex);
        s_log_mutex = NULL;
    }
    
    esp_vfs_spiffs_unregister(NULL);
    s_log_initialized = false;
    
    ESP_LOGI(TAG, "Log storage deinitialized");
}

int spiffs_vprintf(const char *fmt, va_list ap) {
    va_list ap_copy;
    va_copy(ap_copy, ap);
    
    // Siempre imprimir a consola primero
    int res = vprintf(fmt, ap);

    // Solo escribir a archivo si el sistema está inicializado
    if (s_log_initialized && s_log_mutex && xSemaphoreTake(s_log_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        FILE *f = fopen(LOG_FILE, "a");
        if (f) {
            vfprintf(f, fmt, ap_copy);
            fflush(f);
            
            // Solo verificar rotación, no forzarla
            esp_err_t check_result = check_rotate(f);
            if (check_result != ESP_OK) {
                // Si check_rotate cerró el archivo, no lo cerremos de nuevo
                if (check_result != ESP_OK) {
                    // El archivo ya fue cerrado por check_rotate
                } else {
                    fclose(f);
                }
            } else {
                fclose(f);
            }
        }
        xSemaphoreGive(s_log_mutex);
    }
    
    va_end(ap_copy);
    return res;
}

esp_err_t log_storage_rotate_if_needed(void) {
    if (!s_log_initialized) {
        ESP_LOGE(TAG, "Log storage not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Checking if log rotation is needed...");
    
    if (xSemaphoreTake(s_log_mutex, pdMS_TO_TICKS(5000)) != pdTRUE) {
        ESP_LOGE(TAG, "Failed to acquire mutex for rotation check");
        return ESP_ERR_TIMEOUT;
    }
    
    // Verificar si existe log.txt
    struct stat st;
    if (stat(LOG_FILE, &st) != 0) {
        ESP_LOGD(TAG, "No log.txt found, nothing to rotate");
        xSemaphoreGive(s_log_mutex);
        return ESP_ERR_NOT_FOUND;
    }
    
    ESP_LOGI(TAG, "log.txt found, size: %ld bytes", st.st_size);
    
    // Solo rotar si el archivo tiene contenido significativo Y es grande
    if (st.st_size > 0 && st.st_size >= (LOG_MAX_SIZE / 2)) { // 150KB threshold
        ESP_LOGI(TAG, "Log file size warrants rotation, proceeding...");
        
        // Eliminar log.prev anterior si existe
        if (access(PREV_LOG_FILE, F_OK) == 0) {
            ESP_LOGI(TAG, "Removing existing log.prev");
            unlink(PREV_LOG_FILE);
        }
        
        // Mover log.txt a log.prev
        if (rename(LOG_FILE, PREV_LOG_FILE) == 0) {
            ESP_LOGI(TAG, "Log rotation completed successfully");
            
            // Verificar que log.prev se creó correctamente
            if (stat(PREV_LOG_FILE, &st) == 0) {
                ESP_LOGI(TAG, "log.prev created successfully, size: %ld bytes", st.st_size);
            } else {
                ESP_LOGE(TAG, "log.prev was not created properly");
                xSemaphoreGive(s_log_mutex);
                return ESP_FAIL;
            }
            
            // Crear nuevo log.txt
            FILE *new_log = fopen(LOG_FILE, "w");
            if (new_log) {
                fclose(new_log);
                ESP_LOGI(TAG, "New log.txt created");
            } else {
                ESP_LOGW(TAG, "Failed to create new log.txt, will be created on next log write");
            }
            
            xSemaphoreGive(s_log_mutex);
            return ESP_OK;
        } else {
            ESP_LOGE(TAG, "Failed to rotate log file");
            xSemaphoreGive(s_log_mutex);
            return ESP_FAIL;
        }
    } else {
        ESP_LOGD(TAG, "Log file rotation not needed (size: %ld, threshold: %d)", 
                st.st_size, LOG_MAX_SIZE / 2);
        xSemaphoreGive(s_log_mutex);
        return ESP_ERR_NOT_FOUND; // No rotation needed
    }
}

esp_err_t log_storage_force_rotate(void) {
    if (!s_log_initialized) {
        ESP_LOGE(TAG, "Log storage not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Forcing log rotation...");
    
    if (xSemaphoreTake(s_log_mutex, pdMS_TO_TICKS(5000)) != pdTRUE) {
        ESP_LOGE(TAG, "Failed to acquire mutex for forced rotation");
        return ESP_ERR_TIMEOUT;
    }
    
    // Verificar si existe log.txt
    struct stat st;
    if (stat(LOG_FILE, &st) != 0) {
        ESP_LOGW(TAG, "No log.txt found for forced rotation");
        xSemaphoreGive(s_log_mutex);
        return ESP_ERR_NOT_FOUND;
    }
    
    if (st.st_size == 0) {
        ESP_LOGW(TAG, "log.txt is empty, skipping forced rotation");
        xSemaphoreGive(s_log_mutex);
        return ESP_ERR_INVALID_SIZE;
    }
    
    ESP_LOGI(TAG, "Forcing rotation of log.txt (%ld bytes)", st.st_size);
    
    // Eliminar log.prev anterior si existe
    if (access(PREV_LOG_FILE, F_OK) == 0) {
        ESP_LOGI(TAG, "Removing existing log.prev for forced rotation");
        unlink(PREV_LOG_FILE);
    }
    
    // Mover log.txt a log.prev
    if (rename(LOG_FILE, PREV_LOG_FILE) == 0) {
        ESP_LOGI(TAG, "Forced log rotation completed successfully");
        
        // Crear nuevo log.txt
        FILE *new_log = fopen(LOG_FILE, "w");
        if (new_log) {
            fclose(new_log);
            ESP_LOGI(TAG, "New log.txt created after forced rotation");
        }
        
        xSemaphoreGive(s_log_mutex);
        return ESP_OK;
    } else {
        ESP_LOGE(TAG, "Failed to perform forced rotation");
        xSemaphoreGive(s_log_mutex);
        return ESP_FAIL;
    }
}

// Función legacy para compatibilidad - ahora llama a log_storage_rotate_if_needed
esp_err_t log_storage_rotate(void) {
    return log_storage_rotate_if_needed();
}

esp_err_t log_storage_get_info(size_t *log_size, size_t *prev_log_size) {
    if (!s_log_initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (log_size) {
        struct stat st;
        if (stat(LOG_FILE, &st) == 0) {
            *log_size = st.st_size;
        } else {
            *log_size = 0;
        }
    }
    
    if (prev_log_size) {
        struct stat st;
        if (stat(PREV_LOG_FILE, &st) == 0) {
            *prev_log_size = st.st_size;
        } else {
            *prev_log_size = 0;
        }
    }
    
    return ESP_OK;
}