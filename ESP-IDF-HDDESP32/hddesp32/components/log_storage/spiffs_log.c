#include "spiffs_log.h"

#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>
#include "esp_spiffs.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "time_manager.h"

#define LOG_FILE       "/spiffs/log.txt"
#define PREV_LOG_FILE  "/spiffs/log.prev"
#define LOG_MAX_SIZE   (300 * 1024)

static const char *TAG = "LOG_STORAGE";
static SemaphoreHandle_t s_log_mutex;
static bool s_log_initialized = false;

// Nueva función para obtener timestamp formateado
static void get_log_timestamp(char *buffer, size_t size) {
    if (size < 24) return;  // Mínimo necesario para el formato
    
    if (time_manager_is_synchronized()) {
        time_manager_get_lima_time_str(buffer, size);
    } else {
        time_t now = time(NULL);
        struct tm timeinfo;
        localtime_r(&now, &timeinfo);
        int ret = snprintf(buffer, size, "%02d/%02d/%04d, %02d:%02d:%02d",
                 timeinfo.tm_mday, timeinfo.tm_mon + 1, timeinfo.tm_year + 1900,
                 timeinfo.tm_hour, timeinfo.tm_min, timeinfo.tm_sec);
        if (ret >= (int)size) {
            // Truncación detectada, usar formato más corto
            snprintf(buffer, size, "%02d:%02d:%02d", 
                     timeinfo.tm_hour, timeinfo.tm_min, timeinfo.tm_sec);
        }
    }
}

// Macro para logs con timestamp
#define LOG_WITH_TS(level, format, ...) do { \
    char ts_buffer[64]; \
    get_log_timestamp(ts_buffer, sizeof(ts_buffer)); \
    ESP_LOG##level(TAG, "[%s] " format, ts_buffer, ##__VA_ARGS__); \
} while(0)

// Función para generar nombre de archivo con timestamp
static esp_err_t generate_timestamped_filename(char *filename, size_t max_len) {
    time_t now;
    struct tm timeinfo;
    
    if (time_manager_is_synchronized()) {
        now = time_manager_get_time();
    } else {
        time(&now);
    }
    
    // Convertir a tiempo Lima (GMT-5)
    now -= 5 * 3600;
    gmtime_r(&now, &timeinfo);
    
    int ret = snprintf(filename, max_len, "/spiffs/log_%04d%02d%02d_%02d%02d%02d.txt",
                       timeinfo.tm_year + 1900,
                       timeinfo.tm_mon + 1,
                       timeinfo.tm_mday,
                       timeinfo.tm_hour,
                       timeinfo.tm_min,
                       timeinfo.tm_sec);
    
    if (ret >= max_len) {
        return ESP_ERR_INVALID_SIZE;
    }
    
    return ESP_OK;
}

static esp_err_t check_rotate(FILE *f) {
    if (!f) {
        return ESP_FAIL;
    }
    
    long size = ftell(f);
    if (size < 0) {
        return ESP_FAIL;
    }
    
    if (size < LOG_MAX_SIZE) {
        return ESP_OK;
    }
    
    LOG_WITH_TS(I, "Log file size %ld exceeds limit %d, rotating", size, LOG_MAX_SIZE);
    
    fclose(f);
    
    // Generar nombre con timestamp
    char timestamped_file[64];
    esp_err_t ret = generate_timestamped_filename(timestamped_file, sizeof(timestamped_file));
    if (ret != ESP_OK) {
        LOG_WITH_TS(E, "Failed to generate timestamped filename, using legacy format");
        // Fallback a formato anterior
        if (access(PREV_LOG_FILE, F_OK) == 0) {
            LOG_WITH_TS(I, "Removing existing log.prev");
            unlink(PREV_LOG_FILE);
        }
        
        if (rename(LOG_FILE, PREV_LOG_FILE) != 0) {
            LOG_WITH_TS(E, "Failed to rename log.txt to log.prev");
            return ESP_FAIL;
        }
        LOG_WITH_TS(I, "Log rotated to legacy format: log.prev");
    } else {
        // Usar nombre con timestamp
        if (access(timestamped_file, F_OK) == 0) {
            LOG_WITH_TS(I, "Removing existing timestamped file: %s", timestamped_file);
            unlink(timestamped_file);
        }
        
        if (rename(LOG_FILE, timestamped_file) != 0) {
            LOG_WITH_TS(E, "Failed to rename log.txt to %s", timestamped_file);
            // Fallback a formato anterior
            if (rename(LOG_FILE, PREV_LOG_FILE) != 0) {
                LOG_WITH_TS(E, "Failed fallback rename to log.prev");
                return ESP_FAIL;
            }
            LOG_WITH_TS(I, "Log rotated to legacy format as fallback: log.prev");
        } else {
            LOG_WITH_TS(I, "Log rotated to timestamped file: %s", timestamped_file);
        }
    }
    
    FILE *nf = fopen(LOG_FILE, "w");
    if (nf) {
        fclose(nf);
        LOG_WITH_TS(I, "New log.txt created after rotation");
    } else {
        LOG_WITH_TS(E, "Failed to create new log.txt");
        return ESP_FAIL;
    }
    
    return ESP_OK;
}

