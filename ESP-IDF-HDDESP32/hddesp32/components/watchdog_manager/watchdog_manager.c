#include "watchdog_manager.h"
#include <string.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/timers.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_task_wdt.h"
#include "esp_heap_caps.h"
#include "wifi_manager.h"
#include "mqtt_manager.h"

#define TAG "WATCHDOG_MGR"

// Configuraciones por defecto según el modo
#define CONFIG_MODE_TIMEOUT_MS          30000   // 30 segundos en modo config
#define CONFIG_MODE_FEED_INTERVAL_MS    10000   // 10 segundos
#define CONFIG_MODE_HEALTH_CHECK_MS     15000   // 15 segundos

#define RUNNING_MODE_TIMEOUT_MS         15000   // 15 segundos en modo running
#define RUNNING_MODE_FEED_INTERVAL_MS   5000    // 5 segundos
#define RUNNING_MODE_HEALTH_CHECK_MS    10000   // 10 segundos

#define CRITICAL_MODE_TIMEOUT_MS        8000    // 8 segundos en modo crítico
#define CRITICAL_MODE_FEED_INTERVAL_MS  3000    // 3 segundos
#define CRITICAL_MODE_HEALTH_CHECK_MS   5000    // 5 segundos

#define DEFAULT_MEMORY_THRESHOLD        50000   // 50KB mínimo
#define MAX_REGISTERED_TASKS            10      // Máximo 10 tasks registrados
#define MAX_RESET_REASON_LENGTH         64      // Longitud máxima razón de reset

// Estructura para tasks registrados
typedef struct {
    TaskHandle_t handle;
    char name[16];
    int64_t last_feed_time;
    bool is_active;
} registered_task_t;

// Estructura para actividad de subsistemas
typedef struct {
    int64_t last_activity_time;
    uint32_t activity_count;
} subsystem_activity_t;

// Contexto del Watchdog Manager
typedef struct {
    bool initialized;
    bool twdt_available; // Simplificado: solo saber si está disponible
    watchdog_mode_t current_mode;
    watchdog_health_status_t health_status;
    
    // Configuraciones por modo
    watchdog_config_t configs[3]; // CONFIG, RUNNING, CRITICAL
    
    // Tasks registrados
    registered_task_t registered_tasks[MAX_REGISTERED_TASKS];
    int registered_task_count;
    
    // Actividad de subsistemas
    subsystem_activity_t subsystem_activity[4]; // MEMORY, WIFI, MQTT, TASKS
    
    // Estadísticas
    uint32_t feed_count;
    uint32_t error_count;
    char last_reset_reason[MAX_RESET_REASON_LENGTH];
    
    // Callback y datos de usuario
    watchdog_event_callback_t event_callback;
    void *event_user_data;
    
    // Control de timers
    TimerHandle_t health_check_timer;
    TimerHandle_t feed_timer;
    
    // Mutex para protección
    SemaphoreHandle_t mutex;
    
    // Marcas de tiempo
    int64_t last_health_check;
    int64_t last_feed_time;
    
} watchdog_manager_context_t;

static watchdog_manager_context_t s_watchdog_ctx = {0};

// Inicializar configuraciones por defecto
static void init_default_configs(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    // Modo CONFIG
    ctx->configs[WATCHDOG_MODE_CONFIG] = (watchdog_config_t) {
        .timeout_ms = CONFIG_MODE_TIMEOUT_MS,
        .feed_interval_ms = CONFIG_MODE_FEED_INTERVAL_MS,
        .health_check_interval_ms = CONFIG_MODE_HEALTH_CHECK_MS,
        .memory_threshold_bytes = DEFAULT_MEMORY_THRESHOLD,
        .enable_memory_check = true,
        .enable_wifi_check = false,  // No verificar WiFi en modo config
        .enable_mqtt_check = false,  // No verificar MQTT en modo config
        .enable_task_monitoring = true
    };
    
    // Modo RUNNING
    ctx->configs[WATCHDOG_MODE_RUNNING] = (watchdog_config_t) {
        .timeout_ms = RUNNING_MODE_TIMEOUT_MS,
        .feed_interval_ms = RUNNING_MODE_FEED_INTERVAL_MS,
        .health_check_interval_ms = RUNNING_MODE_HEALTH_CHECK_MS,
        .memory_threshold_bytes = DEFAULT_MEMORY_THRESHOLD,
        .enable_memory_check = true,
        .enable_wifi_check = true,
        .enable_mqtt_check = true,
        .enable_task_monitoring = true
    };
    
    // Modo CRITICAL
    ctx->configs[WATCHDOG_MODE_CRITICAL] = (watchdog_config_t) {
        .timeout_ms = CRITICAL_MODE_TIMEOUT_MS,
        .feed_interval_ms = CRITICAL_MODE_FEED_INTERVAL_MS,
        .health_check_interval_ms = CRITICAL_MODE_HEALTH_CHECK_MS,
        .memory_threshold_bytes = DEFAULT_MEMORY_THRESHOLD * 2, // Umbral más alto en crítico
        .enable_memory_check = true,
        .enable_wifi_check = true,
        .enable_mqtt_check = true,
        .enable_task_monitoring = true
    };
}

