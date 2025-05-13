import bluetooth
import os
from micropython import const

class BTManager:
    def __init__(self, wifi_manager):
        self.server_socket = None
        self.client_socket = None
        self.addr = None
        self.wifi_manager = wifi_manager

        self.activate_bluetooth()

    def activate_bluetooth(self):
        self.server_socket = bluetooth.BluetoothSocket(bluetooth.RFCOMM)
        self.server_socket.bind(("", bluetooth.PORT_ANY))
        self.server_socket.listen(1)

        self.addr = self.server_socket.getsockname()
        print("Bluetooth RFCOMM server bound to", self.addr)

        self.accept_connections()

    def accept_connections(self):
        self.client_socket, addr = self.server_socket.accept()
        print("Accepted connection from", addr)

        while True:
            data = self.client_socket.recv(1024)
            if not data:
                break
            self.process_data(data.decode("utf-8"))

    def process_data(self, data):
        data = data.strip()
        if ":" in data:
            ssid, password = data.split(":", 1)
            print(f"Received SSID: {ssid} and Password: {password}")
            self.wifi_manager.update_ssid(ssid)
            self.wifi_manager.update_password(password)

    def close_connection(self):
        if self.client_socket:
            self.client_socket.close()
        if self.server_socket:
            self.server_socket.close()

    def is_connected(self):
        return self.client_socket is not None
