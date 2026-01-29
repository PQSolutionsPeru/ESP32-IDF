idf_component_register(
    SRCS "time_manager.c"
    INCLUDE_DIRS "include"
    REQUIRES esp_netif esp_timer lwip
    PRIV_REQUIRES wifi_manager
)