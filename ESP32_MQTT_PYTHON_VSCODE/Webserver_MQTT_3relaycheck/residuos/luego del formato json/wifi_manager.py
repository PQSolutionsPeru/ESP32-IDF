import network
import ntptime

class WiFiManager:
    def __init__(self, ssid, password):
        self.ssid = ssid
        self.password = password
        self.wifi_connected = False
        self.time_synced = False

    def connect_wifi(self):
        if not self.wifi_connected:
            sta_if = network.WLAN(network.STA_IF)
            if not sta_if.isconnected():
                print("Conectando a WiFi...")
                sta_if.active(True)
                sta_if.connect(self.ssid, self.password)
                while not sta_if.isconnected():
                    pass
                print("Conectado a WiFi")
                self.wifi_connected = True
                if not self.time_synced:
                    ntptime.settime()  # Sincronizar el tiempo una vez conectado
                    self.time_synced = True
        return self.wifi_connected
