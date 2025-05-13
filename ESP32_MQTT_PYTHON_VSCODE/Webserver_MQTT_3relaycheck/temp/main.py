import uasyncio as asyncio
import gc
from wifi_manager import WiFiManager
from mqtt_manager import MQTTManager
from relay_manager import RelayManager
from system_monitor import SystemMonitor
from machine import Pin, WDT
from watchdog import feed_watchdog, restart_device
import json

RELAY_PINS = [32, 33, 25]
RELAY_NAMES = {"32": "Alarma", "33": "Problema", "25": "Supervision"}

wdt = WDT(timeout=30000)  # Ajuste el timeout según sea necesario

wifi_manager = WiFiManager()
mqtt_manager = MQTTManager()
relay_manager = RelayManager()
system_monitor = SystemMonitor()

async def relay_callback(pin, pin_num):
    try:
        print(f"Relay callback triggered for pin {pin_num}")
        current_datetime = await wifi_manager.get_current_time()
        if current_datetime:
            message = {
                "date_time": current_datetime,
                "name": RELAY_NAMES[str(pin_num)],
                "status": "DESC" if pin.value() else "OK"
            }
            print(f"Publishing message: {message}")
            mqtt_manager.publish_event(f"EMPRESA_TEST/{mqtt_manager.client_id}/eventos", json.dumps(message))
    except Exception as e:
        print("Exception in relay_callback:", e)


async def main():
    while True:
        wdt.feed()
        await wifi_manager.connect_wifi()
        for relay_pin in RELAY_PINS:
            relay_manager.setup_relay(relay_pin, relay_callback)
        while True:
            wdt.feed()
            system_monitor.monitor_system()
            gc.collect()
            await asyncio.sleep(5)
            await wifi_manager.check_connection()

if __name__ == "__main__":
    loop = asyncio.get_event_loop()
    loop.run_until_complete(main())
