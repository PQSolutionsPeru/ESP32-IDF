idf_component_register(
    SRCS "mqtt_manager.c" "mqtt_ssl_setup.c"
    INCLUDE_DIRS "include"
    REQUIRES esp_wifi mqtt nvs_flash esp_event config_manager esp32_id_manager wifi_manager time_manager connectivity_monitor esp_common mbedtls esp_timer custom_logging
)

# Configuraciones optimizadas para ESP32 4MB - ESP-IDF v5.4.1
target_compile_definitions(${COMPONENT_LIB} PRIVATE
    "-DCONFIG_MQTT_TASK_STACK_SIZE=4096"
    "-DCONFIG_MQTT_BUFFER_SIZE=512"
    "-DCONFIG_ESP_SYSTEM_EVENT_TASK_STACK_SIZE=4096"
    "-DESP32_4MB_OPTIMIZED=1"
)

# Disable overly strict format-truncation warnings for MQTT component
target_compile_options(${COMPONENT_LIB} PRIVATE
    "-Wno-format-truncation"
    "-Wno-error=format-truncation"
)