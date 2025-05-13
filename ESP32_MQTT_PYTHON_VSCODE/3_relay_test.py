from machine import Pin
import utime
import random
from _thread import start_new_thread
import time

# Definir pines de entrada para los relays, configurando según NO y NC
relay_alarma_no = Pin(32, Pin.IN, Pin.PULL_UP)   # Alarma, Normalmente Abierto
relay_supervision_no = Pin(33, Pin.IN, Pin.PULL_UP)  # Supervisión, Normalmente Abierto
relay_problema_nc = Pin(25, Pin.IN, Pin.PULL_UP)  # Problema, Normalmente Cerrado

# Definir pines de salida para las entradas auxiliares (lado derecho del ESP32)
aux_1 = (Pin(23, Pin.OUT, value=1), Pin(22, Pin.OUT, value=1))  # Auxiliar 1
aux_2 = (Pin(19, Pin.OUT, value=1), Pin(18, Pin.OUT, value=1))  # Auxiliar 2
aux_3 = (Pin(5, Pin.OUT, value=1), Pin(17, Pin.OUT, value=1))   # Auxiliar 3
aux_list = [aux_1, aux_2, aux_3]

# Estado anterior de los relays para detectar cambios
estado_anterior = {
    "alarma_no": relay_alarma_no.value(),
    "supervision_no": relay_supervision_no.value(),
    "problema_nc": relay_problema_nc.value(),
}

def check_relay_changes():
    """ Verifica cambios en el estado de los relays y muestra un mensaje """
    while True:
        for relay_id, relay_pin in [
            ("alarma_no", relay_alarma_no),
            ("supervision_no", relay_supervision_no),
            ("problema_nc", relay_problema_nc)
        ]:
            current_state = relay_pin.value()
            if current_state != estado_anterior[relay_id]:
                relay_type = "NC" if "nc" in relay_id else "NO"
                relay_name = relay_id.split('_')[0].upper()
                print(f"{relay_name} RELAY {relay_type}: {'Cerrado' if current_state == 0 else 'Abierto'}")
                estado_anterior[relay_id] = current_state
        utime.sleep(0.1)

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

def exec_time(nombre_funcion):
    start_time = time.ticks_ms()  # Marca de tiempo al inicio
    # Código cuya duración quieres medir
    duration = time.ticks_ms() - start_time  # Calcula la duración
    print(f"Duración de: {nombre_funcion}, {duration}, ms")
    


# Iniciar hilos para la simulación de presión de botón y monitoreo de relays
start_new_thread(random_aux_activation, ())
start_new_thread(check_relay_changes, ())
exec_time(check_relay_changes)