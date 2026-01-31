#include "connectivity_monitor.h"
#include "custom_logging.h"
#include "wifi_manager.h"
#include "config_manager.h"
#include "mqtt_manager.h"
#include "time_manager.h"
#include "esp_timer.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "lwip/inet.h"
#include "lwip/netdb.h"
#include "lwip/sockets.h"
#include <string.h>
#include <time.h>

#define TAG "CONN_MON"
#define MAX_PENDING_EVENTS 20
#define EVENT_CORRELATION_WINDOW_MS 60000

typedef struct {
    int64_t start_time_ms;
    int64_t end_time_ms;
    connectivity_event_type_t loss_type;
    char ssid[33];
    char panel_name[32];
    bool valid;
} pending_connectivity_event_t;

typedef struct {
    connectivity_monitor_state_t state;
    SemaphoreHandle_t mutex;
    TaskHandle_t monitor_task;
    connectivity_event_callback_t event_callback;
    void *callback_user_data;
    
    connectivity_status_t status;
    connectivity_event_t events[CONNECTIVITY_MAX_STORED_EVENTS];
    size_t event_count;
    size_t event_write_index;
    
    pending_connectivity_event_t pending_events[MAX_PENDING_EVENTS];
    int pending_event_write_index;
    int pending_event_count;
    
    int64_t init_time;
    bool stabilization_complete;
    bool monitoring_active;
} connectivity_monitor_context_t;

static connectivity_monitor_context_t s_conn_ctx = {0};

static void safe_string_copy(char *dest, const char *src, size_t dest_size) {
    if (!dest || !src || dest_size == 0) {
        return;
    }
    strncpy(dest, src, dest_size - 1);
    dest[dest_size - 1] = '\0';
}

static bool validate_event_pointers(connectivity_monitor_context_t *ctx) {
    if (!ctx) {
        return false;
    }
    if (ctx->event_count > CONNECTIVITY_MAX_STORED_EVENTS) {
        ctx->event_count = CONNECTIVITY_MAX_STORED_EVENTS;
        return false;
    }
    if (ctx->pending_event_count > MAX_PENDING_EVENTS) {
        ctx->pending_event_count = MAX_PENDING_EVENTS;
        return false;
    }
    return true;
}

static void save_event_to_ram(int64_t start_time, int64_t end_time,
                              connectivity_event_type_t loss_type,
                              const char* ssid, const char* panel_name) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;

    // NUEVO: Verificar NTP sincronizado
    if (!time_manager_is_synchronized()) {
        LOG_W(TAG, "NTP not synced - rejecting event (invalid timestamps)");
        return;
    }

    // NUEVO: Validar duración mínima (5 segundos)
    int64_t duration_ms = end_time - start_time;
    if (duration_ms < 5000) {
        LOG_W(TAG, "Event duration too short (%lld ms) - rejecting",
              (long long)duration_ms);
        return;
    }

    if (!validate_event_pointers(ctx)) {
        return;
    }

    LOG_I(TAG, "save_event_to_ram called: start=%lld, end=%lld, type=%d, ssid=%s, panel=%s",
          (long long)start_time, (long long)end_time, loss_type,
          ssid ? ssid : "NULL", panel_name ? panel_name : "NULL");
    
    LOG_I(TAG, "Before save: pending_event_count=%d, write_index=%d", 
          ctx->pending_event_count, ctx->pending_event_write_index);
    
    int index;
    if (ctx->pending_event_count < MAX_PENDING_EVENTS) {
        index = ctx->pending_event_count;
        ctx->pending_event_count++;
    } else {
        index = ctx->pending_event_write_index;
        ctx->pending_event_write_index = (ctx->pending_event_write_index + 1) % MAX_PENDING_EVENTS;
        LOG_W(TAG, "RAM event queue full, overwriting event at index %d", index);
    }
    
    if (index < 0 || index >= MAX_PENDING_EVENTS) {
        LOG_E(TAG, "Invalid index calculated: %d", index);
        return;
    }
    
    ctx->pending_events[index].start_time_ms = start_time;
    ctx->pending_events[index].end_time_ms = end_time;
    ctx->pending_events[index].loss_type = loss_type;
    ctx->pending_events[index].valid = true;
    
    if (ssid && strlen(ssid) > 0) {
        safe_string_copy(ctx->pending_events[index].ssid, ssid, sizeof(ctx->pending_events[index].ssid));
    } else {
        safe_string_copy(ctx->pending_events[index].ssid, "Unknown", sizeof(ctx->pending_events[index].ssid));
    }
    
    if (panel_name && strlen(panel_name) > 0) {
        safe_string_copy(ctx->pending_events[index].panel_name, panel_name, sizeof(ctx->pending_events[index].panel_name));
    } else {
        safe_string_copy(ctx->pending_events[index].panel_name, "Panel", sizeof(ctx->pending_events[index].panel_name));
    }
    
    if (ctx->pending_event_count <= MAX_PENDING_EVENTS) {
        ctx->pending_event_write_index = (index + 1) % MAX_PENDING_EVENTS;
    }
    
    LOG_I(TAG, "Event saved to RAM at index %d: %s from %lld to %lld, ssid=%s, panel=%s", 
          index, 
          (loss_type == CONNECTIVITY_EVENT_WIFI_LOST) ? "wifi_lost" : 
          (loss_type == CONNECTIVITY_EVENT_INTERNET_LOST) ? "internet_lost" : 
          (loss_type == CONNECTIVITY_EVENT_MQTT_LOST) ? "mqtt_lost" : "unknown",
          (long long)start_time, (long long)end_time, 
          ctx->pending_events[index].ssid, ctx->pending_events[index].panel_name);
    
    LOG_I(TAG, "After save: pending_event_count=%d, write_index=%d", 
          ctx->pending_event_count, ctx->pending_event_write_index);
}

