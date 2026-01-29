idf_component_register(
    SRCS "config_processor.c"
    INCLUDE_DIRS "include"
    REQUIRES "json" "config_manager" "mqtt_manager" "relay_manager"
)