// Timer callback para verificación de salud
static void health_check_timer_callback(TimerHandle_t xTimer) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return;
    }
    
    watchdog_health_status_t status = watchdog_manager_check_system_health();
    
    if (status != ctx->health_status) {
        ctx->health_status = status;
        
        if (ctx->event_callback) {
            ctx->event_callback(status, WATCHDOG_CHECK_TASKS, ctx->event_user_data);
        }
        
        if (status == WATCHDOG_HEALTH_CRITICAL || status == WATCHDOG_HEALTH_ERROR) {
            ESP_LOGE(TAG, "System health critical - initiating recovery");
            watchdog_manager_force_reset("health_check_critical");
        }
    }
}

// Timer callback para alimentación automática
static void feed_timer_callback(TimerHandle_t xTimer) {
    watchdog_manager_feed();
}

// Verificar salud de memoria - CORREGIDO
static watchdog_health_status_t check_memory_health(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    watchdog_config_t *config = &ctx->configs[ctx->current_mode];
    
    if (!config->enable_memory_check) {
        return WATCHDOG_HEALTH_GOOD;
    }
    
    size_t free_heap = esp_get_free_heap_size();
    
    // Umbrales más estrictos
    uint32_t critical_threshold = config->memory_threshold_bytes / 3;  // 33% del umbral
    uint32_t warning_threshold = config->memory_threshold_bytes / 2;   // 50% del umbral
    
    if (free_heap < critical_threshold) {
        ESP_LOGE(TAG, "CRITICAL memory: %zu bytes (threshold: %lu)", free_heap, critical_threshold);
        
        // Limpieza de emergencia
        ESP_LOGW(TAG, "Performing emergency memory recovery");
        mqtt_manager_emergency_memory_cleanup();
        
        // Verificar si la limpieza ayudó
        size_t free_after = esp_get_free_heap_size();
        ESP_LOGI(TAG, "Memory after cleanup: %zu bytes (recovered: %d)", 
                free_after, (int)(free_after - free_heap));
        
        if (free_after < critical_threshold) {
            return WATCHDOG_HEALTH_CRITICAL;
        } else {
            return WATCHDOG_HEALTH_WARNING;
        }
        
    } else if (free_heap < warning_threshold) {
        ESP_LOGW(TAG, "Low memory: %zu bytes (threshold: %lu)", free_heap, warning_threshold);
        return WATCHDOG_HEALTH_WARNING;
    }
    
    return WATCHDOG_HEALTH_GOOD;
}

// Verificar salud de WiFi
static watchdog_health_status_t check_wifi_health(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    watchdog_config_t *config = &ctx->configs[ctx->current_mode];
    
    if (!config->enable_wifi_check) {
        return WATCHDOG_HEALTH_GOOD;
    }
    
    if (!wifi_manager_is_connected()) {
        ESP_LOGW(TAG, "WiFi disconnected");
        return WATCHDOG_HEALTH_WARNING;
    }
    
    return WATCHDOG_HEALTH_GOOD;
}

