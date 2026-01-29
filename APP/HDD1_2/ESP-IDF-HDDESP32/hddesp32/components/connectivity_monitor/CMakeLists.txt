idf_component_register(SRCS "connectivity_monitor.c"
                    INCLUDE_DIRS "include"
                    REQUIRES esp_system esp_timer freertos lwip custom_logging wifi_manager config_manager mqtt_manager nvs_flash)