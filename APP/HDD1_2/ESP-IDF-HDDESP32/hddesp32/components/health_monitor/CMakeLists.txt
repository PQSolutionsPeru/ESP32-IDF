idf_component_register(
    SRCS "health_monitor.c"
    INCLUDE_DIRS "include"
    REQUIRES mqtt_manager config_manager log_uploader
)
