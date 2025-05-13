from machine import Pin, Timer
import time
import utime
from config.relay_config import RelayConfig

class RelayManager:
    def __init__(self):
        """Inicializa el gestor de relays con configuración"""
        self.config = RelayConfig()
        self.relays = {}  # Pines físicos
        self.relay_states = {}  # Estados actuales
        self.relay_names = {}  # Mapeo pin -> nombre
        self.relay_callbacks = {}  # Callbacks por pin
        self.last_trigger_time = {}  # Control de debounce
        self.pending_updates = {}  # Actualizaciones pendientes
        self.processing_updates = False  # Flag para evitar procesamiento concurrente
        self.update_timer = None
        
        print("[RELAY] Gestor iniciado con configuración cargada")
        self._init_update_timer()

    def _init_update_timer(self):
        """Inicializa el timer para procesar actualizaciones"""
        try:
            if self.update_timer:
                self.update_timer.deinit()
            self.update_timer = Timer(-1)
            self.update_timer.init(period=50, mode=Timer.PERIODIC, callback=self._process_updates)
        except Exception as e:
            print(f"[RELAY] Error iniciando timer: {e}")

    def _get_logical_state(self, pin_value, relay_name):
        """Lee el estado directo del GPIO - LOW = OK (contacto cerrado), HIGH = DISC (contacto abierto)"""
        return "OK" if pin_value == 0 else "DISC"

    def _process_updates(self, _):
        """Procesa actualizaciones pendientes usando un único flujo para todos los relés"""
        try:
            # Evitar procesamiento concurrente
            if self.processing_updates:
                return
                
            self.processing_updates = True
            
            updates_to_process = self.pending_updates.copy()
            self.pending_updates.clear()
            
            for pin_num, pin_value in updates_to_process.items():
                if pin_num in self.relay_callbacks:
                    try:
                        current_time = time.ticks_ms()
                        relay_name = self.relay_names.get(pin_num)
                        logical_state = self._get_logical_state(pin_value, relay_name)
                        
                        # Verificar si el estado ha cambiado realmente
                        current_state = self.relay_states.get(pin_num)
                        
                        # Verificar tiempo desde la última actualización
                        time_diff = time.ticks_diff(current_time, self.last_trigger_time.get(pin_num, 0))
                        min_interval = self.config.get_min_report_interval()
                        
                        # Solo procesamos si:
                        # 1. El estado ha cambiado 
                        # 2. Ha pasado suficiente tiempo desde la última actualización
                        if current_state != logical_state and time_diff > min_interval:
                            # Actualizar timestamp y estado antes del callback
                            self.last_trigger_time[pin_num] = current_time
                            self.relay_states[pin_num] = logical_state
                            
                            # Registrar la actualización
                            print(f"[RELAY] Procesando cambio - Pin {pin_num} ({relay_name}): {current_state} -> {logical_state}")
                            
                            # Ejecutar callback una sola vez
                            self.relay_callbacks[pin_num](self.relays[pin_num], pin_num)
                        elif current_state != logical_state:
                            # Si el estado cambió pero no ha pasado suficiente tiempo, reencolar
                            print(f"[RELAY] Debounce activo para {relay_name}, esperando {min_interval-time_diff}ms más")
                            self.pending_updates[pin_num] = pin_value
                                    
                    except Exception as e:
                        print(f"[RELAY] Error procesando callback para pin {pin_num}: {e}")
                        import sys
                        sys.print_exception(e)
            
            self.processing_updates = False
                
        except Exception as e:
            self.processing_updates = False
            print(f"[RELAY] Error en process_updates: {e}")
            import sys
            sys.print_exception(e)

    def setup_relay(self, pin_num, callback):
        """Configura un relay con manejo de debounce mejorado"""
        try:
            # Configurar pin con Pull-Up
            pin = Pin(pin_num, Pin.IN, Pin.PULL_UP)
            
            # Obtener nombre del relay
            pin_config = self.config.get_relay_pins()
            relay_name = pin_config.get(pin_num)
            
            # Guardar configuración
            self.relays[pin_num] = pin
            self.relay_names[pin_num] = relay_name
            self.relay_callbacks[pin_num] = callback
            
            def irq_handler(p):
                try:
                    pin_value = p.value()
                    logical_state = self._get_logical_state(pin_value, relay_name)
                    
                    # Solo encolar si el estado cambió
                    current_state = self.relay_states.get(pin_num)
                    if current_state != logical_state:
                        # Encolar para procesamiento centralizado
                        self.pending_updates[pin_num] = pin_value
                        print(f"[RELAY] Estado detectado - Pin {pin_num} ({relay_name}): {current_state} -> {logical_state}")
                except Exception as e:
                    print(f"[RELAY] Error en IRQ del pin {pin_num}: {e}")
            
            # Configurar interrupción
            pin.irq(trigger=Pin.IRQ_RISING | Pin.IRQ_FALLING, handler=irq_handler)
            
            # Estado inicial
            initial_value = pin.value()
            initial_state = self._get_logical_state(initial_value, relay_name)
            self.relay_states[pin_num] = initial_state
            self.last_trigger_time[pin_num] = time.ticks_ms()
            
            print(f"[RELAY] Pin {pin_num} ({relay_name}) configurado exitosamente")
            print(f"[RELAY] Estado inicial de {relay_name}: {initial_state}")
            
            # Forzar callback inicial para reportar estado inicial
            callback(pin, pin_num)
            
            return pin
            
        except Exception as e:
            print(f"[RELAY] Error crítico configurando pin {pin_num}: {e}")
            import sys
            sys.print_exception(e)
            return None

    def get_relay_state(self, pin_num):
        """Obtiene el estado lógico actual de un relay"""
        try:
            if pin_num in self.relays:
                pin_value = self.relays[pin_num].value()
                relay_name = self.relay_names.get(pin_num)
                return self._get_logical_state(pin_value, relay_name)
            return None
        except Exception as e:
            print(f"[RELAY] Error obteniendo estado del pin {pin_num}: {e}")
            return None

    def get_all_states(self):
        """Obtiene el estado actual de todos los relays con nombres"""
        try:
            states = {}
            for pin_num, pin in self.relays.items():
                try:
                    relay_name = self.relay_names.get(pin_num)
                    if relay_name:
                        pin_value = pin.value()
                        logical_state = self._get_logical_state(pin_value, relay_name)
                        states[relay_name] = {
                            'name': relay_name,
                            'status': logical_state,
                            'timestamp': {
                                'value': utime.ticks_ms(),
                                'type': 'realtime'
                            }
                        }
                except Exception as e:
                    print(f"[RELAY] Error obteniendo estado del pin {pin_num}: {e}")
                    continue
                    
            return states
        except Exception as e:
            print(f"[RELAY] Error en get_all_states: {e}")
            return {}

    def cleanup(self):
        """Limpia recursos y desactiva interrupciones"""
        try:
            if self.update_timer:
                self.update_timer.deinit()
                self.update_timer = None
            
            for pin_num, pin in self.relays.items():
                try:
                    pin.irq(trigger=0, handler=None)
                except:
                    pass
            
            self.relays.clear()
            self.relay_states.clear()
            self.relay_callbacks.clear()
            self.last_trigger_time.clear()
            self.pending_updates.clear()
            
            print("[RELAY] Limpieza completada")
            return True
        except Exception as e:
            print(f"[RELAY] Error en limpieza: {e}")
            return False