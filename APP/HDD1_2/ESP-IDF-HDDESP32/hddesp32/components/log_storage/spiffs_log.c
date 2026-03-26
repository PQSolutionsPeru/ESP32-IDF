#include "spiffs_log.h"

#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>
#include <dirent.h>
#include <errno.h>
#include <string.h>
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
static int s_rotation_fail_count = 0;

#define MAX_ROTATION_FAILURES 5

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

static esp_err_t copy_and_delete(const char *src, const char *dst) {
    if (access(dst, F_OK) == 0) {
        unlink(dst);
    }
    FILE *fsrc = fopen(src, "r");
    if (!fsrc) {
        return ESP_FAIL;
    }
    FILE *fdst = fopen(dst, "w");
    if (!fdst) {
        fclose(fsrc);
        return ESP_FAIL;
    }
    char buf[512];
    size_t bytes_read;
    bool ok = true;
    while ((bytes_read = fread(buf, 1, sizeof(buf), fsrc)) > 0) {
        if (fwrite(buf, 1, bytes_read, fdst) != bytes_read) {
            ok = false;
            break;
        }
    }
    fclose(fsrc);
    fclose(fdst);
    if (!ok) {
        unlink(dst);
        return ESP_FAIL;
    }
    unlink(src);
    return ESP_OK;
}

