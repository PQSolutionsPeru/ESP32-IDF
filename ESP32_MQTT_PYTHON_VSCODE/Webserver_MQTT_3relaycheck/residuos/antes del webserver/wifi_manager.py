import network # type: ignore
import urequests # type: ignore
from log_manager import LogManager

class WiFiManager:
    def __init__(self, ssid, password, logger):
        self.ssid = ssid
        self.password = password
        self.logger = logger
        self.sta_if = network.WLAN(network.STA_IF)
        self.wifi_connected = False

    def connect_wifi(self):
        self.logger.write_log("Conectando a WiFi...")
        if not self.sta_if.isconnected():
            self.sta_if.active(True)
            self.sta_if.connect(self.ssid, self.password)
            while not self.sta_if.isconnected():
                pass
            ip_address = self.sta_if.ifconfig()[0]
            self.logger.write_log(f"WiFi conectado en {ip_address}")
            self.wifi_connected = True

    def check_connection(self):
        if not self.sta_if.isconnected():
            self.logger.write_log("Conexion WiFi perdida. Reconectando...")
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
            self.logger.write_log(f"Error al obtener la fecha y hora: {e}")
            return None

    def _format_datetime(self, datetime_str):
        year, month, day = datetime_str[:10].split("-")
        time_str = datetime_str[11:19]
        return f"{day}-{month}-{year} a las {time_str}"
