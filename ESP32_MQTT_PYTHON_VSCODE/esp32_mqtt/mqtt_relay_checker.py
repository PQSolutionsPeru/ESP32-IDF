import machine
import time
import network
from umqtt.simple import MQTTClient

# Definir los pines 12 y 13 como entrada
pin12 = machine.Pin(12, machine.Pin.IN)
pin13 = machine.Pin(13, machine.Pin.IN)

# Inicializar las variables para guardar el estado anterior de los pines
estado_anterior_pin12 = None
estado_anterior_pin13 = None

# Conectar a la red WiFi
print("Conectando a WiFi", end="")
sta_if = network.WLAN(network.STA_IF)
sta_if.active(True)
sta_if.connect('PABLO-2.4G', '47009410')
while not sta_if.isconnected():
    print(".", end="")
    time.sleep(0.1)
print("Conectado!")

# Función para imprimir el estado y publicar los datos en el broker
def imprimir_estado(pin12, pin13, estado_anterior_pin12, estado_anterior_pin13):
    # Si los estados son iguales
    if pin12 == pin13:
        if pin12 != estado_anterior_pin12 or pin13 != estado_anterior_pin13:
            print("Relay desconectado o malogrado")
    else:
        # Si el estado de pin12 cambió
        if pin12 != estado_anterior_pin12:
            if pin12 == 1:
                print("NO: Conectado (energizado)")
                client.publish(b"topic/relay/NO", b"Conectado")
            else:
                print("NO: Desconectado (no energizado)")
                client.publish(b"topic/relay/NO", b"Desconectado")
        # Si el estado de pin13 cambió
        if pin13 != estado_anterior_pin13:
            if pin13 == 1:
                print("NC: Conectado (energizado)")
                client.publish(b"topic/relay/NC", b"Conectado")
            else:
                print("NC: Desconectado (no energizado)")
                client.publish(b"topic/relay/NC", b"Desconectado")
    return pin12, pin13

# Función para manejar la conexión con el broker
def on_message(topic, message):
    print("Mensaje recibido en el topic:", topic)
    print("Contenido del mensaje:", message)

# Configurar el cliente MQTT y establecer la conexión con el broker
client = MQTTClient("ESP32-PQ1", "node02.myqtthub.com", port=8883, user="ESP32-1", password="Quixy35L4M3J0R", ssl=True)
client.set_callback(on_message)
client.connect()

# Suscribirse a los topics de los relés
client.subscribe(b"topic/relay/NO")
client.subscribe(b"topic/relay/NC")

# Bucle principal
while True:
    # Leer el estado actual de los pines
    estado_actual_pin12 = pin12.value()
    estado_actual_pin13 = pin13.value()

    # Llamar a la función imprimir_estado para revisar e imprimir el estado actual
    estado_anterior_pin12, estado_anterior_pin13 = imprimir_estado(estado_actual_pin12, estado_actual_pin13, estado_anterior_pin12, estado_anterior_pin13)

    # Esperar un tiempo antes de volver a leer los pines
    time.sleep(0.1)

    # Manejar los mensajes entrantes
    client.check_msg()

