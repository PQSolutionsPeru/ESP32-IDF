import network
import utime
import gc

class WiFiManager:
    def __init__(self):
        self.sta_if = network.WLAN(network.STA_IF)
        self.sta_if.active(True)

    def is_connected(self):
        return self.sta_if.isconnected()

    def connect(self):
        if not self.is_connected():
            print("Conectando a WiFi...")
            while not self.is_connected():
                utime.sleep(1)
            print("Conectado a WiFi.")

    def disconnect(self):
        if self.is_connected():
            self.sta_if.disconnect()
            print("Desconectado de WiFi.")

    def get_connection_info(self):
        if self.is_connected():
            ifconfig = self.sta_if.ifconfig()
            print(f"Configuración de red: {ifconfig}")
            return ifconfig
        else:
            print("No conectado a WiFi.")
            return None

    def check_connection(self):
        try:
            if not self.is_connected():
                print("WiFi desconectado, intentando reconectar...")
                self.connect()
        except Exception as e:
            print(f"Error al verificar la conexión: {e}")

    def get_current_time(self):
        try:
            self.check_connection()
            current_time = self._get_world_time()
            return current_time
        except Exception as e:
            print(f"Error al obtener la hora actual: {e}")
            return None

    def _get_world_time(self):
        try:
            import urequests
            response = urequests.get("http://worldtimeapi.org/api/timezone/America/Lima")
            data = response.json()
            current_datetime = data["datetime"]
            response.close()
            return self._format_datetime(current_datetime)
        except Exception as e:
            print("Error al obtener la fecha y hora:", e)
            return None
        finally:
            gc.collect()  # Recolectar basura después de procesar

    def _format_datetime(self, datetime_str):
        try:
            # Formatear la fecha y hora según los requisitos
            year, month, day = datetime_str[:10].split("-")
            time_str = datetime_str[11:19]
            return f"el {day}-{month}-{year} a las {time_str}"
        except Exception as e:
            print(f"Error al formatear la fecha y hora: {e}")
            return None