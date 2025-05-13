import json
import paho.mqtt.client as mqtt
from google.cloud import firestore
import time
import signal
import sys
import ssl
from datetime import datetime
import pytz
import logging
import random
import string
import asyncio
from typing import Optional
from typing import Dict, Any
from firebase_admin import messaging, initialize_app
import firebase_admin

# Configuración de logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)

# Configuración de zona horaria
TIMEZONE = pytz.timezone('America/Bogota')

# Configuración MQTT
MQTT_BROKER = 'node02.myqtthub.com'
MQTT_PORT = 8883
MQTT_CLIENT_ID = 'mqtt_firestore_handler'
MQTT_USER = 'mqtt_firestore_handler'
MQTT_PASSWORD = 'mqtt_firestore_handler'

# Configuración de Firestore
db = firestore.Client(project='fir-hdd-monitor-d00de')

# Inicializar Firebase Admin SDK si no está inicializado
if not firebase_admin._apps:
    initialize_app()

def format_date() -> str:
    """Formatea la fecha actual en español, GMT-5"""
    return datetime.now(TIMEZONE).strftime('%d/%m/%Y, %H:%M')

def generate_random_id(length: int = 6) -> str:
    """Genera ID aleatorio para notificaciones"""
    chars = string.ascii_uppercase + string.digits
    return ''.join(random.choice(chars) for _ in range(length))

def cleanup_notifications(client_id: str):
    """Limpia notificaciones antiguas manteniendo solo las últimas 20"""
    try:
        notifications_ref = db.collection(f'hdd-monitor/accounts/clients/{client_id}/notifications')
        snapshot = notifications_ref.order_by('date_time', direction=firestore.Query.DESCENDING).get()

        if len(snapshot) > 20:
            batch = db.batch()
            docs_to_delete = snapshot[20:]
            for doc in docs_to_delete:
                batch.delete(doc.reference)
            batch.commit()
            logging.info(f"Limpiadas {len(docs_to_delete)} notificaciones antiguas del cliente {client_id}")
    except Exception as e:
        logging.error(f"Error en cleanup_notifications: {e}", exc_info=True)

