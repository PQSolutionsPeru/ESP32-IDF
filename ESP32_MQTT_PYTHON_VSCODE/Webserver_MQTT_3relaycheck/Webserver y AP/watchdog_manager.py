from machine import WDT

class WatchdogManager:
    def __init__(self, timeout=120000):
        try:
            # Configura el watchdog con un timeout de 120 segundos por defecto
            self.watchdog = WDT(timeout=timeout)
        except Exception as e:
            print(f"Error al configurar el watchdog: {e}")
            self.watchdog = None
    
    def feed(self):
        try:
            if self.watchdog:
                # Alimenta al watchdog para resetear el timer y evitar un reset del sistema
                self.watchdog.feed()
        except Exception as e:
            print(f"Error al alimentar el watchdog: {e}")