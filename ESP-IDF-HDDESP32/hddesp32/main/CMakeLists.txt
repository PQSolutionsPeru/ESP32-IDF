idf_component_register(SRCS "hddesp32_main.c"
                    INCLUDE_DIRS "."
                    REQUIRES 
                        custom_logging 
                        config_manager 
                        config_processor 
                        connectivity_monitor 
                        esp32_id_manager 
                        wifi_manager 
                        wifi_captive_portal 
                        mqtt_manager 
                        nvs_flash 
                        esp_wifi 
                        time_manager 
                        watchdog_manager 
                        relay_manager 
                        json
                        esp_timer
                        esp_system
                        freertos
                        esp_common
                        driver
                        heap
                        log_storage
                        log_uploader)