static esp_err_t process_pending_ram_events(void) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (!mqtt_manager_is_connected()) {
        LOG_W(TAG, "MQTT not connected, cannot process pending events");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(500)) != pdTRUE) {
        LOG_E(TAG, "Failed to acquire mutex for processing events");
        return ESP_ERR_TIMEOUT;
    }
    
    LOG_I(TAG, "Processing RAM events - total pending: %d", ctx->pending_event_count);
    
    int processed = 0;
    int failed = 0;
    
    for (int i = 0; i < ctx->pending_event_count && i < MAX_PENDING_EVENTS; i++) {
        if (ctx->pending_events[i].valid) {
            LOG_I(TAG, "Processing event %d: type=%d, start=%lld, end=%lld, ssid=%s", 
                  i, ctx->pending_events[i].loss_type,
                  (long long)ctx->pending_events[i].start_time_ms,
                  (long long)ctx->pending_events[i].end_time_ms,
                  ctx->pending_events[i].ssid);
            
            mqtt_connectivity_message_t msg = {0};
            msg.start_time_ms = ctx->pending_events[i].start_time_ms;
            msg.end_time_ms = ctx->pending_events[i].end_time_ms;

            switch (ctx->pending_events[i].loss_type) {
                case CONNECTIVITY_EVENT_WIFI_LOST:
                    msg.type = CONNECTIVITY_MSG_WIFI_LOST;
                    break;
                case CONNECTIVITY_EVENT_INTERNET_LOST:
                    msg.type = CONNECTIVITY_MSG_INTERNET_LOST;
                    break;
                case CONNECTIVITY_EVENT_MQTT_LOST:
                    msg.type = CONNECTIVITY_MSG_MQTT_LOST;
                    break;
                default:
                    LOG_W(TAG, "Unknown connectivity event type: %d", ctx->pending_events[i].loss_type);
                    continue;
            }
            
            strncpy(msg.ssid, ctx->pending_events[i].ssid, sizeof(msg.ssid) - 1);
            msg.ssid[sizeof(msg.ssid) - 1] = '\0';
            
            strncpy(msg.panel_name, ctx->pending_events[i].panel_name, sizeof(msg.panel_name) - 1);
            msg.panel_name[sizeof(msg.panel_name) - 1] = '\0';
            
            xSemaphoreGive(ctx->mutex);
            
            esp_err_t ret = mqtt_manager_send_connectivity_message(&msg);
            
            if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(500)) != pdTRUE) {
                LOG_E(TAG, "Failed to reacquire mutex after MQTT send");
                return ESP_ERR_TIMEOUT;
            }
            
            if (ret == ESP_OK) {
                LOG_I(TAG, "RAM event %d sent successfully: %s", i,
                      (msg.type == CONNECTIVITY_MSG_WIFI_LOST) ? "wifi_lost" : 
                      (msg.type == CONNECTIVITY_MSG_INTERNET_LOST) ? "internet_lost" :
                      (msg.type == CONNECTIVITY_MSG_MQTT_LOST) ? "mqtt_lost" : "unknown");
                ctx->pending_events[i].valid = false;
                processed++;
            } else {
                LOG_W(TAG, "Failed to send RAM event %d: %s", i, esp_err_to_name(ret));
                failed++;
                break;
            }
            
            xSemaphoreGive(ctx->mutex);
            vTaskDelay(pdMS_TO_TICKS(100));
            if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(500)) != pdTRUE) {
                LOG_E(TAG, "Failed to reacquire mutex after delay");
                return ESP_ERR_TIMEOUT;
            }
        }
    }
    
    if (processed > 0) {
        int write_pos = 0;
        for (int read_pos = 0; read_pos < MAX_PENDING_EVENTS; read_pos++) {
            if (ctx->pending_events[read_pos].valid) {
                if (write_pos != read_pos) {
                    ctx->pending_events[write_pos] = ctx->pending_events[read_pos];
                    ctx->pending_events[read_pos].valid = false;
                }
                write_pos++;
            }
        }
        
        ctx->pending_event_count -= processed;
        ctx->pending_event_write_index = write_pos;
        
        LOG_I(TAG, "Processed %d RAM events successfully, %d failed, %d remaining", 
              processed, failed, ctx->pending_event_count);
    } else {
        LOG_W(TAG, "No events were processed - processed=%d, failed=%d, total_pending=%d", 
              processed, failed, ctx->pending_event_count);
    }
    
    xSemaphoreGive(ctx->mutex);
    return (processed > 0) ? ESP_OK : ESP_FAIL;
}

