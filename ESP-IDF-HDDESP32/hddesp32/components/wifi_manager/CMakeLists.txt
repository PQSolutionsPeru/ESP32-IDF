idf_component_register(
    SRCS "wifi_manager.c"
    INCLUDE_DIRS "include"
    REQUIRES esp_wifi nvs_flash esp_event config_manager esp32_id_manager
    PRIV_REQUIRES esp_timer
)