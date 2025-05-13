from machine import Pin
import time
import uasyncio as asyncio

class RelayManager:
    def __init__(self):
        self.relays = {}
        self.relay_states = {}
        self.last_trigger_time = {}

    def setup_relay(self, pin_num, callback):
        pin = Pin(pin_num, Pin.IN, Pin.PULL_UP)
        self.relays[pin_num] = pin
        self.relay_states[pin_num] = pin.value()
        print(f"Setting up relay for pin {pin_num}")
        pin.irq(trigger=Pin.IRQ_RISING | Pin.IRQ_FALLING, handler=lambda pin: asyncio.create_task(self.debounce(pin_num, pin, callback)))
        return pin

    async def debounce(self, pin_num, pin, callback):
        current_time = time.ticks_ms()
        if current_time - self.last_trigger_time.get(pin_num, 0) > 300:
            if self.relay_states[pin_num] != pin.value():
                self.relay_states[pin_num] = pin.value()
                await callback(pin, pin_num)
                self.last_trigger_time[pin_num] = current_time
                print(f"Debounce triggered for pin {pin_num}, value {pin.value()}")
