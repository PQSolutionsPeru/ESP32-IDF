import json
import os

class BaseConfig:
    def __init__(self, config_file):
        self.config_file = config_file
        self.config = {}
        self._load_config()

    def _load_config(self):
        try:
            if self.config_file in os.listdir():
                with open(self.config_file, 'r') as f:
                    self.config = json.load(f)
                    print(f"[CONFIG] Configuración cargada: {self.config_file}")
        except Exception as e:
            print(f"[CONFIG] Error cargando {self.config_file}: {e}")

    def _save_config(self):
        try:
            # Guardar con archivo temporal
            temp_file = self.config_file + '.tmp'
            with open(temp_file, 'w') as f:
                json.dump(self.config, f)
            os.rename(temp_file, self.config_file)
            return True
        except Exception as e:
            print(f"[CONFIG] Error guardando {self.config_file}: {e}")
            return False