static esp_err_t ping_host(const char *host, bool *success) {
    *success = false;
    
    struct addrinfo hints = {0};
    struct addrinfo *result = NULL;
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    
    int ret = getaddrinfo(host, "53", &hints, &result);
    if (ret != 0 || result == NULL) {
        return ESP_FAIL;
    }
    
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        freeaddrinfo(result);
        return ESP_FAIL;
    }
    
    struct timeval timeout = {
        .tv_sec = 3,
        .tv_usec = 0
    };
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    
    ret = connect(sock, result->ai_addr, result->ai_addrlen);
    
    close(sock);
    freeaddrinfo(result);
    
    if (ret == 0) {
        *success = true;
        return ESP_OK;
    }
    
    return ESP_OK;
}

static void add_event(connectivity_event_type_t type, const char *ssid, int64_t timestamp) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        
        if (type == CONNECTIVITY_EVENT_WIFI_LOST) {
            int64_t earliest_loss_time = timestamp;
            
            // Buscar eventos de internet y MQTT para obtener el timestamp más temprano
            for (int i = 0; i < ctx->event_count; i++) {
                size_t index = (ctx->event_write_index - ctx->event_count + i) % CONNECTIVITY_MAX_STORED_EVENTS;
                connectivity_event_t *event = &ctx->events[index];
                
                if ((event->type == CONNECTIVITY_EVENT_INTERNET_LOST || 
                     event->type == CONNECTIVITY_EVENT_MQTT_LOST) && !event->sent) {
                    if (event->timestamp_ms < earliest_loss_time) {
                        earliest_loss_time = event->timestamp_ms;
                    }
                }
            }
            
            // Invalidar eventos de internet y MQTT
            for (int i = 0; i < ctx->event_count; i++) {
                size_t index = (ctx->event_write_index - ctx->event_count + i) % CONNECTIVITY_MAX_STORED_EVENTS;
                connectivity_event_t *event = &ctx->events[index];
                
                if ((event->type == CONNECTIVITY_EVENT_INTERNET_LOST || 
                     event->type == CONNECTIVITY_EVENT_MQTT_LOST) && !event->sent) {
                    event->sent = true;
                }
            }
            
            // Limpiar eventos RAM de internet y MQTT
            for (int j = ctx->pending_event_count - 1; j >= 0; j--) {
                if (ctx->pending_events[j].valid && 
                    (ctx->pending_events[j].loss_type == CONNECTIVITY_EVENT_INTERNET_LOST ||
                     ctx->pending_events[j].loss_type == CONNECTIVITY_EVENT_MQTT_LOST)) {
                    ctx->pending_events[j].valid = false;
                }
            }
            
            // Compactar eventos RAM
            int write_pos = 0;
            for (int read_pos = 0; read_pos < ctx->pending_event_count; read_pos++) {
                if (ctx->pending_events[read_pos].valid) {
                    if (write_pos != read_pos) {
                        ctx->pending_events[write_pos] = ctx->pending_events[read_pos];
                    }
                    write_pos++;
                }
            }
            ctx->pending_event_count = write_pos;
            ctx->pending_event_write_index = write_pos;
            
            // Crear evento WiFi con timestamp consolidado
            connectivity_event_t *event = &ctx->events[ctx->event_write_index];
            event->type = type;
            event->timestamp_ms = earliest_loss_time;
            event->sent = false;
            
            if (ssid) {
                strncpy(event->ssid, ssid, sizeof(event->ssid) - 1);
                event->ssid[sizeof(event->ssid) - 1] = '\0';
            } else {
                event->ssid[0] = '\0';
            }
            
            char panel_name[32] = {0};
            if (config_manager_get_str("panel_name", panel_name, sizeof(panel_name)) == ESP_OK && strlen(panel_name) > 0) {
                strncpy(event->panel_name, panel_name, sizeof(event->panel_name) - 1);
                event->panel_name[sizeof(event->panel_name) - 1] = '\0';
            } else {
                strcpy(event->panel_name, "Panel");
            }
            
            ctx->event_write_index = (ctx->event_write_index + 1) % CONNECTIVITY_MAX_STORED_EVENTS;
            
            if (ctx->event_count < CONNECTIVITY_MAX_STORED_EVENTS) {
                ctx->event_count++;
            }
            
            if (ctx->event_callback) {
                ctx->event_callback(event, ctx->callback_user_data);
            }
        }
        
        else if (type == CONNECTIVITY_EVENT_WIFI_RECOVERED) {
            connectivity_event_t *wifi_loss = NULL;
            for (int i = ctx->event_count - 1; i >= 0; i--) {
                size_t index = (ctx->event_write_index - ctx->event_count + i) % CONNECTIVITY_MAX_STORED_EVENTS;
                connectivity_event_t *event = &ctx->events[index];
                
                if (event->type == CONNECTIVITY_EVENT_WIFI_LOST && !event->sent) {
                    wifi_loss = event;
                    break;
                }
            }
            
            if (wifi_loss) {
                char panel_name[32] = {0};
                if (config_manager_get_str("panel_name", panel_name, sizeof(panel_name)) != ESP_OK || 
                    strlen(panel_name) == 0) {
                    strcpy(panel_name, "Panel");
                }
                
                save_event_to_ram(wifi_loss->timestamp_ms, timestamp, CONNECTIVITY_EVENT_WIFI_LOST,
                                  ssid ? ssid : wifi_loss->ssid, panel_name);
                
                wifi_loss->sent = true;
                
                if (ctx->event_callback) {
                    connectivity_event_t recovery_event = {0};
                    recovery_event.type = CONNECTIVITY_EVENT_WIFI_RECOVERED;
                    recovery_event.timestamp_ms = timestamp;
                    strncpy(recovery_event.ssid, ssid ? ssid : wifi_loss->ssid, sizeof(recovery_event.ssid) - 1);
                    recovery_event.ssid[sizeof(recovery_event.ssid) - 1] = '\0';
                    strncpy(recovery_event.panel_name, panel_name, sizeof(recovery_event.panel_name) - 1);
                    recovery_event.panel_name[sizeof(recovery_event.panel_name) - 1] = '\0';
                    recovery_event.sent = true;
                    
                    ctx->event_callback(&recovery_event, ctx->callback_user_data);
                }
            }
        } 
        
        else if (type == CONNECTIVITY_EVENT_INTERNET_RECOVERED) {
            bool has_pending_wifi_loss = false;
            for (int i = 0; i < ctx->event_count; i++) {
                size_t index = (ctx->event_write_index - ctx->event_count + i) % CONNECTIVITY_MAX_STORED_EVENTS;
                connectivity_event_t *event = &ctx->events[index];
                
                if (event->type == CONNECTIVITY_EVENT_WIFI_LOST && !event->sent) {
                    has_pending_wifi_loss = true;
                    break;
                }
            }
            
            if (!has_pending_wifi_loss) {
                connectivity_event_t *internet_loss = NULL;
                for (int i = ctx->event_count - 1; i >= 0; i--) {
                    size_t index = (ctx->event_write_index - ctx->event_count + i) % CONNECTIVITY_MAX_STORED_EVENTS;
                    connectivity_event_t *event = &ctx->events[index];
                    
                    if (event->type == CONNECTIVITY_EVENT_INTERNET_LOST && !event->sent) {
                        internet_loss = event;
                        break;
                    }
                }
                
                if (internet_loss) {
                    char panel_name[32] = {0};
                    if (config_manager_get_str("panel_name", panel_name, sizeof(panel_name)) != ESP_OK || 
                        strlen(panel_name) == 0) {
                        strcpy(panel_name, "Panel");
                    }
                    
                    save_event_to_ram(internet_loss->timestamp_ms, timestamp, CONNECTIVITY_EVENT_INTERNET_LOST,
                                      ssid ? ssid : internet_loss->ssid, panel_name);
                    
                    internet_loss->sent = true;
                    
                    if (ctx->event_callback) {
                        connectivity_event_t recovery_event = {0};
                        recovery_event.type = CONNECTIVITY_EVENT_INTERNET_RECOVERED;
                        recovery_event.timestamp_ms = timestamp;
                        strncpy(recovery_event.ssid, ssid ? ssid : internet_loss->ssid, sizeof(recovery_event.ssid) - 1);
                        recovery_event.ssid[sizeof(recovery_event.ssid) - 1] = '\0';
                        strncpy(recovery_event.panel_name, panel_name, sizeof(recovery_event.panel_name) - 1);
                        recovery_event.panel_name[sizeof(recovery_event.panel_name) - 1] = '\0';
                        recovery_event.sent = true;
                        
                        ctx->event_callback(&recovery_event, ctx->callback_user_data);
                    }
                }
            }
        }

        else if (type == CONNECTIVITY_EVENT_MQTT_RECOVERED) {
            bool has_pending_wifi_loss = false;
            for (int i = 0; i < ctx->event_count; i++) {
                size_t index = (ctx->event_write_index - ctx->event_count + i) % CONNECTIVITY_MAX_STORED_EVENTS;
                connectivity_event_t *event = &ctx->events[index];
                
                if (event->type == CONNECTIVITY_EVENT_WIFI_LOST && !event->sent) {
                    has_pending_wifi_loss = true;
                    break;
                }
            }
            
            if (!has_pending_wifi_loss) {
                connectivity_event_t *mqtt_loss = NULL;
                for (int i = ctx->event_count - 1; i >= 0; i--) {
                    size_t index = (ctx->event_write_index - ctx->event_count + i) % CONNECTIVITY_MAX_STORED_EVENTS;
                    connectivity_event_t *event = &ctx->events[index];
                    
                    if (event->type == CONNECTIVITY_EVENT_MQTT_LOST && !event->sent) {
                        mqtt_loss = event;
                        break;
                    }
                }
                
                if (mqtt_loss) {
                    char panel_name[32] = {0};
                    if (config_manager_get_str("panel_name", panel_name, sizeof(panel_name)) != ESP_OK || 
                        strlen(panel_name) == 0) {
                        strcpy(panel_name, "Panel");
                    }
                    
                    save_event_to_ram(mqtt_loss->timestamp_ms, timestamp, CONNECTIVITY_EVENT_MQTT_LOST,
                                      ssid ? ssid : mqtt_loss->ssid, panel_name);
                    
                    mqtt_loss->sent = true;
                    
                    if (ctx->event_callback) {
                        connectivity_event_t recovery_event = {0};
                        recovery_event.type = CONNECTIVITY_EVENT_MQTT_RECOVERED;
                        recovery_event.timestamp_ms = timestamp;
                        strncpy(recovery_event.ssid, ssid ? ssid : mqtt_loss->ssid, sizeof(recovery_event.ssid) - 1);
                        recovery_event.ssid[sizeof(recovery_event.ssid) - 1] = '\0';
                        strncpy(recovery_event.panel_name, panel_name, sizeof(recovery_event.panel_name) - 1);
                        recovery_event.panel_name[sizeof(recovery_event.panel_name) - 1] = '\0';
                        recovery_event.sent = true;
                        
                        ctx->event_callback(&recovery_event, ctx->callback_user_data);
                    }
                }
            }
        }
        
        else {
            // Eventos de pérdida (INTERNET_LOST, MQTT_LOST, etc.)
            connectivity_event_t *event = &ctx->events[ctx->event_write_index];
            event->type = type;
            event->timestamp_ms = timestamp;
            event->sent = false;
            
            if (ssid) {
                strncpy(event->ssid, ssid, sizeof(event->ssid) - 1);
                event->ssid[sizeof(event->ssid) - 1] = '\0';
            } else {
                event->ssid[0] = '\0';
            }
            
            char panel_name[32] = {0};
            if (config_manager_get_str("panel_name", panel_name, sizeof(panel_name)) == ESP_OK && strlen(panel_name) > 0) {
                strncpy(event->panel_name, panel_name, sizeof(event->panel_name) - 1);
                event->panel_name[sizeof(event->panel_name) - 1] = '\0';
            } else {
                strcpy(event->panel_name, "Panel");
            }
            
            ctx->event_write_index = (ctx->event_write_index + 1) % CONNECTIVITY_MAX_STORED_EVENTS;
            
            if (ctx->event_count < CONNECTIVITY_MAX_STORED_EVENTS) {
                ctx->event_count++;
            }
            
            if (ctx->event_callback) {
                ctx->event_callback(event, ctx->callback_user_data);
            }
        }
        
        xSemaphoreGive(ctx->mutex);
    }
}

