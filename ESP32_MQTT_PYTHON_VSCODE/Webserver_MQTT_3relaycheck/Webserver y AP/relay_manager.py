from machine import Pin
import time
import gc

class RelayManager:
    def __init__(self):
        self.relays = {}
        self.relay_states = {}
        self.last_trigger_time = {}

    def setup_relay(self, pin_num, callback):
        try:
            pin = Pin(pin_num, Pin.IN, Pin.PULL_UP)
            self.relays[pin_num] = pin
            self.relay_states[pin_num] = pin.value()
            pin.irq(trigger=Pin.IRQ_RISING | Pin.IRQ_FALLING, handler=lambda p: self.debounce(pin_num, p, callback))
            return pin
        except Exception as e:
            print(f"Error al configurar el relé: {e}")
            return None

    def debounce(self, pin_num, pin, callback):
        try:
            current_time = time.ticks_ms()
            if current_time - self.last_trigger_time.get(pin_num, 0) > 300 and self.relay_states[pin_num] != pin.value():  # 300 ms debounce period
                self.relay_states[pin_num] = pin.value()  # Actualizar el estado del relay
                callback(pin, pin_num)
                self.last_trigger_time[pin_num] = current_time
        except Exception as e:
            print(f"Error en el debounce del relé: {e}")
        finally:
            gc.collect()  # Recolectar basura después de procesar