// Verificar salud de MQTT
static watchdog_health_status_t check_mqtt_health(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    watchdog_config_t *config = &ctx->configs[ctx->current_mode];
    
    if (!config->enable_mqtt_check) {
        return WATCHDOG_HEALTH_GOOD;
    }
    
    if (!mqtt_manager_is_connected()) {
        ESP_LOGW(TAG, "MQTT disconnected");
        return WATCHDOG_HEALTH_WARNING;
    }
    
    return WATCHDOG_HEALTH_GOOD;
}

// Verificar salud de tasks
static watchdog_health_status_t check_tasks_health(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    watchdog_config_t *config = &ctx->configs[ctx->current_mode];
    
    if (!config->enable_task_monitoring) {
        return WATCHDOG_HEALTH_GOOD;
    }
    
    int64_t current_time = esp_timer_get_time() / 1000;
    uint32_t max_timeout = config->timeout_ms * 2; // Permitir el doble del timeout
    
    for (int i = 0; i < ctx->registered_task_count; i++) {
        if (ctx->registered_tasks[i].is_active) {
            int64_t time_since_feed = current_time - ctx->registered_tasks[i].last_feed_time;
            
            if (time_since_feed > max_timeout) {
                ESP_LOGE(TAG, "Task %s hasn't fed watchdog in %lld ms",
                        ctx->registered_tasks[i].name, time_since_feed);
                return WATCHDOG_HEALTH_CRITICAL;
            }
        }
    }
    
    return WATCHDOG_HEALTH_GOOD;
}

// SIMPLIFICADO: Verificar si TWDT está disponible
static bool check_twdt_available(void) {
    esp_err_t ret = esp_task_wdt_status(NULL);
    return (ret != ESP_ERR_INVALID_STATE);
}

// SIMPLIFICADO: Configurar TWDT
static esp_err_t setup_twdt(watchdog_manager_context_t *ctx) {
    esp_task_wdt_config_t twdt_config = {
        .timeout_ms = ctx->configs[ctx->current_mode].timeout_ms,
        .idle_core_mask = (1 << CONFIG_FREERTOS_NUMBER_OF_CORES) - 1,
        .trigger_panic = false
    };
    
    // Intentar reconfigurar primero
    esp_err_t ret = esp_task_wdt_reconfigure(&twdt_config);
    if (ret == ESP_OK) {
        ESP_LOGI(TAG, "TWDT reconfigured successfully");
        return ESP_OK;
    }
    
    // Si falla, intentar inicializar
    ret = esp_task_wdt_init(&twdt_config);
    if (ret == ESP_OK) {
        ESP_LOGI(TAG, "TWDT initialized successfully");
        return ESP_OK;
    }
    
    ESP_LOGW(TAG, "Could not configure TWDT: %s", esp_err_to_name(ret));
    return ret;
}

