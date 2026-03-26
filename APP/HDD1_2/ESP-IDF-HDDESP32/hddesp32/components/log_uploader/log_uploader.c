#include "log_uploader.h"

#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <time.h>
#include <dirent.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "spiffs_log.h"
#include "esp32_id_manager.h"
#include "wifi_manager.h"
#include "mqtt_manager.h"
#include "connectivity_monitor.h"
#include "time_manager.h"

#ifndef CONFIG_LOG_UPLOAD_URL
#define CONFIG_LOG_UPLOAD_URL "http://34.63.146.196:8080/upload"
#endif

#define UPLOAD_INTERVAL_HOURS 24
#define SYSTEM_STABILITY_CHECK_MS 300000
#define CONNECTIVITY_TIMEOUT_MS 5000
#define MAX_UPLOAD_RETRIES 3
#define MIN_LOG_SIZE_FOR_ROTATION 10240

static const char *TAG = "LOG_UPLOADER";
static char s_esp32_id[ESP32_ID_LENGTH + 1];
static bool s_system_stable = false;
static bool s_uploader_running = false;
static bool s_post_restart_check_done = false;
static int64_t s_last_stability_check = 0;

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

static bool check_full_connectivity(void);
static void check_post_restart_rotation(void);
static bool check_system_stability(void);
static esp_err_t send_file_with_verification(const char *path);
static esp_err_t send_file_with_timestamp(const char *path);
static int64_t calculate_ms_until_midnight_lima(void);
static void force_daily_rotation_and_upload(void);
static void upload_pending_logs(void);

static bool check_full_connectivity(void) {
    if (!wifi_manager_is_connected()) {
        ESP_LOGD(TAG, "WiFi not connected");
        return false;
    }
    
    if (!mqtt_manager_is_connected()) {
        ESP_LOGD(TAG, "MQTT not connected");
        return false;
    }
    
    bool has_internet = false;
    esp_err_t ping_ret = connectivity_monitor_check_internet_connectivity(&has_internet);
    if (ping_ret != ESP_OK || !has_internet) {
        ESP_LOGD(TAG, "Internet connectivity check failed");
        return false;
    }
    
    LOG_WITH_TS(I, "Full connectivity confirmed (WiFi + MQTT + Internet)");
    return true;
}

static void check_post_restart_rotation(void) {
    if (s_post_restart_check_done) {
        return;
    }
    
    LOG_WITH_TS(I, "Checking for post-restart log rotation...");
    
    struct stat st;
    if (stat("/spiffs/log.txt", &st) != 0) {
        LOG_WITH_TS(W, "No log.txt found for post-restart rotation");
        s_post_restart_check_done = true;
        return;
    }
    
    if (st.st_size >= MIN_LOG_SIZE_FOR_ROTATION) {
        LOG_WITH_TS(I, "Post-restart: log.txt has %ld bytes, rotating to preserve previous session", st.st_size);
        
        esp_err_t rotate_ret = log_storage_force_rotate();
        if (rotate_ret == ESP_OK) {
            LOG_WITH_TS(I, "Post-restart log rotation completed successfully");
        } else {
            LOG_WITH_TS(W, "Post-restart log rotation failed: %s", esp_err_to_name(rotate_ret));
        }
    } else {
        LOG_WITH_TS(I, "Post-restart: log.txt size (%ld bytes) below rotation threshold (%d bytes)", 
                st.st_size, MIN_LOG_SIZE_FOR_ROTATION);
    }
    
    s_post_restart_check_done = true;
}

static bool check_system_stability(void) {
    if (s_system_stable) {
        return true;
    }
    
    int64_t current_time = esp_timer_get_time() / 1000;
    
    if (!check_full_connectivity()) {
        s_last_stability_check = current_time;
        return false;
    }
    
    if (s_last_stability_check == 0) {
        s_last_stability_check = current_time;
        LOG_WITH_TS(I, "System connectivity established, starting stability timer");
        return false;
    }
    
    int64_t stability_time = current_time - s_last_stability_check;
    if (stability_time >= SYSTEM_STABILITY_CHECK_MS) {
        if (check_full_connectivity()) {
            s_system_stable = true;
            LOG_WITH_TS(I, "System confirmed stable after %lld ms", stability_time);
            
            check_post_restart_rotation();
            
            return true;
        } else {
            s_last_stability_check = current_time;
            LOG_WITH_TS(W, "Lost connectivity during stability check, restarting timer");
            return false;
        }
    }
    
    int64_t remaining = (SYSTEM_STABILITY_CHECK_MS - stability_time) / 1000;
    ESP_LOGD(TAG, "System stability check: %lld seconds remaining", remaining);
    return false;
}