static void monitor_task(void *pvParameters) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    LOG_I(TAG, "Monitor task started, waiting for stabilization period");
    
    while (ctx->monitoring_active) {
        int64_t current_time = esp_timer_get_time() / 1000;
        
        if (!ctx->stabilization_complete) {
            int64_t time_elapsed = current_time - ctx->init_time;
            int64_t time_remaining = CONNECTIVITY_STABILIZATION_TIME_MS - time_elapsed;
            
            if (time_remaining <= 0) {
                ctx->stabilization_complete = true;
                ctx->state = CONNECTIVITY_STATE_MONITORING;
                LOG_I(TAG, "Stabilization period complete, starting connectivity monitoring");
            } else {
                vTaskDelay(pdMS_TO_TICKS(1000));
                continue;
            }
        }
        
        bool wifi_connected = wifi_manager_is_connected();
        bool internet_available = false;
        bool mqtt_connected = mqtt_manager_is_connected();
        
        if (wifi_connected) {
            char current_ssid[33] = {0};
            wifi_manager_get_configured_ssid(current_ssid, sizeof(current_ssid));
            
            if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
                strncpy(ctx->status.current_ssid, current_ssid, sizeof(ctx->status.current_ssid) - 1);
                ctx->status.current_ssid[sizeof(ctx->status.current_ssid) - 1] = '\0';
                xSemaphoreGive(ctx->mutex);
            }
            
            esp_err_t ping_ret = ping_host("8.8.8.8", &internet_available);
            if (ping_ret != ESP_OK) {
                internet_available = false;
            }
        }
        
        connectivity_event_type_t event_to_add = -1;
        char event_ssid[33] = {0};
        bool add_event_needed = false;
        
        if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
            bool wifi_state_changed = (ctx->status.wifi_connected != wifi_connected);
            bool internet_state_changed = (ctx->status.internet_available != internet_available);
            bool mqtt_state_changed = (ctx->status.mqtt_connected != mqtt_connected);
            
            // PRIORIDAD 1: Cambios de WiFi (más críticos)
            if (wifi_state_changed) {
                if (!wifi_connected && ctx->status.wifi_connected) {
                    ctx->status.wifi_lost_time = current_time;
                    event_to_add = CONNECTIVITY_EVENT_WIFI_LOST;
                    strncpy(event_ssid, ctx->status.current_ssid, sizeof(event_ssid) - 1);
                    add_event_needed = true;
                    LOG_W(TAG, "WiFi connection lost to SSID: %s", ctx->status.current_ssid);
                } else if (wifi_connected && !ctx->status.wifi_connected) {
                    if (ctx->status.wifi_lost_time > 0) {
                        event_to_add = CONNECTIVITY_EVENT_WIFI_RECOVERED;
                        strncpy(event_ssid, ctx->status.current_ssid, sizeof(event_ssid) - 1);
                        add_event_needed = true;
                        LOG_I(TAG, "WiFi connection recovered to SSID: %s", ctx->status.current_ssid);
                        ctx->status.wifi_lost_time = 0;
                    }
                }
                ctx->status.wifi_connected = wifi_connected;
                
                if (wifi_connected && event_to_add == CONNECTIVITY_EVENT_WIFI_RECOVERED) {
                    ctx->status.internet_lost_time = 0;
                    ctx->status.mqtt_lost_time = 0;
                }
            }
            
            // PRIORIDAD 2: Cambios de MQTT (solo si no hay eventos WiFi)
            else if (wifi_connected && mqtt_state_changed) {
                if (!mqtt_connected && ctx->status.mqtt_connected) {
                    ctx->status.mqtt_lost_time = current_time;
                    event_to_add = CONNECTIVITY_EVENT_MQTT_LOST;
                    strncpy(event_ssid, ctx->status.current_ssid, sizeof(event_ssid) - 1);
                    add_event_needed = true;
                    LOG_W(TAG, "MQTT connection lost while connected to WiFi: %s", ctx->status.current_ssid);
                } else if (mqtt_connected && !ctx->status.mqtt_connected) {
                    if (ctx->status.mqtt_lost_time > 0) {
                        event_to_add = CONNECTIVITY_EVENT_MQTT_RECOVERED;
                        strncpy(event_ssid, ctx->status.current_ssid, sizeof(event_ssid) - 1);
                        add_event_needed = true;
                        LOG_I(TAG, "MQTT connection recovered on WiFi: %s", ctx->status.current_ssid);
                        ctx->status.mqtt_lost_time = 0;
                    }
                }
            }
            
            // PRIORIDAD 3: Cambios de Internet (solo si no hay eventos WiFi o MQTT)
            else if (wifi_connected && internet_state_changed) {
                if (!internet_available && ctx->status.internet_available) {
                    ctx->status.internet_lost_time = current_time;
                    event_to_add = CONNECTIVITY_EVENT_INTERNET_LOST;
                    strncpy(event_ssid, ctx->status.current_ssid, sizeof(event_ssid) - 1);
                    add_event_needed = true;
                    LOG_W(TAG, "Internet connectivity lost while connected to WiFi: %s", ctx->status.current_ssid);
                } else if (internet_available && !ctx->status.internet_available) {
                    if (ctx->status.internet_lost_time > 0) {
                        event_to_add = CONNECTIVITY_EVENT_INTERNET_RECOVERED;
                        strncpy(event_ssid, ctx->status.current_ssid, sizeof(event_ssid) - 1);
                        add_event_needed = true;
                        LOG_I(TAG, "Internet connectivity recovered on WiFi: %s", ctx->status.current_ssid);
                        ctx->status.internet_lost_time = 0;
                    }
                }
            }
            
            if (!wifi_connected) {
                internet_available = false;
                mqtt_connected = false;
                ctx->status.internet_lost_time = 0;
                ctx->status.mqtt_lost_time = 0;
            }
            
            ctx->status.internet_available = internet_available;
            ctx->status.mqtt_connected = mqtt_connected;
            ctx->status.last_check_time = current_time;
            
            xSemaphoreGive(ctx->mutex);
        }
        
        if (add_event_needed && event_to_add != -1) {
            add_event(event_to_add, event_ssid, current_time);
        }
        
        vTaskDelay(pdMS_TO_TICKS(CONNECTIVITY_CHECK_INTERVAL_MS));
    }
    
    LOG_I(TAG, "Monitor task ending");
    vTaskDelete(NULL);
}

