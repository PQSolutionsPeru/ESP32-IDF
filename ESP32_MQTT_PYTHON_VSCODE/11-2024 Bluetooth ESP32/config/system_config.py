class SystemConfig:
    @staticmethod
    def print_all_configs():
        configs = {
            "Watchdog": WatchdogConfig(),
            "MQTT": MQTTConfig(),
            "WiFi": WiFiConfig(),
            "Relay": RelayConfig()
        }
        
        print("\n=== Configuración Actual del Sistema ===")
        for name, config in configs.items():
            print(f"\n{name}:")
            for key, value in config.config.items():
                print(f"  {key}: {value}")
        print("=======================================\n")