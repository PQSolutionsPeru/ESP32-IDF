idf_component_register(
    SRCS "watchdog_manager.c"
    INCLUDE_DIRS "include"
    REQUIRES 
        freertos
        esp_system
        esp_timer
        esp_common
        log
        esp_hw_support
        wifi_manager
        mqtt_manager
    PRIV_REQUIRES
        heap
)