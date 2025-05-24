idf_component_register(
    SRCS "mqtt_manager.c" "mqtt_ssl_setup.c"
    INCLUDE_DIRS "include"
    REQUIRES esp_wifi mqtt nvs_flash esp_event config_manager esp32_id_manager wifi_manager time_manager esp_common mbedtls
)

# Configuraciones optimizadas para ESP32 4MB
target_compile_definitions(${COMPONENT_LIB} PRIVATE
    "-DCONFIG_MQTT_TASK_STACK_SIZE=4096"
    "-DCONFIG_MQTT_BUFFER_SIZE=512"
    "-DCONFIG_ESP_SYSTEM_EVENT_TASK_STACK_SIZE=4096"
)