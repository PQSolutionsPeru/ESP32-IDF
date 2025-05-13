from machine import Pin, Timer
import utime
import random
from _thread import start_new_thread

# Definir pines de salida para las entradas auxiliares (lado derecho del ESP32)
aux_1 = (Pin(23, Pin.OUT, value=1), Pin(22, Pin.OUT, value=1))  # Auxiliar 1
aux_2 = (Pin(19, Pin.OUT, value=1), Pin(18, Pin.OUT, value=1))  # Auxiliar 2
aux_3 = (Pin(5, Pin.OUT, value=1), Pin(17, Pin.OUT, value=1))   # Auxiliar 3
aux_list = [aux_1, aux_2, aux_3]

def random_aux_activation():
    """ Activa un par auxiliar al azar para simular un botón presionado """
    while True:
        random_aux = random.choice(aux_list)
        index = aux_list.index(random_aux)
        random_aux[0].value(0)  # IN a LOW
        print(f"Aux_{index + 1} activado")
        utime.sleep(1)  # Mantener activado por 5 segundos
        random_aux[0].value(1)  # IN a HIGH
        print(f"Aux_{index + 1} desactivado")
        utime.sleep(2)  # Esperar 10 segundos antes de activar otro    

# Iniciar hilo para la simulación de presión de botón
start_new_thread(random_aux_activation, ())
