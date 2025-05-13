from umqtt.robust import MQTTClient
import time
import _thread

class MQTTManager:
    def __init__(self):
        self.client = None
        self.MAX_RETRIES = 3
        self.RETRY_DELAY = 10
        self.MQTT_BROKER = "node02.myqtthub.com"
        self.MQTT_PORT = 8883
        self.MQTT_CLIENT_ID = "ESP32-PQ1"
        self.MQTT_USER = "ESP32-1"
        self.MQTT_PASSWORD = "esp32"

    def ensure_client(self, retry_count=0):
        if self.client is None or not self.client.isconnected():
            if retry_count < self.MAX_RETRIES:
                self.client = MQTTClient(self.MQTT_CLIENT_ID.encode('utf-8'), self.MQTT_BROKER, self.MQTT_PORT,
                                         user=self.MQTT_USER.encode('utf-8'), password=self.MQTT_PASSWORD.encode('utf-8'), ssl=True)
                try:
                    self.client.connect()
                    print("Conectado al broker MQTT.")
                except Exception as e:
                    print(f"Fallo al conectar con MQTT: {str(e)}")
                    self.client = None
                    time.sleep(self.RETRY_DELAY)
                    self.ensure_client(retry_count + 1)
            else:
                print("No se pudo conectar después de varios intentos. Verifique la configuración de la red y del broker.")

    def publish_event(self, topic, message):
        self.ensure_client()
        try:
            if self.client:
                self.client.publish(topic, message, qos=1)
                print(f"Evento enviado al broker MQTT: {message}")
        except Exception as e:
            print(f"Fallo al publicar debido a: {str(e)}")
            self.client = None