def send_fcm_notifications(client_id: str, notification_data: Dict[str, Any], notification_type: str):
    """Envía notificaciones FCM a usuarios y administradores"""
    try:
        logging.info(f"Enviando notificación FCM - Cliente: {client_id}, Tipo: {notification_type}")
        # Obtener tokens FCM de usuarios del cliente
        users_ref = db.collection(f'hdd-monitor/accounts/clients/{client_id}/users')
        users_snap = users_ref.get()
        
        # Obtener tokens FCM de administradores
        admins_ref = db.collection('hdd-monitor/accounts/admins')
        admins_snap = admins_ref.get()

        # Obtener nombre del cliente
        client_doc = db.document(f'hdd-monitor/accounts/clients/{client_id}').get()
        client_data = client_doc.to_dict() or {}
        client_name = client_data.get('name', '')
        
        logging.info(f"Preparando notificación para cliente: {client_name}")

        # Preparar mensaje base
        base_data = {
            'clientDocName': client_id,
            'panelDocName': notification_data.get('panel_id', ''),
        }

        if notification_type == 'relay':
            base_data.update({
                'relayName': notification_data.get('relay', ''),
                'oldStatus': notification_data.get('old_status', ''),
                'newStatus': notification_data.get('state', '')
            })
            
            # Mensajes específicos para relay
            user_notification = messaging.Notification(
                title=f"{client_name} - Cambio de Estado",
                body=notification_data.get('message', '')
            )
            admin_notification = messaging.Notification(
                title="Cambio de Estado de Relay",
                body=f"{notification_data.get('message', '')} (Cliente: {client_name})"
            )
        else:
            base_data.update({
                'eventId': notification_data.get('event_id', ''),
                'eventType': notification_data.get('type', ''),
                'status': notification_data.get('status', ''),
                'action': notification_data.get('action', '')
            })
            
            # Mensajes específicos para eventos
            user_notification = messaging.Notification(
                title=f"Evento {notification_data.get('type', '')}",
                body=notification_data.get('message', '')
            )
            admin_notification = messaging.Notification(
                title=f"{client_name} - Evento {notification_data.get('type', '')}",
                body=notification_data.get('message', '')
            )

        logging.info("Enviando notificaciones a usuarios y administradores")
        
        # Enviar a usuarios del cliente
        for user_doc in users_snap:
            if token := user_doc.get('fcmToken'):
                try:
                    message = messaging.Message(
                        notification=user_notification,
                        data=base_data,
                        token=token,
                        android=messaging.AndroidConfig(
                            priority='high',
                            notification=messaging.AndroidNotification(
                                channel_id='event_notifications' if notification_type != 'relay' else 'relay_status',
                                priority='high',
                                sound='default',
                                visibility='public'
                            )
                        )
                    )
                    messaging.send(message)
                    logging.info(f"Notificación enviada a usuario: {user_doc.id}")
                except Exception as e:
                    logging.error(f"Error enviando FCM a usuario: {e}")

        # Enviar a administradores
        for admin_doc in admins_snap:
            if token := admin_doc.get('fcmToken'):
                try:
                    message = messaging.Message(
                        notification=admin_notification,
                        data=base_data,
                        token=token,
                        android=messaging.AndroidConfig(
                            priority='high',
                            notification=messaging.AndroidNotification(
                                channel_id='event_notifications' if notification_type != 'relay' else 'relay_status',
                                priority='high',
                                sound='default',
                                visibility='public'
                            )
                        )
                    )
                    messaging.send(message)
                    logging.info(f"Notificación enviada a admin: {admin_doc.id}")
                except Exception as e:
                    logging.error(f"Error enviando FCM a admin: {e}")
                    
        # Guardar notificación en Firestore
        notification_ref = db.collection(f'hdd-monitor/accounts/clients/{client_id}/notifications').document()
        notification_data['documentName'] = notification_ref.id
        notification_data['date_time'] = format_date()
        notification_data['isRead'] = False
        notification_data['lastUpdate'] = firestore.SERVER_TIMESTAMP
        notification_ref.set(notification_data)

        # Limpiar notificaciones antiguas
        cleanup_notifications(client_id)

    except Exception as e:
        logging.error(f"Error en send_fcm_notifications: {e}", exc_info=True)

def process_relay_update(relay_ref: firestore.DocumentReference, old_data: Dict[str, Any], new_data: Dict[str, Any]):
    """Procesa actualizaciones de relays y crea notificaciones correspondientes"""
    try:
        if old_data.get('status') != new_data.get('status'):
            path_parts = relay_ref.path.split('/')
            client_id = path_parts[3]
            panel_id = path_parts[5]
            relay_id = path_parts[7]

            logging.info(f"Procesando cambio de relay - Cliente: {client_id}, Panel: {panel_id}, Relay: {relay_id}")

            # Obtener información del panel
            panel_doc = db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}').get()
            panel_data = panel_doc.to_dict() or {}
            panel_name = panel_data.get('name', '')

            notification_payload = {
                'relay': relay_id,
                'panel_id': panel_id,
                'panel_name': panel_name,
                'state': new_data.get('status'),
                'old_status': old_data.get('status'),
                'message': f'El relay {relay_id} del panel "{panel_name}" ha cambiado de {old_data.get("status")} a {new_data.get("status")}'
            }

            logging.info(f"Enviando notificación de cambio de relay: {relay_id}")
            send_fcm_notifications(client_id, notification_payload, 'relay')

    except Exception as e:
        logging.error(f"Error en process_relay_update: {e}", exc_info=True)

