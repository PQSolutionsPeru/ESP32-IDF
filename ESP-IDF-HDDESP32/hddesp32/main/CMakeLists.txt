idf_component_register(SRCS "hddesp32_main.c"
                    INCLUDE_DIRS "."
                    REQUIRES config_manager esp32_id_manager wifi_manager wifi_captive_portal mqtt_manager nvs_flash esp_wifi time_manager watchdog_manager relay_manager)