import machine
import time
import network
import ntptime
from umqtt.robust import MQTTClient

# Configuración de red WiFi
SSID = "PABLO-2.4G"
PASSWORD = "47009410"

# Configuración del broker MQTT
MQTT_BROKER = "node02.myqtthub.com"
MQTT_PORT = 8883
MQTT_CLIENT_ID = "ESP32-PQ1"
MQTT_USER = "ESP32-1"
MQTT_PASSWORD = "esp32"

# Estado de la conexión WiFi
wifi_connected = False

# Estado de la sincronización de tiempo
time_synced = False

# Variable global para el cliente MQTT
client = None

# Configurar pines y pines de interrupción para relés
RELAY_PINS = [12, 13]
RELAY_STATES = {}

def conectar_mqtt():
    global client
    if not client or not client.isconnected():
        client = MQTTClient(MQTT_CLIENT_ID.encode('utf-8'), MQTT_BROKER, MQTT_PORT, user=MQTT_USER.encode('utf-8'), password=MQTT_PASSWORD.encode('utf-8'), ssl=True)
        client.connect()

def enviar_evento_al_broker(pin, estado):
    conectar_mqtt()  # Asegura que el cliente esté conectado
    tipo_evento = f"{pin}_activado" if estado else f"{pin}_desactivado"
    topic = f"EMPRESA_TEST/{MQTT_CLIENT_ID}/eventos"
    client.publish(topic, tipo_evento, qos=1)
    print(f"Evento enviado al broker MQTT: {tipo_evento}")

def relay_callback(pin):
    global RELAY_STATES
    estado = pin.value()
    pin_id = pin.id()
    if RELAY_STATES.get(pin_id) != estado:
        enviar_evento_al_broker(pin_id, estado)
        RELAY_STATES[pin_id] = estado

def conectar_wifi():
    global wifi_connected, time_synced
    if not wifi_connected:
        sta_if = network.WLAN(network.STA_IF)
        if not sta_if.isconnected():
            print("Conectando a WiFi...")
            sta_if.active(True)
            sta_if.connect(SSID, PASSWORD)
            while not sta_if.isconnected():
                pass
            print("Conectado a WiFi")
            wifi_connected = True
            if not time_synced:
                ntptime.settime()  # Sincronizar el tiempo una vez conectado
                time_synced = True
    return wifi_connected

def setup_relays():
    for pin_num in RELAY_PINS:
        pin = machine.Pin(pin_num, machine.Pin.IN, machine.Pin.PULL_UP)
        pin.irq(trigger=machine.Pin.IRQ_RISING | machine.Pin.IRQ_FALLING, handler=relay_callback)
        RELAY_STATES[pin_num] = None

def main():
    setup_relays()
    while True:
        conectar_wifi()  # Conecta y sincroniza la hora inicial
        time.sleep(60)  # Reduce la frecuencia de verificación a cada minuto

if __name__ == "__main__":
    main()
