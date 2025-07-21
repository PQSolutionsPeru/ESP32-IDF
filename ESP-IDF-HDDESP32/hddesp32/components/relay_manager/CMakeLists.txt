idf_component_register(
    SRCS "relay_manager.c"
    INCLUDE_DIRS "include"
    REQUIRES 
        driver
        freertos
        esp_timer
        nvs_flash
        json
        config_manager
        custom_logging
    PRIV_REQUIRES
        esp_common
        log
        time_manager
)