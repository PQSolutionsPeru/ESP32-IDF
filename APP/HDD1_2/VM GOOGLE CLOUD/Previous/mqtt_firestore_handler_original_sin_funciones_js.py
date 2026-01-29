import json
import paho.mqtt.client as mqtt
from google.cloud import firestore
import time
from datetime import datetime
import logging
import random
from typing import Dict, Any

# Configuración de logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)

# Configuración MQTT
MQTT_BROKER = 'node02.myqtthub.com'
MQTT_PORT = 8883
MQTT_CLIENT_ID = 'mqtt_firestore_handler'
MQTT_USER = 'mqtt_firestore_handler'
MQTT_PASSWORD = 'mqtt_firestore_handler'

# Configuración de Firestore
db = firestore.Client(project='fir-hdd-monitor-d00de')

def on_connect(client, userdata, flags, rc):
    if rc == 0:
        logging.info("Conectado al Broker MQTT!")
        # Suscribirse a todos los mensajes de paneles usando wildcard
        client.subscribe("clients/+/panels/+/#", qos=1)  # Notar el wildcard adicional
        client.subscribe("system/status/+", qos=1)
    else:
        logging.error(f"Error al conectar, código: {rc}")

def handle_panel_message(topic: str, payload: Dict[str, Any]):
    """Procesa mensajes de estado de paneles"""
    try:
        parts = topic.split('/')
        if len(parts) < 4 or parts[0] != "clients" or parts[2] != "panels":
            logging.warning(f"Formato de tópico inválido: {topic}")
            logging.warning(f"Parts: {parts}")
            return

        client_id = parts[1]
        panel_id = parts[3]
        esp32_id = payload.get('esp32_id')

        logging.info(f"Procesando mensaje de panel - Client: {client_id}, Panel: {panel_id}, ESP32: {esp32_id}")

        if not esp32_id:
            logging.warning("ESP32 ID faltante en mensaje")
            return

        if 'relay' in payload and 'state' in payload:
            relay_name = payload['relay']
            new_state = payload['state']

            logging.info(f"Actualizando estado de relay {relay_name} a {new_state}")
            
            # Construir referencia correcta al relay
            panel_ref = db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
            relay_ref = panel_ref.collection('relays').document(relay_name)

            # Verificar panel existe
            if not panel_ref.get().exists:
                logging.error(f"Panel no encontrado: {panel_id}")
                return

            # Actualizar estado
            relay_ref.set({
                'status': new_state,
                'date_time': datetime.now().strftime('%d-%m-%Y %H:%M'),
                'lastUpdate': firestore.SERVER_TIMESTAMP
            }, merge=True)

            logging.info(f"Relay {relay_name} actualizado exitosamente")

            # Crear notificación
            create_notification(
                client_id=client_id,
                panel_id=panel_id,
                relay_name=relay_name,
                new_state=new_state
            )

    except Exception as e:
        logging.error(f"Error en handle_panel_message: {e}", exc_info=True)

def handle_status_update(payload: Dict[str, Any]):
    """Maneja actualizaciones de estado de ESP32s"""
    try:
        esp32_id = payload.get('esp32_id')
        if not esp32_id:
            logging.warning("ESP32 ID faltante en actualización de estado")
            return

        status = payload.get('status')
        if status in ['CONFIG', 'RUNNING', 'OFFLINE']:
            doc_ref = db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            doc_ref.set({
                'status': status,
                'lastUpdate': firestore.SERVER_TIMESTAMP
            }, merge=True)
            logging.info(f"Estado de ESP32 {esp32_id} actualizado a: {status}")

    except Exception as e:
        logging.error(f"Error actualizando estado: {e}", exc_info=True)

def create_notification(client_id: str, panel_id: str, relay_name: str, new_state: str):
    """Crea notificación de cambio de estado de relay"""
    try:
        # Obtener información del panel
        panel_ref = db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
        panel_doc = panel_ref.get()
        
        if not panel_doc.exists:
            logging.error(f"Panel {panel_id} no encontrado")
            return

        panel_data = panel_doc.to_dict()
        panel_name = panel_data.get('name', 'Panel desconocido')
        current_time = datetime.now().strftime('%d/%m/%Y, %H:%M')

        # Crear ID único para la notificación
        notification_id = f"notification_{client_id}_{datetime.now().strftime('%Y%m%d%H%M%S')}_{generate_random_id()}"

        # Crear notificación
        notification = {
            'date_time': current_time,
            'panelDocName': panel_id,
            'message': f'El relay {relay_name} del panel "{panel_name}" ha cambiado a {new_state}',
            'relayName': relay_name,
            'isRead': False,
            'documentName': notification_id,
            'lastUpdate': firestore.SERVER_TIMESTAMP
        }

        # Guardar notificación
        notifications_ref = db.collection(f'hdd-monitor/accounts/clients/{client_id}/notifications')
        notifications_ref.document(notification_id).set(notification)

        logging.info(f"Notificación creada para {panel_name}: {relay_name} -> {new_state}")

    except Exception as e:
        logging.error(f"Error creando notificación: {e}", exc_info=True)

def generate_random_id(length: int = 6) -> str:
    """Genera ID aleatorio para notificaciones"""
    import random
    import string
    chars = string.ascii_uppercase + string.digits
    return ''.join(random.choice(chars) for _ in range(length))

def on_message(client, userdata, msg):
    """Procesa mensajes MQTT recibidos"""
    try:
        logging.info(f"Mensaje recibido en tópico: {msg.topic}")
        payload = json.loads(msg.payload.decode())
        logging.info(f"Payload: {payload}")

        if msg.topic.startswith("clients/") and "panels" in msg.topic:
            handle_panel_message(msg.topic, payload)
        elif msg.topic.startswith("system/status/"):
            handle_status_update(payload)

    except json.JSONDecodeError as e:
        logging.error(f"Error decodificando JSON: {e}")
    except Exception as e:
        logging.error(f"Error procesando mensaje: {e}", exc_info=True)

def main():
    client = mqtt.Client(client_id=MQTT_CLIENT_ID, clean_session=True)
    client.on_connect = on_connect
    client.on_message = on_message
    client.username_pw_set(MQTT_USER, MQTT_PASSWORD)
    
    # Configurar TLS
    client.tls_set(ca_certs='combined_ca.crt')
    
    logging.info(f"Conectando a {MQTT_BROKER}:{MQTT_PORT}")
    client.connect(MQTT_BROKER, MQTT_PORT, keepalive=60)
    client.loop_forever()

if __name__ == '__main__':
    main()