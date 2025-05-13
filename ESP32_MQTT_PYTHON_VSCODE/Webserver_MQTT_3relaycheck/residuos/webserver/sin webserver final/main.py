import urequests
import utime
import json
from wifi_manager import WiFiManager
from mqtt_manager import MQTTManager
from relay_manager import RelayManager
from performance_monitor import check_performance
from watchdog_manager import WatchdogManager  # Importa el nuevo módulo de watchdog
from machine import Timer
import gc

# Configuration
RELAY_PINS = [32, 33, 25]
RELAY_NAMES = {
    32: "Alarma",
    33: "Problema",
    25: "Supervision"
}

# Managers
wifi_manager = WiFiManager()
mqtt_manager = MQTTManager()
relay_manager = RelayManager()
watchdog_manager = WatchdogManager()  # Instancia del manager de watchdog

# Memory Management
memory_timer = Timer(-1)

def memory_watchdog(timer):
    gc.collect()
    free_memory = gc.mem_free()
    print(f"Memoria libre actual: {free_memory} bytes")

memory_timer.init(period=60000, mode=Timer.PERIODIC, callback=memory_watchdog)

def get_current_datetime():
    try:
        response = urequests.get("http://worldtimeapi.org/api/timezone/America/Lima")
        data = response.json()
        current_datetime = data["datetime"]
        return current_datetime
    except Exception as e:
        print("Error al obtener la fecha y hora:", e)
        return None

def relay_callback(pin, pin_num):
    current_datetime = get_current_datetime()
    if current_datetime:
        message = {
            "date_time": current_datetime[:19],  # YYYY-MM-DD HH:MM:SS format
            "name": RELAY_NAMES.get(pin_num, "Unknown Relay"),
            "status": "DISC" if pin.value() else "OK"
        }
        mqtt_manager.publish_event(f"EMPRESA_TEST/{mqtt_manager.MQTT_CLIENT_ID}/eventos", json.dumps(message))

# main.py
def main():
    wifi_manager.connect_wifi()
    mqtt_manager.ensure_client()  # Asegúrate de que el cliente MQTT se inicializa aquí
    for relay_pin in RELAY_PINS:
        pin = relay_manager.setup_relay(relay_pin, relay_callback)
    while True:
        utime.sleep(10)
        wifi_manager.check_connection()
        watchdog_manager.feed()  # Usa el manager de watchdog para alimentar el WDT

if __name__ == "__main__":
    main()