esp_err_t connectivity_monitor_report_mqtt_status(bool mqtt_connected) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (ctx->mutex == NULL || !ctx->monitoring_active) {
        return ESP_ERR_INVALID_STATE;
    }
    
    int64_t current_time = esp_timer_get_time() / 1000;
    
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        if (ctx->status.mqtt_connected != mqtt_connected) {
            LOG_I(TAG, "MQTT status change reported: %s -> %s", 
                  ctx->status.mqtt_connected ? "connected" : "disconnected",
                  mqtt_connected ? "connected" : "disconnected");
            
            ctx->status.mqtt_connected = mqtt_connected;
            
            if (!mqtt_connected) {
                ctx->status.mqtt_lost_time = current_time;
            } else {
                ctx->status.mqtt_lost_time = 0;
            }
        }
        xSemaphoreGive(ctx->mutex);
    }
    
    return ESP_OK;
}

esp_err_t connectivity_monitor_init(void) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (ctx->mutex != NULL) {
        LOG_W(TAG, "Already initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    LOG_I(TAG, "Initializing connectivity monitor (RAM-only version)");
    
    memset(ctx, 0, sizeof(connectivity_monitor_context_t));
    
    ctx->mutex = xSemaphoreCreateMutex();
    if (ctx->mutex == NULL) {
        LOG_E(TAG, "Failed to create mutex");
        return ESP_ERR_NO_MEM;
    }
    
    ctx->state = CONNECTIVITY_STATE_INIT;
    ctx->init_time = esp_timer_get_time() / 1000;
    ctx->stabilization_complete = false;
    ctx->monitoring_active = false;
    ctx->event_count = 0;
    ctx->event_write_index = 0;
    
    ctx->pending_event_count = 0;
    ctx->pending_event_write_index = 0;
    for (int i = 0; i < MAX_PENDING_EVENTS; i++) {
        ctx->pending_events[i].valid = false;
    }
    
    LOG_I(TAG, "Connectivity monitor initialized (RAM-only)");
    return ESP_OK;
}

esp_err_t connectivity_monitor_deinit(void) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (ctx->mutex == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    connectivity_monitor_stop();
    
    if (ctx->mutex) {
        vSemaphoreDelete(ctx->mutex);
        ctx->mutex = NULL;
    }
    
    memset(ctx, 0, sizeof(connectivity_monitor_context_t));
    
    LOG_I(TAG, "Connectivity monitor deinitialized");
    return ESP_OK;
}

esp_err_t connectivity_monitor_start(void) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (ctx->mutex == NULL) {
        LOG_E(TAG, "Not initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    if (ctx->monitoring_active) {
        LOG_W(TAG, "Already started");
        return ESP_OK;
    }
    
    ctx->monitoring_active = true;
    ctx->state = CONNECTIVITY_STATE_STABILIZING;
    ctx->init_time = esp_timer_get_time() / 1000;
    
    LOG_I(TAG, "Starting monitor task with %d ms stabilization period", CONNECTIVITY_STABILIZATION_TIME_MS);
    
    BaseType_t ret = xTaskCreate(monitor_task, "conn_monitor", 4096, NULL, 3, &ctx->monitor_task);
    if (ret != pdPASS) {
        LOG_E(TAG, "Failed to create monitor task");
        ctx->monitoring_active = false;
        ctx->state = CONNECTIVITY_STATE_ERROR;
        return ESP_FAIL;
    }
    
    LOG_I(TAG, "Connectivity monitor started (RAM-only)");
    return ESP_OK;
}

esp_err_t connectivity_monitor_stop(void) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (ctx->mutex == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    ctx->monitoring_active = false;
    
    if (ctx->monitor_task) {
        vTaskDelay(pdMS_TO_TICKS(100));
        ctx->monitor_task = NULL;
    }
    
    ctx->state = CONNECTIVITY_STATE_INIT;
    
    LOG_I(TAG, "Connectivity monitor stopped");
    return ESP_OK;
}

esp_err_t connectivity_monitor_set_event_callback(connectivity_event_callback_t callback, void *user_data) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (ctx->mutex == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        ctx->event_callback = callback;
        ctx->callback_user_data = user_data;
        xSemaphoreGive(ctx->mutex);
    }
    
    return ESP_OK;
}

