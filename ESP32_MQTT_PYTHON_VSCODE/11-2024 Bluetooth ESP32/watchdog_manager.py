from machine import WDT, Timer
import utime
import machine
import gc
from config.watchdog_config import WatchdogConfig

class WatchdogManager:
    def __init__(self):
        """Inicializa el watchdog con configuración basada en modo"""
        self.config = WatchdogConfig()
        self.watchdog = WDT(timeout=self.config.get_current_timeout())
        
        # Control de tiempos
        self.last_feed = utime.ticks_ms()
        self.last_relay_check = utime.ticks_ms()
        self.last_mqtt_check = utime.ticks_ms()
        
        # Métricas del sistema
        self.system_metrics = {
            'memory_drops': 0,
            'relay_failures': 0,
            'mqtt_failures': 0
        }
        
        # Control de resets
        self.reset_count = 0
        self.last_reset = 0
        
        print("[WATCHDOG] Iniciado con configuración para sistema crítico")

    def feed(self):
        """Alimenta al watchdog según el modo actual"""
        try:
            current_time = utime.ticks_ms()
            
            if utime.ticks_diff(current_time, self.last_feed) >= self.config.DEFAULT_CONFIG['feed_interval']:
                # Verificar memoria siempre
                if not self.check_memory_health():
                    print("[WATCHDOG] Memoria crítica - Forzando reset")
                    self.force_reset("low_memory")
                    return False
                
                # Verificaciones según modo
                if self.config.should_check_relays():
                    if not self.check_relay_health():
                        print("[WATCHDOG] Error en relays - Forzando reset")
                        self.force_reset("relay_error")
                        return False
                        
                if self.config.should_check_mqtt():
                    if not self.check_mqtt_health():
                        print("[WATCHDOG] Error en MQTT - Forzando reset")
                        self.force_reset("mqtt_error")
                        return False
                
                self.watchdog.feed()
                self.last_feed = current_time
                return True
                
        except Exception as e:
            print(f"[WATCHDOG] Error en feed: {e}")
            self.force_reset("feed_error")
            return False

    def set_mode(self, mode):
        """Cambia el modo de operación"""
        if self.config.set_mode(mode):
            print(f"[WATCHDOG] Modo cambiado a: {mode}")
            # Actualizar timeout del watchdog
            self.watchdog = WDT(timeout=self.config.get_current_timeout())
            return True
        return False

    def check_relay_health(self):
        """Verifica salud de relays solo si está habilitado"""
        if not self.config.should_check_relays():
            return True
            
        current_time = utime.ticks_ms()
        interval = self.config.get_relay_check_interval()
        
        if utime.ticks_diff(current_time, self.last_relay_check) >= interval:
            print("[WATCHDOG] Timeout en verificación de relays")
            return False
            
        return True

    def check_mqtt_health(self):
        """Verifica salud de MQTT solo si está habilitado"""
        if not self.config.should_check_mqtt():
            return True
            
        current_time = utime.ticks_ms()
        timeout = self.config.get_mqtt_timeout()
        
        if utime.ticks_diff(current_time, self.last_mqtt_check) >= timeout:
            print("[WATCHDOG] Timeout en comunicaciones MQTT")
            return False
            
        return True

    def check_memory_health(self):
        """Verifica salud de memoria"""
        if gc.mem_free() < self.config.DEFAULT_CONFIG['reset']['memory_threshold']:
            gc.collect()
            utime.sleep_ms(100)
            return gc.mem_free() >= self.config.DEFAULT_CONFIG['reset']['memory_threshold']
        return True

    def force_reset(self, reason="watchdog_timeout"):
        """Fuerza reset con logging"""
        try:
            print(f"[WATCHDOG] Forzando reset: {reason}")
            current_time = utime.ticks_ms()
            
            # Verificar ventana de resets
            if utime.ticks_diff(current_time, self.last_reset) < self.config.DEFAULT_CONFIG['reset']['window']:
                self.reset_count += 1
                if self.reset_count >= self.config.DEFAULT_CONFIG['reset']['max_count']:
                    print("[WATCHDOG] Demasiados resets - Hard reset")
                    machine.reset()
            else:
                self.reset_count = 1
                
            self.last_reset = current_time
            machine.reset()
            
        except Exception as e:
            print(f"[WATCHDOG] Error en force_reset: {e}")
            machine.reset()

    def update_relay_check(self):
        """Actualiza timestamp de verificación de relays"""
        self.last_relay_check = utime.ticks_ms()

    def update_mqtt_check(self):
        """Actualiza timestamp de verificación MQTT"""
        self.last_mqtt_check = utime.ticks_ms()