static esp_err_t send_file_with_verification(const char *path) {
    LOG_WITH_TS(I, "Attempting to send file: %s", path);
    
    if (!check_full_connectivity()) {
        LOG_WITH_TS(W, "No full connectivity available for upload");
        return ESP_ERR_INVALID_STATE;
    }
    
    struct stat st;
    if (stat(path, &st) != 0) {
        LOG_WITH_TS(W, "File %s not found", path);
        return ESP_ERR_NOT_FOUND;
    }
    
    if (st.st_size == 0) {
        LOG_WITH_TS(W, "File %s is empty, deleting", path);
        unlink(path);
        return ESP_ERR_INVALID_SIZE;
    }
    
    if (st.st_size > 350 * 1024) {
        LOG_WITH_TS(W, "File %s too large (%ld bytes), deleting", path, st.st_size);
        unlink(path);
        return ESP_ERR_INVALID_SIZE;
    }
    
    LOG_WITH_TS(I, "File %s found, size: %ld bytes - proceeding with upload", path, st.st_size);
    
    size_t free_before = esp_get_free_heap_size();
    LOG_WITH_TS(I, "Free heap before upload: %zu bytes", free_before);
    
    const char *filename = strrchr(path, '/');
    filename = filename ? filename + 1 : path;

    LOG_WITH_TS(I, "Preparing streaming HTTP upload for file: %s", filename);

    const char *boundary = "----ESP32LogBoundary";
    char header[64];
    snprintf(header, sizeof(header), "multipart/form-data; boundary=%s", boundary);

    char pre_body[512];
    int pre_len = snprintf(pre_body, sizeof(pre_body),
        "--%s\r\n"
        "Content-Disposition: form-data; name=\"esp32_id\"\r\n\r\n%s\r\n"
        "--%s\r\n"
        "Content-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
        "Content-Type: text/plain\r\n\r\n",
        boundary, s_esp32_id, boundary, filename);
    
    char post_body[64];
    int post_len = snprintf(post_body, sizeof(post_body), "\r\n--%s--\r\n", boundary);
    int total_len = pre_len + st.st_size + post_len;

    LOG_WITH_TS(I, "HTTP body size: %d bytes (streaming mode)", total_len);

    if (!check_full_connectivity()) {
        LOG_WITH_TS(W, "Lost connectivity before HTTP request");
        return ESP_ERR_INVALID_STATE;
    }

    LOG_WITH_TS(I, "Sending streaming HTTP POST to: %s", CONFIG_LOG_UPLOAD_URL);
    
    esp_http_client_config_t cfg = {
        .url = CONFIG_LOG_UPLOAD_URL,
        .timeout_ms = CONNECTIVITY_TIMEOUT_MS,
        .buffer_size = 4096,
        .buffer_size_tx = 4096
    };
    
    esp_http_client_handle_t client = esp_http_client_init(&cfg);
    if (!client) {
        LOG_WITH_TS(E, "Failed to initialize HTTP client");
        return ESP_FAIL;
    }
    
    esp_http_client_set_method(client, HTTP_METHOD_POST);
    esp_http_client_set_header(client, "Content-Type", header);
    esp_http_client_set_header(client, "Content-Length", "0");
    
    LOG_WITH_TS(I, "Opening HTTP connection...");
    esp_err_t err = esp_http_client_open(client, total_len);
    if (err != ESP_OK) {
        LOG_WITH_TS(E, "Failed to open HTTP connection: %s", esp_err_to_name(err));
        esp_http_client_cleanup(client);
        return err;
    }
    
    LOG_WITH_TS(I, "Sending multipart headers...");
    int written = esp_http_client_write(client, pre_body, pre_len);
    if (written != pre_len) {
        LOG_WITH_TS(E, "Failed to write headers: %d/%d", written, pre_len);
        esp_http_client_cleanup(client);
        return ESP_FAIL;
    }
    
    FILE *f = fopen(path, "r");
    if (!f) {
        LOG_WITH_TS(E, "Failed to open file for reading");
        esp_http_client_cleanup(client);
        return ESP_FAIL;
    }
    
    LOG_WITH_TS(I, "Streaming file content...");
    char buffer[1024];
    size_t total_written = 0;
    
    while (!feof(f)) {
        size_t bytes_read = fread(buffer, 1, sizeof(buffer), f);
        if (bytes_read > 0) {
            int chunk_written = esp_http_client_write(client, buffer, bytes_read);
            if (chunk_written != bytes_read) {
                LOG_WITH_TS(E, "Failed to write file chunk: %d/%zu", chunk_written, bytes_read);
                fclose(f);
                esp_http_client_cleanup(client);
                return ESP_FAIL;
            }
            total_written += bytes_read;
        }
    }
    fclose(f);
    
    LOG_WITH_TS(I, "Sending multipart footer...");
    written = esp_http_client_write(client, post_body, post_len);
    if (written != post_len) {
        LOG_WITH_TS(E, "Failed to write footer: %d/%d", written, post_len);
        esp_http_client_cleanup(client);
        return ESP_FAIL;
    }
    
    LOG_WITH_TS(I, "Fetching response...");
    int content_length = esp_http_client_fetch_headers(client);
    int status_code = esp_http_client_get_status_code(client);
    
    LOG_WITH_TS(I, "HTTP response - Status: %d, Content-Length: %d", status_code, content_length);
    
    if (status_code == 200) {
        LOG_WITH_TS(I, "Upload successful! Deleting local file: %s", path);
        if (unlink(path) == 0) {
            LOG_WITH_TS(I, "Local file deleted successfully");
        } else {
            LOG_WITH_TS(W, "Failed to delete local file after successful upload");
        }
        err = ESP_OK;
    } else {
        LOG_WITH_TS(W, "Upload failed with HTTP status: %d", status_code);
        err = ESP_FAIL;
    }
    
    esp_http_client_cleanup(client);
    
    size_t free_after = esp_get_free_heap_size();
    LOG_WITH_TS(I, "Free heap after upload: %zu bytes (delta: %+ld)", 
             free_after, (long)(free_after - free_before));
    
    return err;
}