esp_err_t log_storage_init(void) {
    if (s_log_initialized) {
        LOG_WITH_TS(W, "Log storage already initialized");
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
    
    size_t total = 0, used = 0;
    ret = esp_spiffs_info(conf.partition_label, &total, &used);
    if (ret == ESP_OK) {
        LOG_WITH_TS(I, "SPIFFS: %d KB total, %d KB used", total / 1024, used / 1024);
    } else {
        LOG_WITH_TS(W, "Failed to get SPIFFS info: %s", esp_err_to_name(ret));
    }
    
    s_log_initialized = true;
    LOG_WITH_TS(I, "Log storage initialized successfully");
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
    
    LOG_WITH_TS(I, "Log storage deinitialized");
}

int spiffs_vprintf(const char *fmt, va_list ap) {
    va_list ap_copy;
    va_copy(ap_copy, ap);
    
    int res = vprintf(fmt, ap);

    if (s_log_initialized && s_log_mutex && xSemaphoreTake(s_log_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        FILE *f = fopen(LOG_FILE, "a");
        if (f) {
            vfprintf(f, fmt, ap_copy);
            fflush(f);
            
            esp_err_t check_result = check_rotate(f);
            if (check_result != ESP_OK) {
                
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
        LOG_WITH_TS(E, "Log storage not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    LOG_WITH_TS(I, "Checking if log rotation is needed...");
    
    if (xSemaphoreTake(s_log_mutex, pdMS_TO_TICKS(5000)) != pdTRUE) {
        LOG_WITH_TS(E, "Failed to acquire mutex for rotation check");
        return ESP_ERR_TIMEOUT;
    }
    
    struct stat st;
    if (stat(LOG_FILE, &st) != 0) {
        LOG_WITH_TS(D, "No log.txt found, nothing to rotate");
        xSemaphoreGive(s_log_mutex);
        return ESP_ERR_NOT_FOUND;
    }
    
    LOG_WITH_TS(I, "log.txt found, size: %ld bytes", st.st_size);
    
    if (st.st_size > 0 && st.st_size >= (LOG_MAX_SIZE / 2)) {
        LOG_WITH_TS(I, "Log file size warrants rotation, proceeding...");
        
        // Generar nombre con timestamp
        char timestamped_file[64];
        esp_err_t ret = generate_timestamped_filename(timestamped_file, sizeof(timestamped_file));
        if (ret != ESP_OK) {
            LOG_WITH_TS(E, "Failed to generate timestamped filename, using legacy format");
            // Fallback a formato anterior
            if (access(PREV_LOG_FILE, F_OK) == 0) {
                LOG_WITH_TS(I, "Removing existing log.prev");
                unlink(PREV_LOG_FILE);
            }
            
            if (rename(LOG_FILE, PREV_LOG_FILE) == 0) {
                LOG_WITH_TS(I, "Log rotation completed successfully (legacy format)");
            } else {
                LOG_WITH_TS(E, "Failed to rotate log file");
                xSemaphoreGive(s_log_mutex);
                return ESP_FAIL;
            }
        } else {
            // Usar nombre con timestamp
            if (access(timestamped_file, F_OK) == 0) {
                LOG_WITH_TS(I, "Removing existing timestamped file: %s", timestamped_file);
                unlink(timestamped_file);
            }
            
            if (rename(LOG_FILE, timestamped_file) == 0) {
                LOG_WITH_TS(I, "Log rotation completed successfully to: %s", timestamped_file);
            } else {
                LOG_WITH_TS(E, "Failed to rotate to timestamped file, trying legacy");
                // Fallback a formato anterior
                if (rename(LOG_FILE, PREV_LOG_FILE) == 0) {
                    LOG_WITH_TS(I, "Log rotation completed successfully (legacy fallback)");
                } else {
                    LOG_WITH_TS(E, "Failed to rotate log file completely");
                    xSemaphoreGive(s_log_mutex);
                    return ESP_FAIL;
                }
            }
        }
        
        // Verificar que se creó correctamente
        if (stat(timestamped_file, &st) == 0 || stat(PREV_LOG_FILE, &st) == 0) {
            LOG_WITH_TS(I, "Rotated log file created successfully, size: %ld bytes", st.st_size);
        } else {
            LOG_WITH_TS(E, "Rotated log file was not created properly");
            xSemaphoreGive(s_log_mutex);
            return ESP_FAIL;
        }
        
        FILE *new_log = fopen(LOG_FILE, "w");
        if (new_log) {
            fclose(new_log);
            LOG_WITH_TS(I, "New log.txt created");
        } else {
            LOG_WITH_TS(W, "Failed to create new log.txt, will be created on next log write");
        }
        
        xSemaphoreGive(s_log_mutex);
        return ESP_OK;
    } else {
        LOG_WITH_TS(D, "Log file rotation not needed (size: %ld, threshold: %d)", 
                st.st_size, LOG_MAX_SIZE / 2);
        xSemaphoreGive(s_log_mutex);
        return ESP_ERR_NOT_FOUND;
    }
}

esp_err_t log_storage_force_rotate(void) {
    if (!s_log_initialized) {
        LOG_WITH_TS(E, "Log storage not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    LOG_WITH_TS(I, "Forcing log rotation...");
    
    if (xSemaphoreTake(s_log_mutex, pdMS_TO_TICKS(5000)) != pdTRUE) {
        LOG_WITH_TS(E, "Failed to acquire mutex for forced rotation");
        return ESP_ERR_TIMEOUT;
    }
    
    struct stat st;
    if (stat(LOG_FILE, &st) != 0) {
        LOG_WITH_TS(W, "No log.txt found for forced rotation");
        xSemaphoreGive(s_log_mutex);
        return ESP_ERR_NOT_FOUND;
    }
    
    if (st.st_size == 0) {
        LOG_WITH_TS(W, "log.txt is empty, skipping forced rotation");
        xSemaphoreGive(s_log_mutex);
        return ESP_ERR_INVALID_SIZE;
    }
    
    LOG_WITH_TS(I, "Forcing rotation of log.txt (%ld bytes)", st.st_size);
    
    // Generar nombre con timestamp para rotación forzada
    char timestamped_file[64];
    esp_err_t ret = generate_timestamped_filename(timestamped_file, sizeof(timestamped_file));
    if (ret != ESP_OK) {
        LOG_WITH_TS(E, "Failed to generate timestamped filename, using legacy format");
        // Fallback a formato anterior
        if (access(PREV_LOG_FILE, F_OK) == 0) {
            LOG_WITH_TS(I, "Removing existing log.prev for forced rotation");
            unlink(PREV_LOG_FILE);
        }
        
        if (rename(LOG_FILE, PREV_LOG_FILE) == 0) {
            LOG_WITH_TS(I, "Forced log rotation completed successfully (legacy format)");
        } else {
            LOG_WITH_TS(E, "Failed to perform forced rotation");
            xSemaphoreGive(s_log_mutex);
            return ESP_FAIL;
        }
    } else {
        // Usar nombre con timestamp
        if (access(timestamped_file, F_OK) == 0) {
            LOG_WITH_TS(I, "Removing existing timestamped file for forced rotation: %s", timestamped_file);
            unlink(timestamped_file);
        }
        
        if (rename(LOG_FILE, timestamped_file) == 0) {
            LOG_WITH_TS(I, "Forced log rotation completed successfully to: %s", timestamped_file);
        } else {
            LOG_WITH_TS(E, "Failed to rotate to timestamped file, trying legacy");
            // Fallback a formato anterior
            if (access(PREV_LOG_FILE, F_OK) == 0) {
                unlink(PREV_LOG_FILE);
            }
            if (rename(LOG_FILE, PREV_LOG_FILE) == 0) {
                LOG_WITH_TS(I, "Forced log rotation completed successfully (legacy fallback)");
            } else {
                LOG_WITH_TS(E, "Failed to perform forced rotation completely");
                xSemaphoreGive(s_log_mutex);
                return ESP_FAIL;
            }
        }
    }
    
    FILE *new_log = fopen(LOG_FILE, "w");
    if (new_log) {
        fclose(new_log);
        LOG_WITH_TS(I, "New log.txt created after forced rotation");
    }
    
    xSemaphoreGive(s_log_mutex);
    return ESP_OK;
}

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