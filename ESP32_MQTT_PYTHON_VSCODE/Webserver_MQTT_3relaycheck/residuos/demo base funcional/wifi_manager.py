import machine
import utime
import network
import urequests

class WiFiManager:
    def __init__(self, ssid, password):
        self.ssid = ssid
        self.password = password
        self.sta_if = network.WLAN(network.STA_IF)
        self.wifi_connected = False

    def connect_wifi(self):
        if not self.sta_if.isconnected():
            print("Conectando a WiFi...")
            self.sta_if.active(True)
            self.sta_if.connect(self.ssid, self.password)
            while not self.sta_if.isconnected():
                pass
            print("Conectado a WiFi")
            self.wifi_connected = True

    def check_connection(self):
        if not self.sta_if.isconnected():
            print("Reconectando a WiFi...")
            self.connect_wifi()

    def get_current_time(self):
        self.check_connection()
        current_time = self._get_world_time()
        return current_time

    def _get_world_time(self):
        try:
            response = urequests.get("http://worldtimeapi.org/api/timezone/America/Lima")
            data = response.json()
            current_datetime = data["datetime"]
            response.close()
            return self._format_datetime(current_datetime)
        except Exception as e:
            print("Error al obtener la fecha y hora:", e)
            return None

    def _format_datetime(self, datetime_str):
        # Formatear la fecha y hora según los requisitos
        year, month, day = datetime_str[:10].split("-")
        time_str = datetime_str[11:19]
        return f"el {day}-{month}-{year} a las {time_str}"

def main():
    wifi_manager = WiFiManager("Tu-SSID", "Tu-Contraseña")
    wifi_manager.connect_wifi()
    while True:
        current_time = wifi_manager.get_current_time()
        if current_time:
            print("Hora actual:", current_time)
        utime.sleep(60)  # Esperar 60 segundos

if __name__ == "__main__":
    main()
