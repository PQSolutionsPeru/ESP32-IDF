import utime as time
from umqtt.simple import MQTTClient as SimpleMQTTClient

class MQTTClient(SimpleMQTTClient):
    DELAY = 2
    DEBUG = False
    MAX_RETRIES = 3

    def delay(self, i):
        utime.sleep(self.DELAY)

    def log(self, in_reconnect, e):
        if self.DEBUG:
            if in_reconnect:
                print("mqtt reconnect: %r" % e)
            else:
                print("mqtt: %r" % e)

    def reconnect(self):
        for i in range(self.MAX_RETRIES):
            try:
                return super().connect(False)
            except OSError as e:
                self.log(True, e)
                utime.sleep(self.DELAY * (i + 1))
        raise OSError("Max retries exceeded")

    def publish(self, topic, msg, retain=False, qos=0):
        retry_count = 0
        while retry_count < self.MAX_RETRIES:
            try:
                if not isinstance(msg, (bytes, bytearray)):
                    msg = msg.encode() if isinstance(msg, str) else str(msg).encode()
                    
                return super().publish(topic, msg, retain, qos)
            except OSError as e:
                self.log(False, e)
                retry_count += 1
                if retry_count < self.MAX_RETRIES:
                    self.reconnect()
                    utime.sleep(self.DELAY * retry_count)
                else:
                    raise

    def wait_msg(self):
        retry_count = 0
        while retry_count < self.MAX_RETRIES:
            try:
                return super().wait_msg()
            except OSError as e:
                self.log(False, e)
                retry_count += 1
                if retry_count < self.MAX_RETRIES:
                    self.reconnect()
                    utime.sleep(self.DELAY * retry_count)
                else:
                    raise

    def check_msg(self, attempts=2):
        try:
            self.sock.setblocking(False)
            return self.wait_msg()
        except OSError as e:
            self.log(False, e)
            return None