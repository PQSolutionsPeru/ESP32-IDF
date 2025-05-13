import machine
import time
import network
import ntptime
import ujson
from umqtt.robust import MQTTClient

# Datos de la empresa y del ESP32
empresa = "PQS"
id_esp32 = "ESP32-PQ1"

# Definir los pines 12 y 13 como entrada
pin12 = machine.Pin(12, machine.Pin.IN)
pin13 = machine.Pin(13, machine.Pin.IN)

# Variables para controlar los eventos
eventos = set()  # Conjunto para almacenar eventos únicos

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

# Función para registrar un evento y almacenarlo en memoria local
def registrar_evento(tipo_evento):
    fecha_hora = obtener_fecha_hora()
    evento = {"tipo": tipo_evento, "fecha_hora": fecha_hora}
    eventos.add(ujson.dumps(evento))  # Almacenar como JSON en el conjunto de eventos
    print(f"Evento registrado: {tipo_evento} - {fecha_hora}")

# Función para enviar eventos al broker MQTT
def enviar_eventos_al_broker(client):
    while eventos:
        evento = eventos.pop()
        client.publish(b"topic/eventos", evento)  # Enviar evento al broker MQTT
        print(f"Evento enviado al broker MQTT: {evento}")

# Función para conectar a la red WiFi con reconexión automática y sincronización de tiempo
def conectar_wifi():
    print("Conectando a WiFi", end="")
    sta_if = network.WLAN(network.STA_IF)
    sta_if.active(True)
    sta_if.connect('PABLO-2.4G', '47009410')
    
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
    client = MQTTClient("ESP32-PQ1", "node02.myqtthub.com", port=8883, user="ESP32-1", password="Quixy35L4M3J0R", ssl=True)
    client.set_callback(lambda topic, msg: print(f"Mensaje recibido en el topic: {topic}, Contenido del mensaje: {msg}"))
    
    while True:
        try:
            client.connect()
            print("Conectado al broker MQTT")
            return client
        except Exception as e:
            print("Error al conectar al broker MQTT:", e)
            time.sleep(5)

# Función para monitorear el estado de NC y NO y generar eventos según las condiciones
def monitorear_relay(estado_anterior):
    estado_actual_pin12 = pin12.value()
    estado_actual_pin13 = pin13.value()
    
    if estado_actual_pin12 == estado_actual_pin13:
        if estado_actual_pin12 == 0:  # Ambos relés desconectados
            registrar_evento("relay_desconectado")
        else:
            registrar_evento("relay_mal_estado")
    else:
        if estado_actual_pin12 == 1:  # NO energizado
            registrar_evento("NO_On")
        else:  # NC energizado
            registrar_evento("NC_On")
    
    return (estado_actual_pin12, estado_actual_pin13)

# Función para imprimir eventos en formato JSON
def imprimir_eventos_formato_json():
    while eventos:
        evento = eventos.pop()
        print(f"Evento en formato JSON: {evento}")

# Función para imprimir eventos en formato de texto plano
def imprimir_eventos_texto_plano():
    while eventos:
        evento = eventos.pop()
        evento_dict = ujson.loads(evento)
        print(f"Evento: {evento_dict['tipo']} - {evento_dict['fecha_hora']}")

# Función principal
def main():  
    # Conectar a WiFi
    wifi_interface = conectar_wifi()
    registrar_evento("wifi_conectado")

    # Registro de evento al iniciar el código
    registrar_evento("inicio_codigo")
    
    # Conectar al broker MQTT (con reconexión automática)
    client = conectar_mqtt()
    registrar_evento("mqtt_conectado")
    
    # Estado inicial de NC y NO
    estado_anterior = (pin12.value(), pin13.value())
    
    # Bucle principal
    while True:
        # Monitorear el estado de NC y NO
        estado_anterior = monitorear_relay(estado_anterior)
        
        # Enviar eventos al broker MQTT
        enviar_eventos_al_broker(client)
        
        # Esperar un tiempo antes de la próxima verificación
        time.sleep(0.1)

# Ejecutar la función principal al iniciar el script
if __name__ == "__main__":
    main()
