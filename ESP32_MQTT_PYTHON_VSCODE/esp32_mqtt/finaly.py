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
EMPRESA = "EMPRESA_TEST"  # Nombre de la empresa
SUBTOPIC = "eventos"  # Subtopic para los eventos

# Definir los pines 12 y 13 como entrada
pin12 = machine.Pin(12, machine.Pin.IN)
pin13 = machine.Pin(13, machine.Pin.IN)

# Variable para controlar el último estado de relés
ultimo_estado_relays = None

# Variable global para el cliente MQTT
client = None

# Función para obtener la fecha y hora actual formateada en GMT-5
def obtener_fecha_hora():
    # Obtener la fecha y hora actual del RTC
    rtc = machine.RTC()
    year, month, day, weekday, hour, minute, second, _ = rtc.datetime()

    # Aplicar ajuste de GMT-5 (restar 5 horas)
    hour = (hour - 5) % 24  # Restar 5 horas y asegurarse de que el resultado esté en formato de 24 horas

    # Formatear la fecha y hora ajustada
    fecha_hora = "{:02d}-{:02d}-{:02d} {:02d}:{:02d}:{:02d}".format(year, month, day, hour, minute, second)
    return fecha_hora

# Función para enviar eventos al broker MQTT con QoS 1 y manejar reintentos
def enviar_evento_al_broker(tipo_evento):
    global client
    try:
        mqtt_topic = f"{EMPRESA}/{MQTT_CLIENT_ID}/{SUBTOPIC}"
        client.publish(mqtt_topic, tipo_evento, qos=1)  # Enviar evento al broker MQTT con QoS 1
        print(f"Evento enviado al broker MQTT: {tipo_evento}")
    except Exception as e:
        print(f"Fallo al enviar el evento al broker MQTT: {tipo_evento} - Error: {e}")

# Función para monitorear el estado de NC y NO y generar eventos según las condiciones
def monitorear_relay():
    global ultimo_estado_relays
    estado_actual_pin12 = pin12.value()
    estado_actual_pin13 = pin13.value()

    # Detectar cambios en el estado de los relés
    if (estado_actual_pin12, estado_actual_pin13) != ultimo_estado_relays:
        if estado_actual_pin12 == estado_actual_pin13:
            if estado_actual_pin12 == 0:  # Ambos relés desconectados
                tipo_evento = "relay_desconectado"
            else:
                tipo_evento = "relay_mal_estado"
        else:
            if estado_actual_pin12 == 1:  # NO energizado
                tipo_evento = "NO_On"
            else:  # NC energizado
                tipo_evento = "NC_On"
        
        # Actualizar el último estado de relés
        ultimo_estado_relays = (estado_actual_pin12, estado_actual_pin13)
        
        # Enviar el evento al broker MQTT
        enviar_evento_al_broker(tipo_evento)

# Función para conectar a la red WiFi con reconexión automática y sincronización de tiempo
def conectar_wifi():
    print("Conectando a WiFi", end="")
    sta_if = network.WLAN(network.STA_IF)
    sta_if.active(True)
    sta_if.connect(SSID, PASSWORD)
    
    while not sta_if.isconnected():
        print(".", end="")
        time.sleep(0.5)
    
    print("\nConectado a WiFi")

    # Una vez conectado al WiFi, sincronizar el tiempo con NTP
    print("Sincronizando tiempo...")
    ntptime.settime()  # Sincronizar el RTC con la hora de red NTP

    # Obtener fecha y hora actual y ajustar a GMT-5
    fecha_hora = obtener_fecha_hora()
    print("Fecha y hora actual (GMT-5):", fecha_hora)

    return sta_if

# Función para conectar al broker MQTT con reconexión automática
def conectar_mqtt():
    global client
    client = MQTTClient(MQTT_CLIENT_ID.encode('utf-8'), MQTT_BROKER, MQTT_PORT, user=MQTT_USER.encode('utf-8'), password=MQTT_PASSWORD.encode('utf-8'), ssl=True)
    client.connect()
    print("Conectado al broker MQTT")

# Función principal
def main():  
    # Conectar a WiFi
    wifi_interface = conectar_wifi()
    print("WiFi conectada")

    # Conectar al broker MQTT (con reconexión automática)
    conectar_mqtt()
    
    # Bucle principal
    while True:
        # Monitorear el estado de NC y NO
        monitorear_relay()
        
        # Esperar un tiempo antes de la próxima verificación
        time.sleep(0.1)

# Ejecutar la función principal al iniciar el script
if __name__ == "__main__":
    main()