// Implementación de funciones públicas
esp_err_t watchdog_manager_init(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (ctx->initialized) {
        ESP_LOGW(TAG, "Already initialized");
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Initializing Watchdog Manager");
    
    // Limpiar contexto
    memset(ctx, 0, sizeof(watchdog_manager_context_t));
    
    // Verificar si TWDT está disponible
    ctx->twdt_available = check_twdt_available();
    ESP_LOGI(TAG, "TWDT available: %s", ctx->twdt_available ? "YES" : "NO");
    
    // Crear mutex
    ctx->mutex = xSemaphoreCreateMutex();
    if (ctx->mutex == NULL) {
        ESP_LOGE(TAG, "Failed to create mutex");
        return ESP_ERR_NO_MEM;
    }
    
    // Inicializar configuraciones
    init_default_configs();
    ctx->current_mode = WATCHDOG_MODE_CONFIG;
    ctx->health_status = WATCHDOG_HEALTH_GOOD;
    
    // Configurar TWDT si está disponible
    if (ctx->twdt_available) {
        esp_err_t ret = setup_twdt(ctx);
        if (ret != ESP_OK) {
            ESP_LOGW(TAG, "TWDT setup failed, continuing without it");
            ctx->twdt_available = false;
        }
    }
    
    // Crear timers
    ctx->health_check_timer = xTimerCreate(
        "health_check",
        pdMS_TO_TICKS(ctx->configs[ctx->current_mode].health_check_interval_ms),
        pdTRUE,
        NULL,
        health_check_timer_callback
    );
    
    ctx->feed_timer = xTimerCreate(
        "feed_timer",
        pdMS_TO_TICKS(ctx->configs[ctx->current_mode].feed_interval_ms),
        pdTRUE,
        NULL,
        feed_timer_callback
    );
    
    if (ctx->health_check_timer == NULL || ctx->feed_timer == NULL) {
        ESP_LOGE(TAG, "Failed to create timers");
        if (ctx->mutex) {
            vSemaphoreDelete(ctx->mutex);
        }
        return ESP_ERR_NO_MEM;
    }
    
    // Inicializar marcas de tiempo
    ctx->last_health_check = esp_timer_get_time() / 1000;
    ctx->last_feed_time = ctx->last_health_check;
    
    // Inicializar actividad de subsistemas
    for (int i = 0; i < 4; i++) {
        ctx->subsystem_activity[i].last_activity_time = ctx->last_health_check;
        ctx->subsystem_activity[i].activity_count = 0;
    }
    
    // Marcar como inicializado
    ctx->initialized = true;
    
    // Iniciar timers
    xTimerStart(ctx->health_check_timer, 0);
    xTimerStart(ctx->feed_timer, 0);
    
    ESP_LOGI(TAG, "Watchdog Manager initialized successfully");
    return ESP_OK;
}

esp_err_t watchdog_manager_deinit(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    ESP_LOGI(TAG, "Deinitializing Watchdog Manager");
    
    // Detener timers
    if (ctx->health_check_timer) {
        xTimerStop(ctx->health_check_timer, portMAX_DELAY);
        xTimerDelete(ctx->health_check_timer, portMAX_DELAY);
    }
    
    if (ctx->feed_timer) {
        xTimerStop(ctx->feed_timer, portMAX_DELAY);
        xTimerDelete(ctx->feed_timer, portMAX_DELAY);
    }
    
    // Desregistrar todos los tasks
    for (int i = 0; i < ctx->registered_task_count; i++) {
        if (ctx->registered_tasks[i].is_active && ctx->twdt_available) {
            esp_task_wdt_delete(ctx->registered_tasks[i].handle);
        }
    }
    
    // Liberar mutex
    if (ctx->mutex) {
        vSemaphoreDelete(ctx->mutex);
    }
    
    // Limpiar contexto
    memset(ctx, 0, sizeof(watchdog_manager_context_t));
    
    ESP_LOGI(TAG, "Watchdog Manager deinitialized");
    return ESP_OK;
}

esp_err_t watchdog_manager_set_mode(watchdog_mode_t mode) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (mode < WATCHDOG_MODE_CONFIG || mode > WATCHDOG_MODE_CRITICAL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        if (mode != ctx->current_mode) {
            ESP_LOGI(TAG, "Changing mode from %d to %d", ctx->current_mode, mode);
            
            ctx->current_mode = mode;
            
            // Reconfigurar TWDT si está disponible
            if (ctx->twdt_available) {
                esp_task_wdt_config_t twdt_config = {
                    .timeout_ms = ctx->configs[mode].timeout_ms,
                    .idle_core_mask = (1 << CONFIG_FREERTOS_NUMBER_OF_CORES) - 1,
                    .trigger_panic = false
                };
                
                esp_task_wdt_reconfigure(&twdt_config);
            }
            
            // Actualizar intervalos de timers
            xTimerChangePeriod(ctx->health_check_timer,
                              pdMS_TO_TICKS(ctx->configs[mode].health_check_interval_ms),
                              portMAX_DELAY);
                              
            xTimerChangePeriod(ctx->feed_timer,
                              pdMS_TO_TICKS(ctx->configs[mode].feed_interval_ms),
                              portMAX_DELAY);
        }
        
        xSemaphoreGive(ctx->mutex);
    }
    
    return ESP_OK;
}

watchdog_mode_t watchdog_manager_get_mode(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return WATCHDOG_MODE_CONFIG;
    }
    
    return ctx->current_mode;
}

