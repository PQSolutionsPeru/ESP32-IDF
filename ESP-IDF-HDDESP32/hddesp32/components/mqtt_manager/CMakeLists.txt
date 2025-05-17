idf_component_register(
    SRCS "mqtt_manager.c"
    INCLUDE_DIRS "include"
    REQUIRES esp_wifi mqtt nvs_flash esp_event config_manager esp32_id_manager wifi_manager time_manager
)