def handle_panel_message(topic: str, payload: Dict[str, Any]):
    """Procesa mensajes de estado de paneles con validación mejorada"""
    try:
        # Validar formato del tópico
        parts = topic.split('/')
        if len(parts) < 4 or parts[0] != "clients" or parts[2] != "panels":
            logging.warning(f"Formato de tópico inválido: {topic}")
            logging.warning(f"Parts: {parts}")
            return

        client_id = parts[1]
        panel_id = parts[3]
        esp32_id = payload.get('esp32_id')

        if not esp32_id:
            logging.warning("ESP32 ID faltante en mensaje")
            return

        # Validar el mensaje
        if not validate_message(topic, payload):
            return

        if 'relay' in payload and 'state' in payload:
            relay_name = payload['relay']
            new_state = payload['state']
            
            # Validar estado del relay
            valid_states = ['CONN', 'DISC']  # Estados válidos
            if new_state not in valid_states:
                logging.warning(f"Estado de relay inválido: {new_state}")
                return
            
            # Obtener referencias
            panel_ref = db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
            relay_ref = panel_ref.collection('relays').document(relay_name)

            # Verificar que el panel existe
            panel_doc = panel_ref.get()
            if not panel_doc.exists:
                logging.error(f"Panel no encontrado: {panel_id}")
                return

            # Obtener estado anterior con manejo de errores
            try:
                relay_doc = relay_ref.get()
                old_data = relay_doc.to_dict() if relay_doc.exists else {'status': None}
            except Exception as e:
                logging.error(f"Error obteniendo estado anterior del relay: {e}")
                old_data = {'status': None}

            # Actualizar estado con retry en caso de error
            max_retries = 3
            for attempt in range(max_retries):
                try:
                    new_data = {
                        'status': new_state,
                        'date_time': format_date(),
                        'lastUpdate': firestore.SERVER_TIMESTAMP
                    }
                    relay_ref.set(new_data, merge=True)
                    logging.info(f"Relay {relay_name} actualizado exitosamente")
                    break
                except Exception as e:
                    if attempt == max_retries - 1:
                        logging.error(f"Error actualizando relay después de {max_retries} intentos: {e}")
                        return
                    time.sleep(1)  # Esperar antes de reintentar

            # Verificar si el estado ha cambiado y procesar notificaciones
            if old_data.get('status') != new_state:
                process_relay_update(relay_ref, old_data, new_data)

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

def watch_firestore_events():
    """Observa cambios en documentos de eventos"""
    try:
        def on_snapshot(doc_snapshot, changes, read_time):
            for change in changes:
                try:
                    if change.type.name == 'ADDED' or change.type.name == 'MODIFIED':
                        old_data = None
                        if change.type.name == 'MODIFIED':
                            old_data = change.document.get().to_dict()
                        new_data = change.document.to_dict()
                        
                        logging.info(f"Cambio detectado en evento: {change.document.id}")
                        logging.info(f"Tipo de cambio: {change.type.name}")
                        
                        process_event_update(change.document.reference, old_data, new_data)

                except Exception as e:
                    logging.error(f"Error procesando cambio de evento: {e}", exc_info=True)

        logging.info("Iniciando observador de eventos Firestore...")
        events_watch = db.collection_group('events').on_snapshot(on_snapshot)

    except Exception as e:
        logging.error(f"Error en watch_firestore_events: {e}", exc_info=True)

def on_connect(client, userdata, flags, rc):
    """Callback mejorado para conexión MQTT"""
    if rc == 0:
        logging.info("Conectado al Broker MQTT!")
        # Publicar estado online
        client.publish(
            f"system/status/{MQTT_CLIENT_ID}",
            json.dumps({
                "status": "ONLINE",
                "timestamp": int(time.time() * 1000)
            }),
            qos=2,
            retain=True
        )
        
        # Definir tópicos y QoS
        topics = [
            ("clients/+/panels/+/#", 1),
            ("system/status/+", 1)
        ]
        
        # Suscribirse a todos los tópicos
        for topic, qos in topics:
            try:
                result, mid = client.subscribe(topic, qos)
                if result == mqtt.MQTT_ERR_SUCCESS:
                    logging.info(f"Suscrito exitosamente a: {topic} (QoS {qos})")
                else:
                    logging.error(f"Error suscribiéndose a {topic}: {result}")
            except Exception as e:
                logging.error(f"Error en suscripción a {topic}: {e}")
                
        logging.info("Proceso de suscripción completado")
    else:
        error_messages = {
            1: "Versión de protocolo incorrecta",
            2: "Identificador de cliente rechazado",
            3: "Servidor no disponible",
            4: "Credenciales incorrectas",
            5: "No autorizado"
        }
        error_msg = error_messages.get(rc, f"Error desconocido: {rc}")
        logging.error(f"Error al conectar al broker MQTT: {error_msg}")

