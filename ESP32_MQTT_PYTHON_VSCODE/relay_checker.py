import machine
import time
import network

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


# Función para imprimir el estado
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
            else:
                print("NO: Desconectado (no energizado)")
        # Si el estado de pin13 cambió
        if pin13 != estado_anterior_pin13:
            if pin13 == 1:
                print("NC: Conectado (energizado)")
            else:
                print("NC: Desconectado (no energizado)")
    return pin12, pin13

while True:
    # Leer el estado actual de los pines
    estado_actual_pin12 = pin12.value()
    estado_actual_pin13 = pin13.value()

    # Llamar a la función imprimir_estado para revisar e imprimir el estado actual
    estado_anterior_pin12, estado_anterior_pin13 = imprimir_estado(estado_actual_pin12, estado_actual_pin13, estado_anterior_pin12, estado_anterior_pin13)

    # Esperar un tiempo antes de volver a leer los pines
    time.sleep(0.1)

