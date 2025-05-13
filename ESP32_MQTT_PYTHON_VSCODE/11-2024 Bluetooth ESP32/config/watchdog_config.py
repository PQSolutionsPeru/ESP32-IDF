from config.base_config import BaseConfig

class WatchdogConfig(BaseConfig):
    def __init__(self):
        self.DEFAULT_CONFIG = {
            # Timeouts generales
            "timeout": 30000,          # 30 segundos
            "feed_interval": 15000,    # 15 segundos
            
            # Timeouts específicos por modo
            "config_mode": {
                "timeout": 300000,     # 5 minutos durante configuración
                "relay_check": False,  # No verificar relays durante configuración
                "mqtt_check": False    # No verificar MQTT durante configuración
            },
            "running_mode": {
                "timeout": 30000,      # 30 segundos en operación normal
                "relay_check": True,   # Verificar relays en operación
                "relay_check_interval": 10000,  # 10 segundos
                "mqtt_check": True,    # Verificar MQTT en operación
                "mqtt_timeout": 60000  # 1 minuto timeout MQTT
            },
            
            # Configuración de resets
            "reset": {
                "window": 300000,      # 5 minutos
                "max_count": 3,        # máximo 3 resets
                "memory_threshold": 15000  # 15KB mínimo
            }
        }
        super().__init__('watchdog_config.json')
        self._init_default_config()
        self.current_mode = "config_mode"  # Iniciar en modo configuración

    def _init_default_config(self):
        if not self.config:
            self.config = self.DEFAULT_CONFIG.copy()
            self._save_config()

    def set_mode(self, mode):
        """Cambia el modo de operación del watchdog"""
        if mode in ["config_mode", "running_mode"]:
            self.current_mode = mode
            return True
        return False

    def should_check_relays(self):
        """Determina si se deben verificar los relays según el modo"""
        mode_config = self.config.get(self.current_mode, {})
        return mode_config.get('relay_check', False)

    def should_check_mqtt(self):
        """Determina si se debe verificar MQTT según el modo"""
        mode_config = self.config.get(self.current_mode, {})
        return mode_config.get('mqtt_check', False)

    def get_current_timeout(self):
        """Obtiene el timeout según el modo actual"""
        mode_config = self.config.get(self.current_mode, {})
        return mode_config.get('timeout', self.config['timeout'])

    def get_relay_check_interval(self):
        """Obtiene el intervalo de verificación de relays"""
        mode_config = self.config.get(self.current_mode, {})
        return mode_config.get('relay_check_interval', 10000)

    def get_mqtt_timeout(self):
        """Obtiene el timeout de MQTT"""
        mode_config = self.config.get(self.current_mode, {})
        return mode_config.get('mqtt_timeout', 60000)