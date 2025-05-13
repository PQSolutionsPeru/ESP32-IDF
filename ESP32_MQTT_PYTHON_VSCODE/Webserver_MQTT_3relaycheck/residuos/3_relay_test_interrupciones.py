from machine import Pin, Timer
import utime

# Definir pines de entrada para los relés, configurando según NO y NC
relay_alarma_no = Pin(32, Pin.IN, Pin.PULL_UP)   # Alarma, Normalmente Abierto
relay_supervision_no = Pin(33, Pin.IN, Pin.PULL_UP)  # Supervisión, Normalmente Abierto
relay_problema_nc = Pin(25, Pin.IN, Pin.PULL_UP)  # Problema, Normalmente Cerrado

# Diccionario para almacenar el estado de los pines de relé
relay_states = {
    relay_alarma_no: None,
    relay_supervision_no: None,
    relay_problema_nc: None
}

# Debouncing: Utilizar un solo temporizador para manejar los rebotes
debounce_timer = Timer(-1)

def make_relay_callback(pin, relay_name, relay_type):
    def callback(timer):
        current_state = pin.value()
        last_state = relay_states[pin]
        if current_state != last_state:
            print(f"{relay_name} RELAY {relay_type}: {'Cerrado' if current_state == 0 else 'Abierto'}")
            relay_states[pin] = current_state
    return callback

def setup_interrupt(pin, relay_name, relay_type):
    pin.irq(trigger=Pin.IRQ_RISING | Pin.IRQ_FALLING, handler=make_relay_callback(pin, relay_name, relay_type))

# Configurar interrupciones para los pines de relay
setup_interrupt(relay_alarma_no, "ALARMA", "NO")
setup_interrupt(relay_supervision_no, "SUPERVISION", "NO")
setup_interrupt(relay_problema_nc, "PROBLEMA", "NC")

# Bucle principal
while True:
    utime.sleep_ms(100)  # Espera para reducir el uso de CPU
