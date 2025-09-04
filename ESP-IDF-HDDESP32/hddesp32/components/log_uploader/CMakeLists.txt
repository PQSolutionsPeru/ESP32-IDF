idf_component_register(SRCS "log_uploader.c"
                    INCLUDE_DIRS "."
                    REQUIRES esp_http_client
                             esp_timer
                             freertos
                             esp32_id_manager
                             wifi_manager
                             mqtt_manager
                             connectivity_monitor
                             log_storage)