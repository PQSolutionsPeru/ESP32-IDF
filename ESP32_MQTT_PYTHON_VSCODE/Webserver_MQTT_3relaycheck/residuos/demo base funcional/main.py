import urequests
import utime
import json
from wifi_manager import WiFiManager
from mqtt_manager import MQTTManager
from relay_manager import RelayManager

# Configuration
SSID = "PABLO-2.4G"
PASSWORD = "47009410"
MQTT_BROKER = "node02.myqtthub.com"
MQTT_PORT = 8883
MQTT_CLIENT_ID = "ESP32-PQ1"
MQTT_USER = "ESP32-1"
MQTT_PASSWORD = "esp32"
RELAY_PINS = [32, 33, 25]
RELAY_NAMES = {
    32: "Alarma",
    33: "Problema",
    25: "Supervision"
}

# Managers
wifi_manager = WiFiManager(SSID, PASSWORD)
mqtt_manager = MQTTManager(MQTT_BROKER, MQTT_PORT, MQTT_CLIENT_ID, MQTT_USER, MQTT_PASSWORD)
relay_manager = RelayManager()

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
            "date_time": current_datetime[:19],  # Formato YYYY-MM-DD HH:MM:SS
            "name": RELAY_NAMES.get(pin_num, "Unknown Relay"),
            "status": "DISC" if pin.value() else "OK"
        }
        mqtt_manager.publish_event(f"EMPRESA_TEST/{MQTT_CLIENT_ID}/eventos", json.dumps(message))

def main():
    wifi_manager.connect_wifi()
    for relay_pin in RELAY_PINS:
        pin = relay_manager.setup_relay(relay_pin, relay_callback)
    while True:
        utime.sleep(10)  # Tiempo de espera reducido para operaciones más receptivas
        wifi_manager.check_connection()  # Verificar y mantener la conexión WiFi regularmente

if __name__ == "__main__":
    main()
