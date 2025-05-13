from umqtt.robust import MQTTClient
import time

class MQTTManager:
    def __init__(self):
        self.broker = "node02.myqtthub.com"
        self.port = 8883
        self.client_id = "ESP32-PQ1"
        self.user = "ESP32-1"
        self.password = "esp32"
        self.client = None
        self.retry_interval = 1

    def ensure_client(self):
        print("Ensuring MQTT client is connected...")
        if self.client is None or not self.client.isconnected():
            self.connect_mqtt()

    def connect_mqtt(self):
        try:
            print("Attempting MQTT connection...")
            self.client = MQTTClient(self.client_id.encode('utf-8'), self.broker, self.port,
                                     user=self.user.encode('utf-8'), password=self.password.encode('utf-8'), ssl=True)
            self.client.connect()
            print("MQTT Connected successfully")
        except Exception as e:
            print(f"Failed to connect to MQTT: {e}")
            time.sleep(self.retry_interval)
            self.retry_interval = min(self.retry_interval * 2, 300)
            self.client = None

    def publish_event(self, topic, message):
        print(f"Attempting to publish message to {topic}...")
        self.ensure_client()
        if self.client and self.client.isconnected():
            try:
                self.client.publish(topic, message, qos=1)
                print(f"Message published to {topic}: {message}")
            except Exception as e:
                print(f"Failed to publish message: {e}")
                self.client = None  # Re-initialize the client if failed