esp_err_t connectivity_monitor_force_check(void) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (ctx->mutex == NULL || !ctx->monitoring_active) {
        return ESP_ERR_INVALID_STATE;
    }
    
    return ESP_OK;
}

esp_err_t connectivity_monitor_get_status(connectivity_status_t *status) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (ctx->mutex == NULL || status == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        memcpy(status, &ctx->status, sizeof(connectivity_status_t));
        xSemaphoreGive(ctx->mutex);
        return ESP_OK;
    }
    
    return ESP_ERR_TIMEOUT;
}

connectivity_monitor_state_t connectivity_monitor_get_state(void) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (ctx->mutex == NULL) {
        return CONNECTIVITY_STATE_INIT;
    }
    
    return ctx->state;
}

esp_err_t connectivity_monitor_get_pending_events(connectivity_event_t *events, size_t max_events, size_t *event_count) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (ctx->mutex == NULL || events == NULL || event_count == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        size_t count = (ctx->event_count < max_events) ? ctx->event_count : max_events;
        
        for (size_t i = 0; i < count; i++) {
            size_t index = (ctx->event_write_index - ctx->event_count + i) % CONNECTIVITY_MAX_STORED_EVENTS;
            memcpy(&events[i], &ctx->events[index], sizeof(connectivity_event_t));
        }
        
        *event_count = count;
        xSemaphoreGive(ctx->mutex);
        return ESP_OK;
    }
    
    return ESP_ERR_TIMEOUT;
}

