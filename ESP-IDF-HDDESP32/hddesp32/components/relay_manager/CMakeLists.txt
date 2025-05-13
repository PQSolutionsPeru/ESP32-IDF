idf_component_register(
    SRCS "relay_manager.c"
    INCLUDE_DIRS "include"
    REQUIRES driver freertos esp_timer nvs_flash log mqtt_manager config_manager esp32_id_manager
)