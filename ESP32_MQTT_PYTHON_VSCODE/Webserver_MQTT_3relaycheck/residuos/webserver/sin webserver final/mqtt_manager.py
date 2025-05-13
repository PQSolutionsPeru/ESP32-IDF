from umqtt.robust import MQTTClient
import time

class MQTTManager:
    MQTT_BROKER = "node02.myqtthub.com"
    MQTT_PORT = 8883
    MQTT_CLIENT_ID = "ESP32-PQ1"
    MQTT_USER = "ESP32-1"
    MQTT_PASSWORD = "esp32"

    def __init__(self):
        self.client = None

    def ensure_client(self):
        if self.client is None:
            self.client = MQTTClient(self.MQTT_CLIENT_ID.encode('utf-8'), self.MQTT_BROKER, self.MQTT_PORT,
                                     user=self.MQTT_USER.encode('utf-8'), password=self.MQTT_PASSWORD.encode('utf-8'), ssl=True)
            self.connect_mqtt()

    def connect_mqtt(self):
        try:
            self.client.connect()
            print("Connected to MQTT Broker!")
        except Exception as e:
            print(f"Failed to connect to MQTT: {str(e)}")
            self.client = None
            time.sleep(5)  # Delay before retry
            self.ensure_client()

    def publish_event(self, topic, message):
        try:
            self.ensure_client()  # Ensure that client is connected
            self.client.publish(topic, message, qos=1)
            print(f"Evento enviado al broker MQTT: {message}")
        except Exception as e:
            print(f"Failed to publish: {str(e)}")
            self.client = None
            self.ensure_client()  # Reconnect on failure
