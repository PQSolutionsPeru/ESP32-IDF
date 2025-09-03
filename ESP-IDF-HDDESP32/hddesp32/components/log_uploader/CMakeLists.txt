idf_component_register(SRCS "log_uploader.c"
                       INCLUDE_DIRS "."
                       REQUIRES esp_http_client log_storage)
