import paho.mqtt.client as mqtt
import ssl
import logging
import sys
import time

# Configuración de logging
logging.basicConfig(
    level=logging.DEBUG,
    format='%(asctime)s - %(levelname)s - %(message)s'
)

# Configuración MQTT
MQTT_BROKER = 'node02.myqtthub.com'
MQTT_PORT = 8883
MQTT_CLIENT_ID = 'mqtt_firestore_handler'
MQTT_USER = 'mqtt_firestore_handler'
MQTT_PASSWORD = 'mqtt_firestore_handler'

def on_connect(client, userdata, flags, rc):
    if rc == 0:
        logging.info("Conectado exitosamente al broker")
        # No publicar inmediatamente, esperar un poco
        client.connection_test_passed = True
    else:
        logging.error(f"Error de conexión, código: {rc}")
        client.connection_test_passed = False

def on_disconnect(client, userdata, rc):
    if rc != 0:
        logging.error(f"Desconexión inesperada, código: {rc}")
    else:
        logging.info("Desconexión normal")

def on_publish(client, userdata, mid):
    logging.info(f"Mensaje {mid} publicado")
    client.disconnect()

def on_log(client, userdata, level, buf):
    logging.debug(f"MQTT Log: {buf}")

def create_client():
    client = mqtt.Client(
        client_id=MQTT_CLIENT_ID,
        clean_session=True,
        protocol=mqtt.MQTTv311
    )
    
    client.connection_test_passed = False
    
    # Configurar callbacks
    client.on_connect = on_connect
    client.on_disconnect = on_disconnect
    client.on_publish = on_publish
    client.on_log = on_log
    
    # Configurar autenticación
    client.username_pw_set(MQTT_USER, MQTT_PASSWORD)
    
    # Configurar TLS
    context = ssl.create_default_context()
    context.load_verify_locations(cafile='combined_ca.crt')
    context.check_hostname = False
    context.set_ciphers('ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384')
    
    client.tls_set_context(context)
    client.tls_insecure_set(False)
    
    return client

def main():
    try:
        client = create_client()
        
        logging.info(f"Conectando a {MQTT_BROKER}:{MQTT_PORT}")
        client.connect(MQTT_BROKER, MQTT_PORT, keepalive=60)
        
        # Iniciar el loop
        client.loop_start()
        
        # Esperar a que se establezca la conexión
        time.sleep(2)
        
        if client.connection_test_passed:
            # Intentar publicar un mensaje
            logging.info("Enviando mensaje de prueba...")
            result = client.publish("test", "Mensaje de prueba", qos=1)
            
            # Esperar a que se publique
            if result.wait_for_publish(timeout=5.0):
                logging.info("Mensaje publicado exitosamente")
            else:
                logging.error("Timeout esperando publicación del mensaje")
        
        # Esperar un poco más antes de cerrar
        time.sleep(3)
        
        # Desconectar limpiamente
        client.loop_stop()
        client.disconnect()
        
    except KeyboardInterrupt:
        logging.info("Programa interrumpido por el usuario")
    except Exception as e:
        logging.error(f"Error: {e}", exc_info=True)
    finally:
        try:
            client.disconnect()
            client.loop_stop()
        except:
            pass

if __name__ == "__main__":
    main()