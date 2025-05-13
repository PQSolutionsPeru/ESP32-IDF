# Importar las librerías necesarias
import network
import time
from machine import Pin

# Configuración de los pines GPIO
NC_pin = Pin(12, Pin.IN)
NO_pin = Pin(13, Pin.IN)

# Conectar a la red WiFi
print("Conectando a WiFi", end="")
sta_if = network.WLAN(network.STA_IF)
sta_if.active(True)
sta_if.connect('PABLO-2.4G', '47009410')
while not sta_if.isconnected():
    print(".", end="")
    time.sleep(0.1)    
print(" ¡Conectado!")

# Funciones para encender y apagar el LED
def estatus_NC(pin):
    print("El pin {} está activado".format(pin))

def estatus_des_NC(pin):
    print("El pin {} está desactivado".format(pin))

# Variable para almacenar el estado anterior del relé
prev_relay_state = False

# Bucle principal
while True:
    # Leer el estado del relé
    relay_state = (NC_pin.value() == 1)    # Si el contacto NC está abierto (activo), el relé está desactivado
    
    # Comprobar si el estado del relé ha cambiado
    if relay_state != prev_relay_state:
        # Actualizar el estado del relé anterior
        prev_relay_state = relay_state
        
        # Encender o apagar el LED según el estado del relé
        if relay_state:
            estatus_NC(NC_pin)
        else:
            estatus_des_NC(NC_pin)
    
    # Pequeño retraso entre las lecturas
    time.sleep(0.1)