esp_err_t connectivity_monitor_mark_events_sent(void) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (ctx->mutex == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        for (size_t i = 0; i < ctx->event_count; i++) {
            size_t index = (ctx->event_write_index - ctx->event_count + i) % CONNECTIVITY_MAX_STORED_EVENTS;
            ctx->events[index].sent = true;
        }
        xSemaphoreGive(ctx->mutex);
        return ESP_OK;
    }
    
    return ESP_ERR_TIMEOUT;
}

esp_err_t connectivity_monitor_clear_events(void) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (ctx->mutex == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        ctx->event_count = 0;
        ctx->event_write_index = 0;
        ctx->pending_event_count = 0;
        ctx->pending_event_write_index = 0;
        
        memset(ctx->events, 0, sizeof(ctx->events));
        for (int i = 0; i < MAX_PENDING_EVENTS; i++) {
            ctx->pending_events[i].valid = false;
        }
        
        xSemaphoreGive(ctx->mutex);
        return ESP_OK;
    }
    
    return ESP_ERR_TIMEOUT;
}

bool connectivity_monitor_has_pending_events(void) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (ctx->mutex == NULL) {
        return false;
    }
    
    bool has_events = false;
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(50)) == pdTRUE) {
        has_events = (ctx->event_count > 0) || (ctx->pending_event_count > 0);
        xSemaphoreGive(ctx->mutex);
    }
    
    return has_events;
}

