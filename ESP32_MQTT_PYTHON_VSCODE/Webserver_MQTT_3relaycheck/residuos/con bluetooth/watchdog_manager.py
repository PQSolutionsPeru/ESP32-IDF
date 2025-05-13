from machine import WDT

class WatchdogManager:
    def __init__(self, timeout=120000):
        # Configura el watchdog con un timeout de 120 segundos por defecto
        self.watchdog = WDT(timeout=timeout)
    
    def feed(self):
        # Alimenta al watchdog para resetear el timer y evitar un reset del sistema
        self.watchdog.feed()