def validate_message(msg, payload: Dict) -> bool:
    """Valida la integridad y formato del mensaje"""
    try:
        # Validar estructura básica
        required_fields = ['esp32_id', 'timestamp']
        if not all(field in payload for field in required_fields):
            logging.warning(f"Campos requeridos faltantes en mensaje: {payload}")
            return False

        # Validar timestamp
        timestamp = payload['timestamp']
        if not isinstance(timestamp, dict) or 'value' not in timestamp:
            logging.warning("Formato de timestamp inválido")
            return False

        current_time = int(time.time() * 1000)
        message_time = int(timestamp['value'])
        
        # Validar que el mensaje no sea muy antiguo (más de 30 segundos)
        if message_time < (current_time - 30000):
            logging.warning(f"Mensaje descartado por antigüedad: {current_time - message_time}ms")
            return False

        return True
    except Exception as e:
        logging.error(f"Error validando mensaje: {e}")
        return False

def on_message(client, userdata, msg):
    """Callback cuando se recibe un mensaje MQTT"""
    try:
        logging.info(f"Mensaje recibido - Topic: {msg.topic}, Size: {len(msg.payload)} bytes")
        
        if not msg.payload:
            logging.warning("Payload vacío recibido")
            return
            
        try:
            payload = json.loads(msg.payload.decode())
            logging.info(f"Payload decodificado: {payload}")
        except json.JSONDecodeError as e:
            logging.error(f"Error decodificando JSON: {e}")
            return
        except UnicodeDecodeError as e:
            logging.error(f"Error decodificando mensaje: {e}")
            return

        if msg.topic.startswith("clients/") and "panels" in msg.topic:
            handle_panel_message(msg.topic, payload)
        elif msg.topic.startswith("system/status/"):
            handle_status_update(payload)
        else:
            logging.warning(f"Tópico no manejado: {msg.topic}")

    except Exception as e:
        logging.error(f"Error en on_message: {e}", exc_info=True)

def process_event_update(event_ref: firestore.DocumentReference, old_data: Dict[str, Any], new_data: Dict[str, Any]):
    """Procesa actualizaciones de eventos y crea notificaciones correspondientes"""
    try:
        path_parts = event_ref.path.split('/')
        client_id = path_parts[3]
        event_id = path_parts[5]

        logging.info(f"Procesando actualización de evento - Cliente: {client_id}, Evento: {event_id}")

        # Determinar tipo de actualización
        update_type = None
        if not old_data:
            update_type = 'CREATE'
        elif old_data.get('status') != new_data.get('status'):
            if new_data.get('status') == 'ACEPTADO' and old_data.get('status') == 'PROGRAMADO':
                update_type = 'ACCEPT'
            elif new_data.get('status') == 'FINALIZADO' and old_data.get('status') != 'FINALIZADO':
                update_type = 'FINISH'
            elif old_data.get('status') == 'FINALIZADO' and new_data.get('status') == 'ACEPTADO':
                update_type = 'REOPEN'
        else:
            update_type = 'EDIT'  # Cambios en título, texto, etc.

        if update_type:
            # Obtener cliente y panel info
            client_doc = db.document(f'hdd-monitor/accounts/clients/{client_id}').get()
            client_data = client_doc.to_dict() or {}
            client_name = client_data.get('name', '')

            panel_id = new_data.get('panelDocName', '')
            if panel_id:
                panel_doc = db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}').get()
                panel_data = panel_doc.to_dict() or {}
                panel_name = panel_data.get('name', '')
            else:
                panel_name = ''

            # Crear payload para notificación
            notification_payload = {
                'event_id': event_id,
                'type': new_data.get('type'),
                'title': new_data.get('title'),
                'status': new_data.get('status'),
                'panel_id': panel_id,
                'panel_name': panel_name,
                'action': update_type,
                'message': get_event_message(update_type, new_data).format(
                    actor_name=new_data.get('actor_name', 'Usuario'),
                    admin_actor_name=new_data.get('actor_name', 'Administrador')
                )
            }

            logging.info(f"Enviando notificación de evento: {update_type}")
            send_fcm_notifications(client_id, notification_payload, 'event')

    except Exception as e:
        logging.error(f"Error en process_event_update: {e}", exc_info=True)

