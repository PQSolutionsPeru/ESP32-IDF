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
RELAY_NAMES = {32: "Alarma", 33: "Problema", 25: "Supervision"}

# Managers
wifi_manager = WiFiManager()
mqtt_manager = MQTTManager(wifi_manager)
relay_manager = RelayManager()
watchdog_manager = WatchdogManager()

cached_datetime = None
last_datetime_update = utime.ticks_ms()
memory_report_enabled = True  # Cambiar a False para desactivar los informes de memoria

def get_current_datetime():
    global cached_datetime, last_datetime_update
    current_ticks = utime.ticks_ms()
    if utime.ticks_diff(current_ticks, last_datetime_update) > 600000 or cached_datetime is None:  # 10 minutos
        update_datetime()
    return cached_datetime

def update_datetime():
    global cached_datetime, last_datetime_update
    try:
        cached_datetime = wifi_manager.get_current_time()
        print(f"Fecha y hora actuales actualizadas a: {cached_datetime}")
    except Exception as e:
        print(f"Error al obtener fecha y hora, usando última conocida o 'unknown': {e}")
        cached_datetime = cached_datetime if cached_datetime else "unknown"
    last_datetime_update = utime.ticks_ms()


def relay_callback(pin, pin_num):
    gc.collect()  # Recolectar basura antes de procesar
    print(f"Callback de relay activado para el pin {pin_num}.")
    wifi_manager.ensure_wifi_connected()  # Verificar conexión WiFi antes de proceder
    current_datetime = get_current_datetime()  # Asegurarse de usar la fecha y hora formateada
    status = "DISC" if pin.value() else "OK"
    message = {
        "date_time": current_datetime,  # Usar la fecha y hora formateada
        "name": RELAY_NAMES.get(pin_num, "Relay Desconocido"),
        "status": status
    }
    print(f"Enviando mensaje MQTT: {message}")
    mqtt_manager.publish_event(f"EMPRESA_TEST/{mqtt_manager.MQTT_CLIENT_ID}/eventos", json.dumps(message))



def main():
    last_memory_report_time = utime.ticks_ms()
    while not wifi_manager.sta_if.isconnected():
        wifi_manager.connect_wifi()
    mqtt_manager.ensure_client()
    for relay_pin in RELAY_PINS:
        pin = relay_manager.setup_relay(relay_pin, relay_callback)
    while True:
        utime.sleep(1)
        watchdog_manager.feed()
        current_time = utime.ticks_ms()
        if memory_report_enabled and utime.ticks_diff(current_time, last_memory_report_time) > 60000:  # 1 minuto
            free_memory = gc.mem_free()
            print(f"Reporte de memoria libre: {free_memory} bytes")
            last_memory_report_time = current_time
            gc.collect()  # Recolección de basura para mantener la memoria limpia

if __name__ == "__main__":
    main()
