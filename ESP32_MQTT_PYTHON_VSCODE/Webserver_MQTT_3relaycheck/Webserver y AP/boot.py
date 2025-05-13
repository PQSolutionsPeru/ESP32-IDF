import gc
import uos
from machine import Pin, reset
import network
import usocket as socket
import ujson
from esp32 import NVS

# Configuración de pines
led = Pin(2, Pin.OUT)

# Configuración de red
sta_if = network.WLAN(network.STA_IF)
sta_if.active(True)

# Inicializar NVS
nvs = NVS("credentials")

def read_credentials():
    try:
        ssid = nvs.get_blob("ssid").decode()
        password = nvs.get_blob("password").decode()
        return ssid, password
    except:
        return None, None

def connect_wifi(ssid, password):
    sta_if.connect(ssid, password)
    while not sta_if.isconnected():
        pass
    print("Conectado a la red WiFi")
    print("Dirección IP:", sta_if.ifconfig()[0])

def start_ap_mode():
    ap_if = network.WLAN(network.AP_IF)
    ap_if.active(True)
    ap_if.config(essid="ESP32-AP", password="12345678")
    while not ap_if.active():
        pass
    print("Modo AP iniciado")

def main():
    # Leer credenciales de NVS
    ssid, password = read_credentials()

    if ssid and password:
        # Conectar a la red WiFi
        connect_wifi(ssid, password)
    else:
        # Iniciar modo AP
        start_ap_mode()

    # Importar el módulo del servidor web
    import web_server

    # Iniciar el servidor web
    web_server.main()

if __name__ == "__main__":
    main()