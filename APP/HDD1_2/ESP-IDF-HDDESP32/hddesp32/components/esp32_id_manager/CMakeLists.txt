idf_component_register(SRCS "esp32_id_manager.c"
                    INCLUDE_DIRS "include"
                    REQUIRES config_manager esp_system nvs_flash)