esp_err_t watchdog_manager_register_task(TaskHandle_t task_handle, const char *task_name) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (task_name == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    // Si no se proporciona handle, usar el task actual
    if (task_handle == NULL) {
        task_handle = xTaskGetCurrentTaskHandle();
    }
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        // Verificar si ya está registrado
        for (int i = 0; i < ctx->registered_task_count; i++) {
            if (ctx->registered_tasks[i].handle == task_handle) {
                xSemaphoreGive(ctx->mutex);
                return ESP_ERR_INVALID_STATE; // Ya registrado
            }
        }
        
        // Verificar espacio disponible
        if (ctx->registered_task_count >= MAX_REGISTERED_TASKS) {
            xSemaphoreGive(ctx->mutex);
            return ESP_ERR_NO_MEM;
        }
        
        // Registrar en TWDT si está disponible
        if (ctx->twdt_available) {
            esp_err_t ret = esp_task_wdt_add(task_handle);
            if (ret != ESP_OK && ret != ESP_ERR_INVALID_ARG) {
                xSemaphoreGive(ctx->mutex);
                ESP_LOGW(TAG, "Failed to register task in TWDT: %s", esp_err_to_name(ret));
                return ret;
            }
        }
        
        // Agregar a la lista
        int index = ctx->registered_task_count;
        ctx->registered_tasks[index].handle = task_handle;
        strncpy(ctx->registered_tasks[index].name, task_name, sizeof(ctx->registered_tasks[index].name) - 1);
        ctx->registered_tasks[index].name[sizeof(ctx->registered_tasks[index].name) - 1] = '\0';
        ctx->registered_tasks[index].last_feed_time = esp_timer_get_time() / 1000;
        ctx->registered_tasks[index].is_active = true;
        
        ctx->registered_task_count++;
        
        ESP_LOGI(TAG, "Registered task: %s (%d/%d)", task_name, ctx->registered_task_count, MAX_REGISTERED_TASKS);
        
        xSemaphoreGive(ctx->mutex);
    }
    
    return ESP_OK;
}

esp_err_t watchdog_manager_unregister_task(TaskHandle_t task_handle) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    // Si no se proporciona handle, usar el task actual
    if (task_handle == NULL) {
        task_handle = xTaskGetCurrentTaskHandle();
    }
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        // Buscar el task
        for (int i = 0; i < ctx->registered_task_count; i++) {
            if (ctx->registered_tasks[i].handle == task_handle) {
                // Desregistrar del TWDT si está disponible
                if (ctx->twdt_available) {
                    esp_task_wdt_delete(task_handle);
                }
                
                // Marcar como inactivo
                ctx->registered_tasks[i].is_active = false;
                
                ESP_LOGI(TAG, "Unregistered task: %s", ctx->registered_tasks[i].name);
                
                xSemaphoreGive(ctx->mutex);
                return ESP_OK;
            }
        }
        
        xSemaphoreGive(ctx->mutex);
        return ESP_ERR_NOT_FOUND;
    }
    
    return ESP_FAIL;
}

esp_err_t watchdog_manager_feed(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    TaskHandle_t current_task = xTaskGetCurrentTaskHandle();
    
    // Solo alimentar TWDT si está disponible y el task está registrado
    if (ctx->twdt_available) {
        bool task_is_registered = false;
        for (int i = 0; i < ctx->registered_task_count; i++) {
            if (ctx->registered_tasks[i].handle == current_task && ctx->registered_tasks[i].is_active) {
                task_is_registered = true;
                break;
            }
        }
        
        if (task_is_registered) {
            esp_err_t ret = esp_task_wdt_reset();
            if (ret != ESP_OK) {
                ESP_LOGD(TAG, "Failed to feed TWDT: %s", esp_err_to_name(ret));
            }
        }
    }
    
    // Actualizar estadísticas
    ctx->feed_count++;
    ctx->last_feed_time = esp_timer_get_time() / 1000;
    
    // Actualizar tiempo del task actual si está registrado
    for (int i = 0; i < ctx->registered_task_count; i++) {
        if (ctx->registered_tasks[i].handle == current_task && ctx->registered_tasks[i].is_active) {
            ctx->registered_tasks[i].last_feed_time = ctx->last_feed_time;
            break;
        }
    }
    
    return ESP_OK;
}