esp_err_t connectivity_monitor_check_internet_connectivity(bool *has_internet) {
    if (has_internet == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (!wifi_manager_is_connected()) {
        *has_internet = false;
        return ESP_OK;
    }
    
    return ping_host("8.8.8.8", has_internet);
}

esp_err_t connectivity_monitor_process_pending_events(void) {
    return process_pending_ram_events();
}

bool connectivity_monitor_has_pending_ram_events(void) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (ctx->mutex == NULL) {
        return false;
    }
    
    bool has_events = false;
    int event_count = 0;
    int valid_events = 0;
    
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(200)) == pdTRUE) {
        event_count = ctx->pending_event_count;
        
        for (int i = 0; i < MAX_PENDING_EVENTS; i++) {
            if (ctx->pending_events[i].valid) {
                valid_events++;
            }
        }
        
        has_events = (event_count > 0) || (valid_events > 0);
        
        // SOLO loggear si hay eventos pendientes
        if (has_events) {
            LOG_I(TAG, "RAM events found: count=%d, valid=%d", event_count, valid_events);
        }
        
        xSemaphoreGive(ctx->mutex);
    }
    
    return has_events;
}

int connectivity_monitor_get_pending_event_count(void) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (ctx->mutex == NULL) {
        return 0;
    }
    
    int count = 0;
    int valid_count = 0;
    
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(200)) == pdTRUE) {
        count = ctx->pending_event_count;
        
        for (int i = 0; i < MAX_PENDING_EVENTS; i++) {
            if (ctx->pending_events[i].valid) {
                valid_count++;
            }
        }
        
        // SOLO loggear si hay eventos O si hay inconsistencia crítica
        if (count > 0 || valid_count > 0 || (valid_count != count && count > 0)) {
            LOG_I(TAG, "Pending events: count=%d, valid=%d", count, valid_count);
        }
        
        xSemaphoreGive(ctx->mutex);
        return (valid_count > count) ? valid_count : count;
    }
    
    return 0;
}

void connectivity_monitor_debug_dump_events(void) {
    return;
}

esp_err_t connectivity_monitor_get_ram_usage_stats(size_t *total_events, size_t *pending_events, size_t *memory_used) {
    connectivity_monitor_context_t *ctx = &s_conn_ctx;
    
    if (!total_events || !pending_events || !memory_used) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (ctx->mutex == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (xSemaphoreTake(ctx->mutex, pdMS_TO_TICKS(50)) == pdTRUE) {
        *total_events = ctx->event_count;
        *pending_events = ctx->pending_event_count;
        *memory_used = sizeof(connectivity_monitor_context_t);
        xSemaphoreGive(ctx->mutex);
        return ESP_OK;
    }
    
    return ESP_ERR_TIMEOUT;
}