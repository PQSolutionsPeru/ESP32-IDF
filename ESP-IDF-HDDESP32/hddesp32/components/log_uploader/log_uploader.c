#include "log_uploader.h"

#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
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

#ifndef CONFIG_LOG_UPLOAD_URL
#define CONFIG_LOG_UPLOAD_URL "http://34.63.146.196:8080/upload"
#endif

#define UPLOAD_INTERVAL_HOURS 24
#define SYSTEM_STABILITY_CHECK_MS 300000  // 5 minutos de estabilidad requerida
#define CONNECTIVITY_TIMEOUT_MS 5000      // 5 segundos timeout HTTP
#define MAX_UPLOAD_RETRIES 3

static const char *TAG = "LOG_UPLOADER";
static char s_esp32_id[ESP32_ID_LENGTH + 1];
static bool s_system_stable = false;
static bool s_uploader_running = false;
static int64_t s_last_stability_check = 0;

// Verificar conectividad completa antes de uploads
static bool check_full_connectivity(void) {
    // Verificar WiFi
    if (!wifi_manager_is_connected()) {
        ESP_LOGD(TAG, "WiFi not connected");
        return false;
    }
    
    // Verificar MQTT
    if (!mqtt_manager_is_connected()) {
        ESP_LOGD(TAG, "MQTT not connected");
        return false;
    }
    
    // Verificar Internet con ping rápido
    bool has_internet = false;
    esp_err_t ping_ret = connectivity_monitor_check_internet_connectivity(&has_internet);
    if (ping_ret != ESP_OK || !has_internet) {
        ESP_LOGD(TAG, "Internet connectivity check failed");
        return false;
    }
    
    ESP_LOGI(TAG, "Full connectivity confirmed (WiFi + MQTT + Internet)");
    return true;
}

// Verificar estabilidad del sistema
static bool check_system_stability(void) {
    if (s_system_stable) {
        return true; // Ya verificado anteriormente
    }
    
    int64_t current_time = esp_timer_get_time() / 1000;
    
    // Primera verificación de conectividad
    if (!check_full_connectivity()) {
        s_last_stability_check = current_time;
        return false;
    }
    
    // Si es la primera vez que tenemos conectividad completa
    if (s_last_stability_check == 0) {
        s_last_stability_check = current_time;
        ESP_LOGI(TAG, "System connectivity established, starting stability timer");
        return false;
    }
    
    // Verificar si ha pasado suficiente tiempo de estabilidad
    int64_t stability_time = current_time - s_last_stability_check;
    if (stability_time >= SYSTEM_STABILITY_CHECK_MS) {
        // Verificar que aún tenemos conectividad
        if (check_full_connectivity()) {
            s_system_stable = true;
            ESP_LOGI(TAG, "System confirmed stable after %lld ms", stability_time);
            return true;
        } else {
            // Perdimos conectividad, reiniciar timer
            s_last_stability_check = current_time;
            ESP_LOGW(TAG, "Lost connectivity during stability check, restarting timer");
            return false;
        }
    }
    
    int64_t remaining = (SYSTEM_STABILITY_CHECK_MS - stability_time) / 1000;
    ESP_LOGD(TAG, "System stability check: %lld seconds remaining", remaining);
    return false;
}