esp_err_t watchdog_manager_report_activity(watchdog_check_type_t check_type) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (check_type < WATCHDOG_CHECK_MEMORY || check_type > WATCHDOG_CHECK_TASKS) {
        return ESP_ERR_INVALID_ARG;
    }
    
    ctx->subsystem_activity[check_type].last_activity_time = esp_timer_get_time() / 1000;
    ctx->subsystem_activity[check_type].activity_count++;
    
    return ESP_OK;
}

void watchdog_manager_force_reset(const char *reason) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (reason) {
        strncpy(ctx->last_reset_reason, reason, sizeof(ctx->last_reset_reason) - 1);
        ctx->last_reset_reason[sizeof(ctx->last_reset_reason) - 1] = '\0';
        ESP_LOGE(TAG, "Forcing system reset: %s", reason);
    } else {
        ESP_LOGE(TAG, "Forcing system reset: unknown reason");
    }
    
    // Dar tiempo para que el log se escriba
    vTaskDelay(pdMS_TO_TICKS(100));
    
    // Reset del sistema
    esp_restart();
}

watchdog_health_status_t watchdog_manager_get_health_status(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return WATCHDOG_HEALTH_ERROR;
    }
    
    return ctx->health_status;
}

watchdog_health_status_t watchdog_manager_check_system_health(void) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return WATCHDOG_HEALTH_ERROR;
    }
    
    watchdog_health_status_t worst_status = WATCHDOG_HEALTH_GOOD;
    
    // Verificar memoria
    watchdog_health_status_t memory_status = check_memory_health();
    if (memory_status > worst_status) {
        worst_status = memory_status;
    }
    
    // Verificar WiFi
    watchdog_health_status_t wifi_status = check_wifi_health();
    if (wifi_status > worst_status) {
        worst_status = wifi_status;
    }
    
    // Verificar MQTT
    watchdog_health_status_t mqtt_status = check_mqtt_health();
    if (mqtt_status > worst_status) {
        worst_status = mqtt_status;
    }
    
    // Verificar tasks
    watchdog_health_status_t tasks_status = check_tasks_health();
    if (tasks_status > worst_status) {
        worst_status = tasks_status;
    }
    
    ctx->health_status = worst_status;
    ctx->last_health_check = esp_timer_get_time() / 1000;
    
    return worst_status;
}

esp_err_t watchdog_manager_get_stats(uint32_t *feed_count, uint32_t *error_count, const char **last_reset_reason) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (feed_count) {
        *feed_count = ctx->feed_count;
    }
    
    if (error_count) {
        *error_count = ctx->error_count;
    }
    
    if (last_reset_reason) {
        *last_reset_reason = ctx->last_reset_reason;
    }
    
    return ESP_OK;
}

esp_err_t watchdog_manager_set_event_callback(watchdog_event_callback_t callback, void *user_data) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized) {
        return ESP_ERR_INVALID_STATE;
    }
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        ctx->event_callback = callback;
        ctx->event_user_data = user_data;
        xSemaphoreGive(ctx->mutex);
    }
    
    return ESP_OK;
}

esp_err_t watchdog_manager_set_config(watchdog_mode_t mode, const watchdog_config_t *config) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized || !config) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (mode < WATCHDOG_MODE_CONFIG || mode > WATCHDOG_MODE_CRITICAL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (xSemaphoreTake(ctx->mutex, portMAX_DELAY) == pdTRUE) {
        ctx->configs[mode] = *config;
        
        // Si es el modo actual, aplicar cambios
        if (mode == ctx->current_mode) {
            watchdog_manager_set_mode(mode); // Reconfigurar
        }
        
        xSemaphoreGive(ctx->mutex);
    }
    
    return ESP_OK;
}

esp_err_t watchdog_manager_get_config(watchdog_mode_t mode, watchdog_config_t *config) {
    watchdog_manager_context_t *ctx = &s_watchdog_ctx;
    
    if (!ctx->initialized || !config) {
        return ESP_ERR_INVALID_ARG;
    }
    
    if (mode < WATCHDOG_MODE_CONFIG || mode > WATCHDOG_MODE_CRITICAL) {
        return ESP_ERR_INVALID_ARG;
    }
    
    *config = ctx->configs[mode];
    return ESP_OK;
}