static esp_err_t send_file_with_timestamp(const char *path) {
    return send_file_with_verification(path);
}

// Función corregida para calcular tiempo hasta medianoche Lima (GMT-5)
static int64_t calculate_ms_until_midnight_lima(void) {
    time_t now;
    struct tm timeinfo;
    
    // Usar tiempo sincronizado si está disponible
    if (time_manager_is_synchronized()) {
        now = time_manager_get_time();
    } else {
        time(&now);
    }
    
    // Convertir a tiempo Lima (GMT-5)
    now -= 5 * 3600;  // Restar 5 horas para GMT-5
    gmtime_r(&now, &timeinfo);
    
    // Calcular segundos desde medianoche
    int seconds_since_midnight = timeinfo.tm_hour * 3600 + timeinfo.tm_min * 60 + timeinfo.tm_sec;
    int seconds_until_midnight = 86400 - seconds_since_midnight;
    
    // Asegurar que no sea negativo
    if (seconds_until_midnight <= 0) {
        seconds_until_midnight = 86400;  // 24 horas
    }
    
    // Mostrar hora actual Lima
    LOG_WITH_TS(I, "Current Lima time: %02d:%02d:%02d, seconds until midnight: %d",
             timeinfo.tm_hour, timeinfo.tm_min, timeinfo.tm_sec, seconds_until_midnight);
    
    return (int64_t)seconds_until_midnight * 1000;
}

static void force_daily_rotation_and_upload(void) {
    LOG_WITH_TS(I, "=== FORCING DAILY LOG ROTATION AND UPLOAD (MIDNIGHT LIMA) ===");
    
    struct stat st;
    if (stat("/spiffs/log.txt", &st) == 0 && st.st_size > 0) {
        LOG_WITH_TS(I, "Current log.txt exists (%ld bytes), forcing rotation", st.st_size);
        
        esp_err_t rotate_ret = log_storage_force_rotate();
        if (rotate_ret == ESP_OK) {
            LOG_WITH_TS(I, "Daily log rotation successful");
        } else {
            LOG_WITH_TS(W, "Daily log rotation failed: %s", esp_err_to_name(rotate_ret));
        }
    } else {
        LOG_WITH_TS(I, "No current log to rotate");
    }
    
    upload_pending_logs();
    
    LOG_WITH_TS(I, "=== DAILY ROTATION AND UPLOAD COMPLETED ===");
}

