import bluetooth
from micropython import const

class BTManager:
    def __init__(self, wifi_manager):
        self.bluetooth = bluetooth.BLE()
        self.wifi_manager = wifi_manager
        self.connected = False
        self.bluetooth.active(True)
        self.init_ble_services()

    def init_ble_services(self):
        # UUIDs for the service and characteristics
        WIFI_SERVICE_UUID = bluetooth.UUID("12345678-1234-5678-1234-56789abcdef0")
        SSID_CHAR_UUID = bluetooth.UUID("12345678-1234-5678-1234-56789abcdef1")
        PASS_CHAR_UUID = bluetooth.UUID("12345678-1234-5678-1234-56789abcdef2")

        wifi_service = self.bluetooth.Service(WIFI_SERVICE_UUID, isprimary=True)

        self.ssid_char = wifi_service.Characteristic(SSID_CHAR_UUID, bluetooth.FLAG_WRITE | bluetooth.FLAG_READ, 32)
        self.pass_char = wifi_service.Characteristic(PASS_CHAR_UUID, bluetooth.FLAG_WRITE | bluetooth.FLAG_READ, 32)

        self.ssid_char.callback(trigger=bluetooth.FLAG_WRITE_EVENT, handler=self.on_ssid_write)
        self.pass_char.callback(trigger=bluetooth.FLAG_WRITE_EVENT, handler=self.on_pass_write)

        self.bluetooth.gap_advertise(100, b'\x02\x01\x06\x03\x03\xab\xcd')

    def on_ssid_write(self, char):
        new_ssid = char.value().decode().strip()
        print("New SSID received:", new_ssid)
        self.wifi_manager.update_ssid(new_ssid)

    def on_pass_write(self, char):
        new_pass = char.value().decode().strip()
        print("New Password received:", new_pass)
        self.wifi_manager.update_password(new_pass)

    def is_connected(self):
        return self.connected
