import network
import uasyncio as asyncio
import usocket as socket

class WiFiManager:
    def __init__(self):
        self.ssid = "PABLO-2.4G"
        self.password = "47009410"
        self.sta_if = network.WLAN(network.STA_IF)
        self.wifi_connected = False

    async def connect_wifi(self):
        if not self.sta_if.isconnected():
            print("Activating WiFi interface...")
            self.sta_if.active(True)
            self.sta_if.connect(self.ssid, self.password)
            while not self.sta_if.isconnected():
                await asyncio.sleep(1)
            print("WiFi connected successfully.")

    async def check_connection(self):
        if not self.sta_if.isconnected():
            print("WiFi disconnected, attempting to reconnect...")
            await self.connect_wifi()

    async def get_current_time(self):
        await self.check_connection()
        if self.sta_if.isconnected():
            print("Fetching current time from API...")
            response = await self.http_get("http://worldtimeapi.org/api/timezone/America/Lima")
            if response:
                data = ujson.loads(response)
                current_datetime = data["datetime"]
                print(f"Current time obtained: {current_datetime}")
                return self._format_datetime(current_datetime)
            else:
                print("Failed to fetch time, no response.")
        else:
            print("Not connected to WiFi.")
        return None

    async def http_get(self, url):
        _, _, host, path = url.split('/', 3)
        addr = socket.getaddrinfo(host, 80)[0][-1]
        s = socket.socket()
        s.setblocking(False)
        try:
            await asyncio.wait_for(s.connect(addr), timeout=10)
            s.send(bytes('GET /{} HTTP/1.0\r\nHost: {}\r\n\r\n'.format(path, host), 'utf8'))
            response = await self.read_response(s)
            return response
        finally:
            s.close()

    async def read_response(self, s):
        response = b''
        while True:
            data = await asyncio.wait_for(s.recv(100), timeout=5)
            if not data:
                break
            response += data
        return response.decode('utf-8').split('\r\n\r\n')[1]

    def _format_datetime(self, datetime_str):
        year, month, day = datetime_str[:10].split("-")
        time_str = datetime_str[11:19]
        return f"{day}-{month}-{year} a las {time_str}"
