import network
import utime
import urequests

class WiFiManager:
    def __init__(self):
        self.sta_if = network.WLAN(network.STA_IF)
        self.sta_if.active(True)
        # Inicializa con valores por defecto o nulos
        self.SSID = None
        self.PASSWORD = None

    def update_ssid(self, new_ssid):
        if new_ssid:  # Asegurarse de que el SSID no está vacío
            self.SSID = new_ssid.strip()
            print("Updating SSID to:", self.SSID)
            self.connect_wifi()
        else:
            print("Received empty SSID.")

    def update_password(self, new_password):
        if new_password:  # Asegurarse de que la contraseña no está vacía
            self.PASSWORD = new_password.strip()
            print("Updating Password to:", self.PASSWORD)
            self.connect_wifi()
        else:
            print("Received empty password.")

    def connect_wifi(self):
        if self.SSID is None or self.PASSWORD is None:
            print("SSID or PASSWORD not defined.")
            return
        
        if not self.sta_if.isconnected():
            print("Conectando a WiFi...")
            self.sta_if.connect(self.SSID, self.PASSWORD)
            start_time = utime.ticks_ms()
            while not self.sta_if.isconnected():
                if utime.ticks_diff(utime.ticks_ms(), start_time) > 10000:  # 10 segundos de tiempo máximo de espera
                    print("Timeout al intentar conectar a WiFi")
                    break
                utime.sleep(1)
            if self.sta_if.isconnected():
                print("Conectado a WiFi.")
            else:
                print("No se pudo conectar a WiFi, intentará reconectarse automáticamente.")

    def check_connection(self):
        if not self.sta_if.isconnected():
            print("WiFi desconectado, intentando reconectar...")
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
