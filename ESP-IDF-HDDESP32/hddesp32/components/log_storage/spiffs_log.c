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
#define LOG_MAX_SIZE   (200 * 1024)

static const char *TAG = "LOG_STORAGE";
static SemaphoreHandle_t s_log_mutex;

static esp_err_t check_rotate(FILE *f) {
    long size = ftell(f);
    if (size < 0) {
        return ESP_FAIL;
    }
    if (size < LOG_MAX_SIZE) {
        return ESP_OK;
    }
    fclose(f);
    if (access(PREV_LOG_FILE, F_OK) == 0) {
        unlink(PREV_LOG_FILE);
    }
    rename(LOG_FILE, PREV_LOG_FILE);
    FILE *nf = fopen(LOG_FILE, "w");
    if (nf) {
        fclose(nf);
    }
    return ESP_OK;
}

esp_err_t log_storage_init(void) {
    s_log_mutex = xSemaphoreCreateMutex();
    if (!s_log_mutex) {
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
        return ret;
    }
    return ESP_OK;
}

int spiffs_vprintf(const char *fmt, va_list ap) {
    va_list ap_copy;
    va_copy(ap_copy, ap);
    int res = vprintf(fmt, ap);

    if (s_log_mutex && xSemaphoreTake(s_log_mutex, portMAX_DELAY) == pdTRUE) {
        FILE *f = fopen(LOG_FILE, "a");
        if (f) {
            vfprintf(f, fmt, ap_copy);
            fflush(f);
            fsync(fileno(f));
            check_rotate(f);
            fclose(f);
        }
        xSemaphoreGive(s_log_mutex);
    }
    va_end(ap_copy);
    return res;
}

esp_err_t log_storage_rotate(void) {
    if (xSemaphoreTake(s_log_mutex, portMAX_DELAY) != pdTRUE) {
        return ESP_FAIL;
    }
    if (access(LOG_FILE, F_OK) != 0) {
        xSemaphoreGive(s_log_mutex);
        return ESP_ERR_NOT_FOUND;
    }
    if (access(PREV_LOG_FILE, F_OK) == 0) {
        unlink(PREV_LOG_FILE);
    }
    esp_err_t ret = rename(LOG_FILE, PREV_LOG_FILE);
    xSemaphoreGive(s_log_mutex);
    return (ret == 0) ? ESP_OK : ESP_FAIL;
}

