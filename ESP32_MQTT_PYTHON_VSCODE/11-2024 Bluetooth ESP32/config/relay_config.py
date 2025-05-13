from config.base_config import BaseConfig

class RelayConfig(BaseConfig):
    def __init__(self):
        self.DEFAULT_CONFIG = {
            "pins": {
                "32": "Alarma",    # NO
                "33": "Problema",  # NO
                "25": "Supervision" # NO
            },
            "debounce_time": 100,       # 100ms
            "min_report_interval": 2000  # 2 segundo
        }
        super().__init__('relay_config.json')
        self._init_default_config()

    def _init_default_config(self):
        if not self.config:
            self.config = self.DEFAULT_CONFIG.copy()
            self._save_config()

    def get_relay_pins(self):
        return {int(k): v for k, v in self.config.get('pins', self.DEFAULT_CONFIG['pins']).items()}

    def get_debounce_time(self):
        return self.config.get('debounce_time', self.DEFAULT_CONFIG['debounce_time'])

    def get_min_report_interval(self):
        return self.config.get('min_report_interval', self.DEFAULT_CONFIG['min_report_interval'])