from machine import Pin
import time
from log_manager import LogManager

class RelayManager:
    def __init__(self, logger):
        self.relays = {}
        self.relay_states = {}
        self.last_trigger_time = {}
        self.logger = logger

    def setup_relay(self, pin_num, callback):
        pin = Pin(pin_num, Pin.IN, Pin.PULL_UP)
        self.relays[pin_num] = pin
        self.relay_states[pin_num] = pin.value()
        pin.irq(trigger=Pin.IRQ_RISING | Pin.IRQ_FALLING, handler=lambda p: self.debounce(pin_num, p, callback))
        return pin

    def debounce(self, pin_num, pin, callback):
        current_time = time.ticks_ms()
        if current_time - self.last_trigger_time.get(pin_num, 0) > 300 and self.relay_states[pin_num] != pin.value():
            self.relay_states[pin_num] = pin.value()
            callback(pin, pin_num)
            self.logger.write_log(f"Estado del relevador {pin_num} cambiado a {pin.value()}")
            print(f"Estado del relevador {pin_num} cambiado a {pin.value()}")
            self.last_trigger_time[pin_num] = current_time