static void upload_pending_logs(void) {
    LOG_WITH_TS(I, "=== STARTING LOG UPLOAD CHECK ===");
    
    if (!check_system_stability()) {
        ESP_LOGD(TAG, "System not stable yet, skipping upload");
        return;
    }
    
    struct stat st;
    char log_files[10][64];
    int file_count = 0;

    DIR *spiffs_dir = opendir("/spiffs");
    if (spiffs_dir) {
        struct dirent *entry;
        while ((entry = readdir(spiffs_dir)) != NULL && file_count < 10) {
            if (strcmp(entry->d_name, "log.txt") == 0) {
                continue;
            }
            bool is_timestamped = (strncmp(entry->d_name, "log_", 4) == 0 &&
                                   strstr(entry->d_name, ".txt") != NULL);
            bool is_prev = (strcmp(entry->d_name, "log.prev") == 0);
            if (!is_timestamped && !is_prev) {
                continue;
            }
            char filepath[64];
            snprintf(filepath, sizeof(filepath), "/spiffs/%s", entry->d_name);
            if (stat(filepath, &st) == 0 && st.st_size > 0) {
                strncpy(log_files[file_count], filepath, sizeof(log_files[0]) - 1);
                log_files[file_count][sizeof(log_files[0]) - 1] = '\0';
                file_count++;
                LOG_WITH_TS(I, "Found pending log for upload: %s (%ld bytes)", filepath, st.st_size);
            }
        }
        closedir(spiffs_dir);
    } else {
        LOG_WITH_TS(W, "Failed to open /spiffs directory for scan");
    }

    if (file_count == 0) {
        LOG_WITH_TS(I, "No timestamped log files found - checking current log");
        
        if (stat("/spiffs/log.txt", &st) == 0) {
            LOG_WITH_TS(I, "Current log.txt size: %ld bytes", st.st_size);
            
            // Siempre rotar si hay contenido, sin límite mínimo
            if (st.st_size > 0) {
                LOG_WITH_TS(I, "Found current log with content, forcing rotation for upload");
                esp_err_t rotate_ret = log_storage_force_rotate();
                if (rotate_ret == ESP_OK) {
                    LOG_WITH_TS(I, "Log rotation successful, rechecking for files");
                    // Después de rotar, verificar archivos nuevamente
                    if (stat("/spiffs/log.prev", &st) == 0) {
                        strcpy(log_files[file_count], "/spiffs/log.prev");
                        file_count++;
                    }
                } else {
                    LOG_WITH_TS(W, "Log rotation failed: %s", esp_err_to_name(rotate_ret));
                    return;
                }
            } else {
                LOG_WITH_TS(I, "Current log is empty - no upload needed");
                return;
            }
        } else {
            LOG_WITH_TS(I, "No log files found at all");
            return;
        }
    }
    
    if (file_count == 0) {
        LOG_WITH_TS(W, "No log files available for upload after all checks");
        return;
    }
    
    LOG_WITH_TS(I, "=== STARTING HTTP UPLOAD PROCESS ===");
    LOG_WITH_TS(I, "Found %d log file(s) for upload", file_count);
    
    // Subir todos los archivos encontrados
    int successful_uploads = 0;
    for (int i = 0; i < file_count; i++) {
        LOG_WITH_TS(I, "Processing file %d/%d: %s", i+1, file_count, log_files[i]);
        
        int retry_count = 0;
        bool upload_success = false;
        
        while (retry_count < MAX_UPLOAD_RETRIES && !upload_success) {
            LOG_WITH_TS(I, "Upload attempt %d/%d for %s", retry_count + 1, MAX_UPLOAD_RETRIES, log_files[i]);
            
            if (!check_full_connectivity()) {
                LOG_WITH_TS(W, "Lost connectivity, aborting upload attempts");
                break;
            }
            
            esp_err_t err = send_file_with_timestamp(log_files[i]);
            if (err == ESP_OK) {
                LOG_WITH_TS(I, "Upload completed successfully for %s on attempt %d", log_files[i], retry_count + 1);
                upload_success = true;
                successful_uploads++;
            } else if (err == ESP_ERR_NOT_FOUND) {
                LOG_WITH_TS(I, "File %s was deleted during upload process", log_files[i]);
                upload_success = true;  // Considerar como éxito
                successful_uploads++;
            } else if (err == ESP_ERR_INVALID_STATE) {
                LOG_WITH_TS(W, "No connectivity available, will retry later");
                break;
            } else {
                retry_count++;
                LOG_WITH_TS(W, "Upload failed for %s on attempt %d: %s", log_files[i], retry_count, esp_err_to_name(err));
                
                if (retry_count < MAX_UPLOAD_RETRIES) {
                    LOG_WITH_TS(I, "Retrying upload in 30 seconds...");
                    vTaskDelay(pdMS_TO_TICKS(30000));
                }
            }
        }
        
        if (!upload_success) {
            LOG_WITH_TS(E, "Failed to upload %s after %d attempts", log_files[i], MAX_UPLOAD_RETRIES);
        }
    }
    
    LOG_WITH_TS(I, "=== UPLOAD PROCESS COMPLETED ===");
    LOG_WITH_TS(I, "Successfully uploaded %d/%d files", successful_uploads, file_count);
}