static void do_rotation_no_mutex(void) {
    char timestamped_file[64];
    esp_err_t rot_ret = ESP_FAIL;
    if (generate_timestamped_filename(timestamped_file, sizeof(timestamped_file)) == ESP_OK) {
        rot_ret = copy_and_delete(LOG_FILE, timestamped_file);
        if (rot_ret == ESP_OK) {
            LOG_WITH_TS(I, "Log rotated to: %s", timestamped_file);
        }
    }
    if (rot_ret != ESP_OK) {
        rot_ret = copy_and_delete(LOG_FILE, PREV_LOG_FILE);
        if (rot_ret == ESP_OK) {
            LOG_WITH_TS(I, "Log rotated to legacy format");
        }
    }
    if (rot_ret == ESP_OK) {
        FILE *nf = fopen(LOG_FILE, "w");
        if (nf) {
            fclose(nf);
        }
    } else {
        LOG_WITH_TS(E, "Size-based rotation failed");
    }
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
            long size = ftell(f);
            fclose(f);

            if (size >= LOG_MAX_SIZE) {
                do_rotation_no_mutex();
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

        char timestamped_file[64];
        esp_err_t rot_ret = ESP_FAIL;
        if (generate_timestamped_filename(timestamped_file, sizeof(timestamped_file)) == ESP_OK) {
            rot_ret = copy_and_delete(LOG_FILE, timestamped_file);
            if (rot_ret == ESP_OK) {
                LOG_WITH_TS(I, "Log rotation completed to: %s", timestamped_file);
            }
        }
        if (rot_ret != ESP_OK) {
            rot_ret = copy_and_delete(LOG_FILE, PREV_LOG_FILE);
            if (rot_ret == ESP_OK) {
                LOG_WITH_TS(I, "Log rotation completed to legacy format");
            }
        }
        if (rot_ret != ESP_OK) {
            LOG_WITH_TS(E, "Failed to rotate log file");
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

// Helper function to clean old log files if space is insufficient
static esp_err_t cleanup_old_logs(size_t needed_space) {
    DIR *dir = opendir("/spiffs");
    if (!dir) {
        LOG_WITH_TS(E, "Failed to open /spiffs directory for cleanup (errno=%d)", errno);
        return ESP_FAIL;
    }

    struct dirent *entry;
    char oldest_file[64] = {0};
    time_t oldest_time = 0;
    size_t total_freed = 0;

    // Find oldest timestamped log file
    while ((entry = readdir(dir)) != NULL) {
        // Look for timestamped log files: log_YYYYMMDD_HHMMSS.txt
        if (strncmp(entry->d_name, "log_", 4) == 0 && strstr(entry->d_name, ".txt")) {
            // Check filename length before processing
            size_t name_len = strnlen(entry->d_name, 256);
            if (name_len > 54) {
                LOG_WITH_TS(W, "Skipping file with too long name: %.20s...", entry->d_name);
                continue; // "/spiffs/" (8) + name (54) + null (1) = 63 max
            }

            char filepath[64];
            int written = snprintf(filepath, sizeof(filepath), "/spiffs/%s", entry->d_name);
            if (written < 0 || written >= sizeof(filepath)) {
                LOG_WITH_TS(E, "Path truncated for file: %s", entry->d_name);
                continue;
            }

            struct stat st;
            if (stat(filepath, &st) == 0) {
                // Track oldest file by modification time
                if (oldest_time == 0 || st.st_mtime < oldest_time) {
                    oldest_time = st.st_mtime;
                    strncpy(oldest_file, filepath, sizeof(oldest_file) - 1);
                }
            }
        }
    }
    closedir(dir);

    // Delete oldest file if found
    if (oldest_file[0] != '\0') {
        struct stat st;
        if (stat(oldest_file, &st) == 0) {
            size_t file_size = st.st_size;
            if (unlink(oldest_file) == 0) {
                LOG_WITH_TS(I, "Deleted old log file: %s (%zu bytes freed)", oldest_file, file_size);
                total_freed = file_size;
            } else {
                LOG_WITH_TS(E, "Failed to delete old log file: %s (errno=%d)", oldest_file, errno);
            }
        }
    } else {
        LOG_WITH_TS(W, "No old timestamped log files found to cleanup");
    }

    return (total_freed >= needed_space) ? ESP_OK : ESP_FAIL;
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

    size_t log_file_size = st.st_size;
    LOG_WITH_TS(I, "Forcing rotation of log.txt (%zu bytes)", log_file_size);

    // Check available space BEFORE attempting rotation
    size_t total = 0, used = 0;
    esp_err_t space_check = esp_spiffs_info(NULL, &total, &used);
    if (space_check == ESP_OK) {
        size_t available = total - used;
        // Need at least 1.2x the log file size for safe rotation (file + metadata)
        size_t needed = (log_file_size * 12) / 10;

        LOG_WITH_TS(I, "SPIFFS space check: %zu KB total, %zu KB used, %zu KB available, %zu KB needed",
                    total/1024, used/1024, available/1024, needed/1024);

        if (available < needed) {
            LOG_WITH_TS(W, "Insufficient space for rotation (%zu < %zu bytes), attempting cleanup",
                        available, needed);

            esp_err_t cleanup_result = cleanup_old_logs(needed - available);
            if (cleanup_result != ESP_OK) {
                LOG_WITH_TS(E, "Cleanup failed, rotation aborted to prevent corruption");
                xSemaphoreGive(s_log_mutex);
                return ESP_ERR_NO_MEM;
            }

            // Re-check space after cleanup
            space_check = esp_spiffs_info(NULL, &total, &used);
            available = total - used;
            LOG_WITH_TS(I, "After cleanup: %zu KB available", available/1024);

            if (available < needed) {
                LOG_WITH_TS(E, "Still insufficient space after cleanup (%zu < %zu), aborting rotation",
                            available, needed);
                xSemaphoreGive(s_log_mutex);
                return ESP_ERR_NO_MEM;
            }
        }
    } else {
        LOG_WITH_TS(W, "Could not check SPIFFS space: %s, proceeding with caution",
                    esp_err_to_name(space_check));
    }

    char timestamped_file[64];
    esp_err_t rot_ret = ESP_FAIL;
    if (generate_timestamped_filename(timestamped_file, sizeof(timestamped_file)) == ESP_OK) {
        rot_ret = copy_and_delete(LOG_FILE, timestamped_file);
        if (rot_ret == ESP_OK) {
            LOG_WITH_TS(I, "Forced log rotation completed to: %s", timestamped_file);
        }
    }
    if (rot_ret != ESP_OK) {
        rot_ret = copy_and_delete(LOG_FILE, PREV_LOG_FILE);
        if (rot_ret == ESP_OK) {
            LOG_WITH_TS(I, "Forced log rotation completed to legacy format");
        }
    }
    if (rot_ret != ESP_OK) {
        s_rotation_fail_count++;
        LOG_WITH_TS(E, "Forced rotation failed (attempt %d/%d)", s_rotation_fail_count, MAX_ROTATION_FAILURES);
        xSemaphoreGive(s_log_mutex);
        if (s_rotation_fail_count >= MAX_ROTATION_FAILURES) {
            LOG_WITH_TS(E, "Max rotation failures reached, reformatting SPIFFS for self-recovery");
            if (s_log_mutex) {
                vSemaphoreDelete(s_log_mutex);
                s_log_mutex = NULL;
            }
            esp_vfs_spiffs_unregister(NULL);
            esp_spiffs_format(NULL);
            s_log_initialized = false;
            s_rotation_fail_count = 0;
            log_storage_init();
        }
        return ESP_FAIL;
    }
    s_rotation_fail_count = 0;

    FILE *new_log = fopen(LOG_FILE, "w");
    if (new_log) {
        fclose(new_log);
        LOG_WITH_TS(I, "New log.txt created after forced rotation");
    } else {
        LOG_WITH_TS(E, "Failed to create new log.txt after rotation (errno=%d)", errno);
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