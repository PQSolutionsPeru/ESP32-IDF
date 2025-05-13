import utime
import json
from watchdog import feed_watchdog, restart_device
from wifi_manager import WiFiManager
from mqtt_manager import MQTTManager
from relay_manager import RelayManager
from log_manager import LogManager

SSID = "PABLO-2.4G"
PASSWORD = "47009410"
MQTT_BROKER = "node02.myqtthub.com"
MQTT_PORT = 8883
MQTT_CLIENT_ID = "ESP32-PQ1"
MQTT_USER = "ESP32-1"
MQTT_PASSWORD = "esp32"
RELAY_PINS = [32, 33, 25]
RELAY_NAMES = {"32": "Alarma", "33": "Problema", "25": "Supervision"}

log_manager = LogManager("logs.txt", max_size_kb=100)
wifi_manager = WiFiManager(SSID, PASSWORD, log_manager)
mqtt_manager = MQTTManager(MQTT_BROKER, MQTT_PORT, MQTT_CLIENT_ID, MQTT_USER, MQTT_PASSWORD, log_manager)
relay_manager = RelayManager(log_manager)

def relay_callback(pin, pin_num):
    current_datetime = wifi_manager.get_current_time()
    if current_datetime:
        message = {
            "date_time": current_datetime,
            "name": RELAY_NAMES.get(str(pin_num), "Error al obtener nombre del relevador"),
            "status": "DESC" if pin.value() else "OK"
        }
        mqtt_manager.publish_event(f"EMPRESA_TEST/{MQTT_CLIENT_ID}/eventos", json.dumps(message))

def main():
    try:
        wifi_manager.connect_wifi()
        for relay_pin in RELAY_PINS:
            relay_manager.setup_relay(relay_pin, relay_callback)
        while True:
            feed_watchdog()
            utime.sleep(5)  # Reduced sleep time
            wifi_manager.check_connection()
    except Exception as e:
        log_manager.write_log(f"Error en el bucle principal: {str(e)}")
        restart_device()

if __name__ == "__main__":
    main()
