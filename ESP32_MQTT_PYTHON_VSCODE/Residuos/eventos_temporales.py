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

# Función para obtener la fecha y hora actual formateada
def obtener_fecha_hora():
    now = time.localtime()
    return f"{now[0]}-{now[1]}-{now[2]} {now[3]}:{now[4]}:{now[5]}"

# Función para registrar un evento y almacenarlo en memoria local
def registrar_evento(tipo_evento):
    fecha_hora = obtener_fecha_hora()
    evento = {"tipo": tipo_evento, "fecha_hora": fecha_hora}
    eventos.add(evento)  # Almacenar evento en el conjunto de eventos
    print(f"Evento registrado: {tipo_evento} - {fecha_hora}")

# Función para conectar a la red WiFi con reconexión automática
def conectar_wifi():
    print("Conectando a WiFi", end="")
    sta_if = network.WLAN(network.STA_IF)
    sta_if.active(True)
    sta_if.connect('nombre_de_red', 'contraseña_de_red')  # Cambiar por tu red WiFi y contraseña
    
    while not sta_if.isconnected():
        print(".", end="")
        time.sleep(0.5)
    
    print("\nConectado a WiFi")
    return sta_if

# Función para verificar y ajustar la hora del ESP32 con NTP
def verificar_y_ajustar_hora():
    try:
        ntptime.settime()  # Obtener la hora actual desde un servidor NTP
        print("Hora ajustada correctamente")
    except Exception as e:
        print("Error al ajustar la hora:", e)

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

# Función para imprimir eventos almacenados en formato JSON
def imprimir_eventos_formato_json():
    print("Eventos registrados (formato JSON):")
    for evento in eventos:
        print(ujson.dumps(evento, indent=4))

# Función para imprimir eventos almacenados en texto plano
def imprimir_eventos_texto_plano():
    print("Eventos registrados (texto plano):")
    for evento in eventos:
        tipo_evento = evento["tipo"]
        fecha_hora = evento["fecha_hora"]
        print(f"{tipo_evento} - {fecha_hora}")

# Función principal
def main():
    # Registro de evento al iniciar el código
    registrar_evento("inicio_codigo")
    
    # Conectar a WiFi
    wifi_interface = conectar_wifi()
    registrar_evento("wifi_conectado")
    
    # Verificar y ajustar hora con NTP
    verificar_y_ajustar_hora()
    
    # Conectar al broker MQTT
    client = conectar_mqtt()
    registrar_evento("mqtt_conectado")
    
    # Estado inicial de NC y NO
    estado_anterior = (pin12.value(), pin13.value())
    
    # Bucle principal
    while True:
        # Verificar conexión y reconectar si es necesario
        if not wifi_interface.isconnected():
            wifi_interface = conectar_wifi()
            registrar_evento("wifi_reconectado")
        
        if not client.is_connected():
            client = conectar_mqtt()
            registrar_evento("mqtt_reconectado")
        
        # Monitorear el estado de NC y NO
        estado_anterior = monitorear_relay(estado_anterior)
        
        # Imprimir eventos almacenados en formato JSON
        imprimir_eventos_formato_json()
        
        # Imprimir eventos almacenados en texto plano
        imprimir_eventos_texto_plano()
        
        # Esperar un tiempo antes de la próxima verificación
        time.sleep(0.1)

# Ejecutar la función principal al iniciar el script
if __name__ == "__main__":
    main()
