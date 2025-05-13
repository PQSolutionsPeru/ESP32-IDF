import urequests
import utime
import json
from wifi_manager import WiFiManager
from mqtt_manager import MQTTManager
from relay_manager import RelayManager
from watchdog_manager import WatchdogManager
import gc

# Configuración
RELAY_PINS = [32, 33, 25]
RELAY_NAMES = {32: "Alarma", 33: "Problema", 25: "Supervisión"}

# Managers
wifi_manager = WiFiManager()
mqtt_manager = MQTTManager()
relay_manager = RelayManager()
watchdog_manager = WatchdogManager()

def get_current_datetime():
    try:
        print("Obteniendo fecha y hora actual desde worldtimeapi.org...")
        response = urequests.get("http://worldtimeapi.org/api/timezone/America/Lima")
        data = response.json()
        current_datetime = data['datetime']
        response.close()
        print(f"Fecha y hora actuales obtenidas correctamente: {current_datetime}")
        return current_datetime
    except Exception as e:
        print(f"Error al obtener la fecha y hora, se utiliza un valor predeterminado: {e}")
        return "unknown"

def relay_callback(pin, pin_num):
    print(f"Callback de relay activado para el pin {pin_num}.")
    current_datetime = get_current_datetime()
    if current_datetime != "unknown":
        message = {
            "date_time": current_datetime[:19],
            "name": RELAY_NAMES.get(pin_num, "Relay Desconocido"),
            "status": "DISC" if pin.value() else "OK"
        }
        print(f"Enviando mensaje MQTT: {message}")
        mqtt_manager.publish_event(f"EMPRESA_TEST/{mqtt_manager.MQTT_CLIENT_ID}/eventos", json.dumps(message))
    else:
        print("No se envía mensaje MQTT debido a la falta de fecha y hora válida.")

def main():
    wifi_manager.connect_wifi()
    mqtt_manager.ensure_client()
    for relay_pin in RELAY_PINS:
        pin = relay_manager.setup_relay(relay_pin, relay_callback)
    last_feed_time = utime.ticks_ms()
    while True:
        current_time = utime.ticks_ms()
        if utime.ticks_diff(current_time, last_feed_time) > 5000:
            watchdog_manager.feed()
            print("Watchdog alimentado.")
            last_feed_time = current_time

        if not wifi_manager.sta_if.isconnected():
            print("Revisando conexión WiFi...")
            wifi_manager.check_connection()
        utime.sleep(1)

if __name__ == "__main__":
    main()
