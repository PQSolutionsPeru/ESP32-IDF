import network
import machine

def conectar_wifi(ssid, password):
    sta_if = network.WLAN(network.STA_IF)
    if not sta_if.isconnected():
        print('Conectando a la red WiFi...')
        sta_if.active(True)
        sta_if.connect(ssid, password)
        while not sta_if.isconnected():
            pass
    print('¡Conexión exitosa!')
    print('Dirección IP:', sta_if.ifconfig()[0])

# Llamar a la función para conectarse a la red WiFi "PABLO-2.4G" con la contraseña "47009410"
conectar_wifi('PABLO-2.4G', '47009410')

# Obtener fecha y hora actual
rtc = machine.RTC()
year, month, day, _, hour, minute, second, _ = rtc.datetime()

# Imprimir información
print("Fecha actual:", "{:02d}-{:02d}-{:02d}".format(year, month, day))
print("Hora actual:", "{:02d}:{:02d}:{:02d}".format(hour, minute, second))