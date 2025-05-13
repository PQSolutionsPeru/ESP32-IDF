from umqtt.robust import MQTTClient
import time

class MQTTManager:
    def __init__(self, broker, port, client_id, user, password):
        self.broker = broker
        self.port = port
        self.client_id = client_id
        self.user = user
        self.password = password
        self.client = None

    def ensure_client(self):
        if self.client is None:
            self.client = MQTTClient(self.client_id.encode('utf-8'), self.broker, self.port,
                                     user=self.user.encode('utf-8'), password=self.password.encode('utf-8'), ssl=True)
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