from config.base_config import BaseConfig

class WiFiConfig(BaseConfig):
    def __init__(self):
        self.DEFAULT_CONFIG = {
            "connect_timeout": 20000,   # 20 segundos
            "max_retries": 3,
            "retry_delay": 2000,        # 2 segundos
            "check_interval": 60000,    # 1 minuto
            "ssid": None,
            "password": None
        }
        super().__init__('wifi_config.json')
        self._init_default_config()

    def _init_default_config(self):
        if not self.config:
            self.config = self.DEFAULT_CONFIG.copy()
            self._save_config()

    def save_credentials(self, ssid, password):
        self.config['ssid'] = ssid
        self.config['password'] = password
        return self._save_config()

    def get_credentials(self):
        return {
            "ssid": self.config.get('ssid'),
            "password": self.config.get('password')
        }