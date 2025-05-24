idf_component_register(
    SRCS "time_manager.c"
    INCLUDE_DIRS "include"
    REQUIRES esp_timer esp_netif esp_wifi nvs_flash wifi_manager mqtt_manager
)