static esp_err_t send_file_with_verification(const char *path) {
    ESP_LOGI(TAG, "Attempting to send file: %s", path);
    
    // Verificación triple de conectividad antes de enviar
    if (!check_full_connectivity()) {
        ESP_LOGW(TAG, "No full connectivity available for upload");
        return ESP_ERR_INVALID_STATE;
    }
    
    struct stat st;
    if (stat(path, &st) != 0) {
        ESP_LOGW(TAG, "File %s not found", path);
        return ESP_ERR_NOT_FOUND;
    }
    
    if (st.st_size == 0) {
        ESP_LOGW(TAG, "File %s is empty, deleting", path);
        unlink(path);
        return ESP_ERR_INVALID_SIZE;
    }
    
    if (st.st_size > 1024 * 1024) { // 1MB limit
        ESP_LOGW(TAG, "File %s too large (%ld bytes), deleting", path, st.st_size);
        unlink(path);
        return ESP_ERR_INVALID_SIZE;
    }
    
    ESP_LOGI(TAG, "File %s found, size: %ld bytes", path, st.st_size);
    
    FILE *f = fopen(path, "r");
    if (!f) {
        ESP_LOGE(TAG, "Failed to open file %s for reading", path);
        return ESP_FAIL;
    }

    const char *filename = strrchr(path, '/');
    filename = filename ? filename + 1 : path;

    ESP_LOGI(TAG, "Preparing HTTP upload for file: %s", filename);

    const char *boundary = "----ESP32LogBoundary";
    char header[64];
    snprintf(header, sizeof(header), "multipart/form-data; boundary=%s", boundary);

    int pre_len = snprintf(NULL, 0,
        "--%s\r\n"
        "Content-Disposition: form-data; name=\"esp32_id\"\r\n\r\n%s\r\n"
        "--%s\r\n"
        "Content-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
        "Content-Type: text/plain\r\n\r\n",
        boundary, s_esp32_id, boundary, filename);
    int post_len = snprintf(NULL, 0, "\r\n--%s--\r\n", boundary);
    int total_len = pre_len + st.st_size + post_len;

    ESP_LOGI(TAG, "HTTP body size: %d bytes (pre: %d, file: %ld, post: %d)", 
             total_len, pre_len, st.st_size, post_len);

    char *body = malloc(total_len);
    if (!body) {
        ESP_LOGE(TAG, "Failed to allocate %d bytes for HTTP body", total_len);
        fclose(f);
        return ESP_ERR_NO_MEM;
    }
    
    char *p = body;
    p += sprintf(p,
        "--%s\r\n"
        "Content-Disposition: form-data; name=\"esp32_id\"\r\n\r\n%s\r\n"
        "--%s\r\n"
        "Content-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
        "Content-Type: text/plain\r\n\r\n",
        boundary, s_esp32_id, boundary, filename);
    
    size_t read_bytes = fread(p, 1, st.st_size, f);
    fclose(f);
    
    if (read_bytes != st.st_size) {
        ESP_LOGE(TAG, "File read error: expected %ld bytes, got %zu bytes", st.st_size, read_bytes);
        free(body);
        return ESP_FAIL;
    }
    
    p += st.st_size;
    p += sprintf(p, "\r\n--%s--\r\n", boundary);

    // Verificar conectividad una vez más antes del HTTP request
    if (!check_full_connectivity()) {
        ESP_LOGW(TAG, "Lost connectivity before HTTP request");
        free(body);
        return ESP_ERR_INVALID_STATE;
    }

    ESP_LOGI(TAG, "Initializing HTTP client for URL: %s", CONFIG_LOG_UPLOAD_URL);
    
    esp_http_client_config_t cfg = {
        .url = CONFIG_LOG_UPLOAD_URL,
        .timeout_ms = CONNECTIVITY_TIMEOUT_MS, // 5 segundos timeout
        .buffer_size = 2048,
        .buffer_size_tx = 2048
    };
    
    esp_http_client_handle_t client = esp_http_client_init(&cfg);
    if (!client) {
        ESP_LOGE(TAG, "Failed to initialize HTTP client");
        free(body);
        return ESP_FAIL;
    }
    
    esp_http_client_set_method(client, HTTP_METHOD_POST);
    esp_http_client_set_header(client, "Content-Type", header);
    esp_http_client_set_post_field(client, body, total_len);
    
    ESP_LOGI(TAG, "Performing HTTP POST request...");
    esp_err_t err = esp_http_client_perform(client);
    
    if (err == ESP_OK) {
        int status_code = esp_http_client_get_status_code(client);
        int content_length = esp_http_client_get_content_length(client);
        
        ESP_LOGI(TAG, "HTTP POST completed - Status: %d, Content-Length: %d", 
                 status_code, content_length);
        
        if (status_code == 200) {
            ESP_LOGI(TAG, "Upload successful, deleting local file: %s", path);
            if (unlink(path) == 0) {
                ESP_LOGI(TAG, "Local file deleted successfully");
            } else {
                ESP_LOGW(TAG, "Failed to delete local file after successful upload");
            }
        } else {
            ESP_LOGW(TAG, "Upload failed with HTTP status: %d", status_code);
            err = ESP_FAIL;
        }
    } else {
        ESP_LOGE(TAG, "HTTP request failed: %s", esp_err_to_name(err));
    }
    
    esp_http_client_cleanup(client);
    free(body);
    return err;
}

