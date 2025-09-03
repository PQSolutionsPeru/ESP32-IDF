#include "log_uploader.h"

#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "log_storage.h"
#include "esp32_id_manager.h"

#ifndef CONFIG_LOG_UPLOAD_URL
#define CONFIG_LOG_UPLOAD_URL "http://example.com:8080/upload"
#endif

#define UPLOAD_INTERVAL_HOURS 24

static const char *TAG = "LOG_UPLOADER";
static char s_esp32_id[ESP32_ID_LENGTH + 1];

static esp_err_t send_file(const char *path) {
    struct stat st;
    if (stat(path, &st) != 0) {
        return ESP_ERR_NOT_FOUND;
    }
    FILE *f = fopen(path, "r");
    if (!f) {
        return ESP_FAIL;
    }

    const char *filename = strrchr(path, '/');
    filename = filename ? filename + 1 : path;

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

    char *body = malloc(total_len);
    if (!body) {
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
    fread(p, 1, st.st_size, f);
    fclose(f);
    p += st.st_size;
    p += sprintf(p, "\r\n--%s--\r\n", boundary);

    esp_http_client_config_t cfg = {
        .url = CONFIG_LOG_UPLOAD_URL,
    };
    esp_http_client_handle_t client = esp_http_client_init(&cfg);
    if (!client) {
        free(body);
        return ESP_FAIL;
    }
    esp_http_client_set_method(client, HTTP_METHOD_POST);
    esp_http_client_set_header(client, "Content-Type", header);
    esp_http_client_set_post_field(client, body, total_len);
    esp_err_t err = esp_http_client_perform(client);
    if (err == ESP_OK && esp_http_client_get_status_code(client) == 200) {
        unlink(path);
    } else {
        ESP_LOGW(TAG, "upload failed: %s", esp_err_to_name(err));
    }
    esp_http_client_cleanup(client);
    free(body);
    return err;
}

static void upload_pending(void) {
    while (1) {
        esp_err_t err = send_file("/spiffs/log.prev");
        if (err == ESP_OK || err == ESP_ERR_NOT_FOUND) {
            break;
        }
        vTaskDelay(pdMS_TO_TICKS(60000));
    }
}

static void uploader_task(void *arg) {
    vTaskDelay(pdMS_TO_TICKS(10000));
    upload_pending();
    while (1) {
        vTaskDelay(pdMS_TO_TICKS(UPLOAD_INTERVAL_HOURS * 3600 * 1000));
        if (log_storage_rotate() == ESP_OK) {
            upload_pending();
        }
    }
}

void log_uploader_start(const char *esp32_id) {
    if (esp32_id) {
        strlcpy(s_esp32_id, esp32_id, sizeof(s_esp32_id));
    } else {
        s_esp32_id[0] = '\0';
    }
    xTaskCreate(uploader_task, "log_uploader", 4096, NULL, 5, NULL);
}