static void uploader_task(void *arg) {
    LOG_WITH_TS(I, "Log uploader task started - waiting for internet connectivity");
    s_uploader_running = true;
    
    bool initial_upload_done = false;
    
    while (s_uploader_running) {
        if (check_system_stability()) {
            if (!initial_upload_done) {
                LOG_WITH_TS(I, "System is stable, checking for post-restart uploads");
                upload_pending_logs();
                initial_upload_done = true;
                
                // Calcular tiempo hasta la PRÓXIMA medianoche Lima
                int64_t ms_until_midnight = calculate_ms_until_midnight_lima();
                LOG_WITH_TS(I, "Post-restart upload complete, next scheduled at midnight Lima (GMT-5)");
                vTaskDelay(pdMS_TO_TICKS(ms_until_midnight));
            } else {
                // Rotación diaria exacta a medianoche Lima
                char current_time[32];
                get_log_timestamp(current_time, sizeof(current_time));
                LOG_WITH_TS(I, "Daily upload triggered at midnight Lima");
                
                force_daily_rotation_and_upload();
                
                // Esperar hasta la siguiente medianoche
                int64_t ms_until_next_midnight = calculate_ms_until_midnight_lima();
                vTaskDelay(pdMS_TO_TICKS(ms_until_next_midnight));
            }
        } else {
            if (check_full_connectivity()) {
                int64_t current_time = esp_timer_get_time() / 1000;
                if (s_last_stability_check > 0) {
                    int64_t elapsed = current_time - s_last_stability_check;
                    int64_t remaining = (SYSTEM_STABILITY_CHECK_MS - elapsed) / 1000;
                    LOG_WITH_TS(I, "Internet connected, waiting %lld seconds for stability", remaining);
                } else {
                    LOG_WITH_TS(I, "Internet connected, starting 5-minute stability timer");
                }
            } else {
                LOG_WITH_TS(I, "Waiting for internet connectivity, rechecking in 2 minutes");
            }
            vTaskDelay(pdMS_TO_TICKS(120000));
        }
    }
    
    LOG_WITH_TS(I, "Log uploader task ended");
    vTaskDelete(NULL);
}

void log_uploader_start(const char *esp32_id) {
    if (s_uploader_running) {
        LOG_WITH_TS(W, "Log uploader already running");
        return;
    }
    
    if (esp32_id) {
        strlcpy(s_esp32_id, esp32_id, sizeof(s_esp32_id));
        LOG_WITH_TS(I, "Log uploader initialized with ESP32 ID: %s", s_esp32_id);
    } else {
        s_esp32_id[0] = '\0';
        LOG_WITH_TS(W, "Log uploader started without ESP32 ID");
    }
    
    s_system_stable = false;
    s_last_stability_check = 0;
    s_post_restart_check_done = false;
    
    BaseType_t result = xTaskCreate(uploader_task, "log_uploader", 8192, NULL, 3, NULL);
    if (result == pdPASS) {
        LOG_WITH_TS(I, "Log uploader task created successfully");
    } else {
        LOG_WITH_TS(E, "Failed to create log uploader task");
        s_uploader_running = false;
    }
}

void log_uploader_stop(void) {
    if (s_uploader_running) {
        LOG_WITH_TS(I, "Stopping log uploader...");
        s_uploader_running = false;
    }
}

bool log_uploader_is_system_stable(void) {
    return s_system_stable;
}

void log_uploader_reset_stability(void) {
    LOG_WITH_TS(I, "Resetting system stability state");
    s_system_stable = false;
    s_last_stability_check = 0;
    s_post_restart_check_done = false;
}