static void upload_pending_logs(void) {
    ESP_LOGI(TAG, "Checking for pending log uploads...");
    
    if (!check_system_stability()) {
        ESP_LOGD(TAG, "System not stable yet, skipping upload");
        return;
    }
    
    struct stat st;
    if (stat("/spiffs/log.prev", &st) != 0) {
        ESP_LOGD(TAG, "No log.prev found, nothing to upload");
        return;
    }
    
    ESP_LOGI(TAG, "Found log.prev, size: %ld bytes", st.st_size);
    
    if (st.st_size == 0) {
        ESP_LOGW(TAG, "log.prev is empty, deleting");
        unlink("/spiffs/log.prev");
        return;
    }
    
    ESP_LOGI(TAG, "Starting HTTP upload process...");
    
    int retry_count = 0;
    while (retry_count < MAX_UPLOAD_RETRIES) {
        // Verificar conectividad antes de cada intento
        if (!check_full_connectivity()) {
            ESP_LOGW(TAG, "Lost connectivity, aborting upload attempts");
            break;
        }
        
        esp_err_t err = send_file_with_verification("/spiffs/log.prev");
        if (err == ESP_OK) {
            ESP_LOGI(TAG, "Upload completed successfully on attempt %d", retry_count + 1);
            return;
        } else if (err == ESP_ERR_NOT_FOUND) {
            ESP_LOGI(TAG, "File was deleted during upload process");
            return;
        } else if (err == ESP_ERR_INVALID_STATE) {
            ESP_LOGW(TAG, "No connectivity available, will retry later");
            break;
        } else {
            retry_count++;
            ESP_LOGW(TAG, "Upload failed on attempt %d: %s", retry_count, esp_err_to_name(err));
            
            if (retry_count < MAX_UPLOAD_RETRIES) {
                ESP_LOGI(TAG, "Retrying upload in 30 seconds...");
                vTaskDelay(pdMS_TO_TICKS(30000));
            }
        }
    }
    
    if (retry_count >= MAX_UPLOAD_RETRIES) {
        ESP_LOGE(TAG, "Failed to upload after %d attempts, giving up for this cycle", MAX_UPLOAD_RETRIES);
    }
}

static void uploader_task(void *arg) {
    ESP_LOGI(TAG, "Log uploader task started");
    s_uploader_running = true;
    
    // Esperar más tiempo inicial para que el sistema se estabilice
    ESP_LOGI(TAG, "Waiting for system stabilization...");
    vTaskDelay(pdMS_TO_TICKS(120000)); // 2 minutos inicial
    
    while (s_uploader_running) {
        // Verificar estabilidad del sistema
        if (check_system_stability()) {
            // Solo subir logs pendientes si el sistema está estable
            ESP_LOGI(TAG, "System is stable, checking for pending log uploads");
            
            // Intentar subir logs existentes (no forzar rotación)
            upload_pending_logs();
        } else {
            ESP_LOGD(TAG, "System not stable yet, skipping log operations");
        }
        
        // Esperar el intervalo completo de upload
        ESP_LOGD(TAG, "Next upload check in %d hours", UPLOAD_INTERVAL_HOURS);
        vTaskDelay(pdMS_TO_TICKS(UPLOAD_INTERVAL_HOURS * 3600 * 1000));
    }
    
    ESP_LOGI(TAG, "Log uploader task ended");
    vTaskDelete(NULL);
}

void log_uploader_start(const char *esp32_id) {
    if (s_uploader_running) {
        ESP_LOGW(TAG, "Log uploader already running");
        return;
    }
    
    if (esp32_id) {
        strlcpy(s_esp32_id, esp32_id, sizeof(s_esp32_id));
        ESP_LOGI(TAG, "Log uploader initialized with ESP32 ID: %s", s_esp32_id);
    } else {
        s_esp32_id[0] = '\0';
        ESP_LOGW(TAG, "Log uploader started without ESP32 ID");
    }
    
    // Resetear estado de estabilidad
    s_system_stable = false;
    s_last_stability_check = 0;
    
    BaseType_t result = xTaskCreate(uploader_task, "log_uploader", 6144, NULL, 3, NULL);
    if (result == pdPASS) {
        ESP_LOGI(TAG, "Log uploader task created successfully");
    } else {
        ESP_LOGE(TAG, "Failed to create log uploader task");
        s_uploader_running = false;
    }
}

void log_uploader_stop(void) {
    if (s_uploader_running) {
        ESP_LOGI(TAG, "Stopping log uploader...");
        s_uploader_running = false;
        // La tarea se detendrá en su próxima iteración
    }
}

bool log_uploader_is_system_stable(void) {
    return s_system_stable;
}

void log_uploader_reset_stability(void) {
    ESP_LOGI(TAG, "Resetting system stability state");
    s_system_stable = false;
    s_last_stability_check = 0;
}