def get_event_message(update_type: str, event_data: Dict[str, Any]) -> str:
    """Retorna el mensaje de notificación según el tipo de actualización del evento"""
    panel_name = event_data.get('panelName') or event_data.get('panelDocName', '')
    panel_text = f' para el panel "{panel_name}"' if panel_name else ''

    if update_type == 'CREATE':
        return f"{{actor_name}} ha creado un nuevo evento de {event_data['type']}: \"{event_data['title']}\"{panel_text}"
    elif update_type == 'ACCEPT':
        return f"{{actor_name}} ha aceptado el evento de {event_data['type']}: \"{event_data['title']}\"{panel_text}"
    elif update_type == 'FINISH':
        return f"{{actor_name}} ha finalizado el evento de {event_data['type']}: \"{event_data['title']}\"{panel_text}"
    elif update_type == 'REOPEN':
        return f"{{admin_actor_name}} ha reabierto el evento de {event_data['type']}: \"{event_data['title']}\"{panel_text}"
    elif update_type == 'EDIT':
        return f"{{actor_name}} ha actualizado el evento de {event_data['type']}: \"{event_data['title']}\"{panel_text}"
    else:
        return ""

def main():
    try:
        logging.info("Iniciando MQTT Firestore Handler...")
        
        # Iniciar observador de Firestore en un hilo separado
        import threading
        firestore_thread = threading.Thread(target=watch_firestore_events, daemon=True)
        firestore_thread.start()

        # Configurar cliente MQTT
        client = mqtt.Client(
            client_id=MQTT_CLIENT_ID, 
            protocol=mqtt.MQTTv311,
            clean_session=True
        )
        
        # Configurar callbacks
        client.on_connect = on_connect
        client.on_message = on_message
        client.on_disconnect = on_disconnect  # Nuevo callback para desconexiones
        
        # Configurar credenciales
        client.username_pw_set(MQTT_USER, MQTT_PASSWORD)
        
        # Configurar TLS con opciones mejoradas
        client.tls_set(
            ca_certs='combined_ca.crt',
            tls_version=ssl.PROTOCOL_TLSv1_2,
            cert_reqs=ssl.CERT_REQUIRED,
            ciphers='ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384'
        )
        
        # Configurar last will message
        client.will_set(
            f"system/status/{MQTT_CLIENT_ID}",
            payload=json.dumps({
                "status": "OFFLINE",
                "timestamp": int(time.time() * 1000)
            }),
            qos=2,
            retain=True
        )
        
        # Loop de reconexión mejorado
        retry_count = 0
        max_retries = 5
        initial_delay = 1  # Delay inicial de 1 segundo
        max_delay = 60     # Delay máximo de 60 segundos
        
        while True:
            try:
                logging.info(f"Conectando a {MQTT_BROKER}:{MQTT_PORT}")
                client.connect(MQTT_BROKER, MQTT_PORT, keepalive=60)
                retry_count = 0
                logging.info("Iniciando loop de mensajes...")
                client.loop_forever()
            except Exception as e:
                retry_count += 1
                delay = min(initial_delay * (2 ** retry_count), max_delay)
                logging.error(f"Error en conexión MQTT (intento {retry_count}/{max_retries}): {e}")
                
                if retry_count >= max_retries:
                    logging.critical("Número máximo de reintentos alcanzado, reiniciando proceso...")
                    sys.exit(1)  # El servicio systemd reiniciará el proceso
                    
                logging.info(f"Esperando {delay} segundos antes de reintentar...")
                time.sleep(delay)

    except KeyboardInterrupt:
        logging.info("Programa interrumpido por el usuario")
        client.disconnect()
    except Exception as e:
        logging.error(f"Error fatal en main: {e}", exc_info=True)
        sys.exit(1)

def on_disconnect(client, userdata, rc):
    """Nuevo callback para manejar desconexiones"""
    if rc != 0:
        logging.warning(f"Desconexión inesperada del broker MQTT, código: {rc}")
    else:
        logging.info("Desconexión normal del broker MQTT")
    
    # Publicar estado offline
    try:
        client.publish(
            f"system/status/{MQTT_CLIENT_ID}",
            json.dumps({
                "status": "OFFLINE",
                "timestamp": int(time.time() * 1000)
            }),
            qos=2,
            retain=True
        )
    except Exception as e:
        logging.error(f"Error publicando estado offline: {e}")

if __name__ == '__main__':
    main()