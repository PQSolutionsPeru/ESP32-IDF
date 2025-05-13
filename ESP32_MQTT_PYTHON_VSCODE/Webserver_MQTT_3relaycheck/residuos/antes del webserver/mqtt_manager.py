from umqtt.robust import MQTTClient
import time

class MQTTManager:
    def __init__(self, broker, port, client_id, user, password, logger):
        self.broker = broker
        self.port = port
        self.client_id = client_id
        self.user = user
        self.password = password
        self.logger = logger
        self.client = None

    def ensure_client(self):
        if self.client is None:
            self.connect_mqtt()

    def connect_mqtt(self):
        try:
            if self.client:
                self.client.disconnect()  # Asegúrate de desconectar primero si el cliente ya existe
            self.client = MQTTClient(self.client_id.encode('utf-8'), self.broker, self.port,
                                     user=self.user.encode('utf-8'), password=self.password.encode('utf-8'), ssl=True)
            self.client.connect()
            self.logger.write_log("Conectado al broker MQTT!")
        except Exception as e:
            self.logger.write_log(f"Fallo al conectar con MQTT: {str(e)}")
            time.sleep(5)  # Delay before retry
            self.client = None  # Asegurarse de resetear el cliente si hay una falla

    def publish_event(self, topic, message):
        try:
            self.ensure_client()  # Asegura que el cliente está conectado
            if self.client:
                self.client.publish(topic, message, qos=1)
                self.logger.write_log(f"Evento enviado al broker MQTT: {message}")
            else:
                raise Exception("Cliente MQTT no disponible")
        except Exception as e:
            self.logger.write_log(f"Fallo al publicar: {str(e)}")
            self.client = None  # Asegurarse de resetear el cliente si hay una falla