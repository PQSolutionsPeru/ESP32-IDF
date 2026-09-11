import paho.mqtt.client as mqtt
import ssl
import json
import time
import logging
import re
from config import MQTT_CONFIG
from typing import Dict, Any, Optional, Callable
from google.cloud import firestore

def validate_time_range(time_range: str) -> bool:
    """
    Valida formato "HH:MM a HH:MM" y que inicio != fin.
    Retorna True si válido, False si inválido.
    """
    if not time_range or time_range.strip() == "":
        return False

    pattern = r'^(\d{1,2}):(\d{2})\s+a\s+(\d{1,2}):(\d{2})$'
    match = re.match(pattern, time_range)

    if not match:
        logging.error(f"time_range invalid format: '{time_range}'")
        return False

    h1, m1, h2, m2 = map(int, match.groups())

    # Validar rangos horarios
    if not (0 <= h1 < 24 and 0 <= h2 < 24 and 0 <= m1 < 60 and 0 <= m2 < 60):
        logging.error(f"time_range out of bounds: '{time_range}'")
        return False

    # Validar que NO sean idénticos
    if h1 == h2 and m1 == m2:
        logging.error(f"time_range zero duration: '{time_range}'")
        return False

    return True

class MQTTClient:
    def __init__(self, message_handler: Callable, db=None):
        """Inicializa el cliente MQTT"""
        self.client_id = MQTT_CONFIG['CLIENT_ID']
        self.message_handler = message_handler
        self.connected = False
        self.db = db
        self.connection_time = None  # Timestamp de conexión para ignorar retain viejos
        self.ignore_retain_period = 30  # Segundos
        self._setup_mqtt_client()

    def _setup_mqtt_client(self):
        """Configura una nueva instancia del cliente MQTT"""
        self.client = mqtt.Client(
            client_id=self.client_id,
            clean_session=True,
            protocol=mqtt.MQTTv311
        )
        self.setup_client()

    def setup_client(self):
        """Configura el cliente MQTT"""
        try:
            self.client.username_pw_set(MQTT_CONFIG['USER'], MQTT_CONFIG['PASSWORD'])

            # Solo configurar TLS si está especificado en la configuración
            if 'TLS_CA_CERTS' in MQTT_CONFIG and MQTT_CONFIG.get('TLS_CA_CERTS'):
                context = ssl.create_default_context()
                context.load_verify_locations(MQTT_CONFIG['TLS_CA_CERTS'])
                context.check_hostname = False

                self.client.tls_set_context(context)
                self.client.tls_insecure_set(False)
                logging.info("TLS configurado para conexión MQTT")
            else:
                logging.info("Conexión MQTT sin TLS (localhost)")

            self.client.on_connect = self._on_connect
            self.client.on_message = self._on_message
            self.client.on_disconnect = self._on_disconnect
            self.client.on_subscribe = self._on_subscribe

            will_payload = json.dumps({
                "status": "OFFLINE",
                "client_id": self.client_id,
                "timestamp": int(time.time() * 1000)
            })
            self.client.will_set(
                f"system/status/{self.client_id}",
                payload=will_payload,
                qos=2,
                retain=True
            )

            logging.info(f"Cliente MQTT configurado con ID: {self.client_id}")

        except Exception as e:
            logging.error(f"Error configurando cliente MQTT: {str(e)}", exc_info=True)
            raise

    def connect_and_loop(self):
        """Maneja la conexión y reconexión"""
        retry_count = 0
        max_retries = 10
        
        while True:
            try:
                if not self.connected:
                    logging.info(f"Intentando conexión MQTT a {MQTT_CONFIG['BROKER']}:{MQTT_CONFIG['PORT']}...")
                    
                    self.client.connect(
                        MQTT_CONFIG['BROKER'],
                        MQTT_CONFIG['PORT'],
                        keepalive=60
                    )
                
                retry_count = 0
                self.client.loop_start()
                return
                
            except Exception as e:
                retry_count += 1
                delay = min(2 ** retry_count, 60)
                
                logging.error(f"Error en conexión MQTT (intento {retry_count}): {str(e)}")
                
                try:
                    self.client.disconnect()
                except:
                    pass
                
                self.connected = False
                time.sleep(delay)
                
                if retry_count >= max_retries:
                    logging.warning("Reiniciando cliente MQTT...")
                    self._setup_mqtt_client()
                    retry_count = 0

    def _on_connect(self, client, userdata, flags, rc, properties=None):
        """Callback de conexión MQTT"""
        if rc == 0:
            self.connected = True
            self.connection_time = time.time()  # Marcar tiempo de conexión
            logging.info("Conectado al broker MQTT!")
            
            online_payload = json.dumps({
                "status": "ONLINE",
                "client_id": self.client_id,
                "timestamp": int(time.time() * 1000)
            })
            
            self.client.publish(
                f"system/status/{self.client_id}",
                payload=online_payload,
                qos=2,
                retain=True
            )
            
            topics = [
                ("clients/+/panels/+/#", 2),
                ("system/status/+", 2),
                ("esp32/status/+", 2),
                ("esp32/network_info", 2),
                ("esp32/connectivity/+", 2)
            ]
            
            for topic, qos in topics:
                try:
                    result, mid = self.client.subscribe(topic, qos)
                    if result == mqtt.MQTT_ERR_SUCCESS:
                        logging.info(f"Suscrito a: {topic}")
                except Exception as e:
                    logging.error(f"Error en suscripción a {topic}: {e}")
        else:
            self.connected = False
            logging.error(f"Error de conexión MQTT: {rc}")

    def _subscribe_to_topics(self):
        """Suscribe a los tópicos necesarios"""
        topics = [
            ("clients/+/panels/+/#", 2),
            ("system/status/+", 2),
            ("esp32/status/+", 2),
            ("esp32/network_info", 2),
            ("esp32/connectivity/+", 2)
        ]
        
        for topic, qos in topics:
            try:
                result, mid = self.client.subscribe(topic, qos)
                if result == mqtt.MQTT_ERR_SUCCESS:
                    logging.info(f"Suscrito a: {topic}")
            except Exception as e:
                logging.error(f"Error en suscripción a {topic}: {e}")

    def _handle_connectivity_message(self, topic: str, payload: Dict[str, Any]):
        """Maneja mensajes de conectividad del ESP32"""
        try:
            esp32_id = payload.get('esp32_id')
            event_type = payload.get('type')
            status = payload.get('status')
            panel_name = payload.get('panel_name', 'Panel')
            ssid = payload.get('ssid', '')
            time_range = payload.get('time_range', '')

            # AGREGAR validación
            if time_range and not validate_time_range(time_range):
                logging.warning(f"Rejecting connectivity event - invalid time_range: '{time_range}' "
                               f"for ESP32 {esp32_id}")
                return

            if not esp32_id or not event_type:
                logging.warning(f"Mensaje de conectividad incompleto: {payload}")
                return
            
            esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            esp32_doc = esp32_ref.get()
            
            if not esp32_doc.exists:
                logging.warning(f"ESP32 {esp32_id} no encontrado en registro")
                return
            
            esp32_data = esp32_doc.to_dict()
            client_id = esp32_data.get('client_id')
            panel_id = esp32_data.get('panel_id')
            
            if not client_id or not panel_id:
                logging.warning(f"ESP32 {esp32_id} no tiene asignación de cliente/panel")
                return
            
            panel_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
            panel_doc = panel_ref.get()
            
            if panel_doc.exists:
                panel_data = panel_doc.to_dict()
                panel_name = panel_data.get('name', panel_name)
            
            client_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}')
            client_doc = client_ref.get()
            client_name = ""
            if client_doc.exists:
                client_data = client_doc.to_dict()
                client_name = client_data.get('name', '')
            
            from notification_handler import NotificationHandler
            notification_handler = NotificationHandler(self.db)
            
            if event_type in ['WIFI_DISCONNECTED', 'INTERNET_LOST', 'MQTT_DISCONNECTED']:
                if event_type == 'WIFI_DISCONNECTED':
                    message = f"Panel {panel_name} se desconectó de la red {ssid}"
                    if time_range:
                        message += f" de {time_range}"
                    notification_handler.send_wifi_disconnection_notification(
                        client_id, panel_name, ssid, time_range, client_name
                    )
                elif event_type == 'INTERNET_LOST':
                    message = f"Panel {panel_name} estuvo sin internet"
                    if time_range:
                        message += f" de {time_range}"
                    notification_handler.send_internet_loss_notification(
                        client_id, panel_name, time_range, client_name
                    )
                elif event_type == 'MQTT_DISCONNECTED':
                    message = f"Panel {panel_name} perdió conexión con el servidor MQTT"
                    if time_range:
                        message += f" de {time_range}"
                    notification_handler.send_mqtt_disconnection_notification(
                        client_id, panel_name, time_range, client_name
                    )
                
                logging.info(f"Notificación de conectividad enviada: {message}")

            elif event_type in ['WIFI_RECONNECTED', 'INTERNET_RECOVERED', 'MQTT_RECONNECTED']:
                if event_type == 'WIFI_RECONNECTED':
                    notification_handler.send_connectivity_recovery_notification(
                        client_id, panel_name, "wifi", ssid, client_name
                    )
                    message = f"Panel {panel_name} se reconectó a la red {ssid}"
                elif event_type == 'INTERNET_RECOVERED':
                    notification_handler.send_connectivity_recovery_notification(
                        client_id, panel_name, "internet", "", client_name
                    )
                    message = f"Panel {panel_name} recuperó conectividad a internet"
                elif event_type == 'MQTT_RECONNECTED':
                    notification_handler.send_connectivity_recovery_notification(
                        client_id, panel_name, "mqtt", "", client_name
                    )
                    message = f"Panel {panel_name} se reconectó al servidor MQTT"
                
                logging.info(f"Notificación de recuperación enviada: {message}")
                
        except Exception as e:
            logging.error(f"Error procesando mensaje de conectividad: {e}", exc_info=True)

    def _handle_state_change(self, topic: str, payload: Dict[str, Any]):
        """Maneja cambios de estado de los ESP32"""
        try:
            esp32_id = payload['esp32_id']
            new_status = payload['status']
            message_type = payload.get('type', '')
            
            logging.info(f"Procesando estado de ESP32 {esp32_id}: {new_status}")
            
            esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            esp32_doc = esp32_ref.get()
            
            if esp32_doc.exists:
                esp32_data = esp32_doc.to_dict()
                current_status = esp32_data.get('status')
                last_update = esp32_data.get('lastStatusUpdate', 0)
                current_time = int(time.time())
                
                should_process = False
                
                if message_type == 'lwt':
                    should_process = True
                elif topic == 'esp32/network_info':
                    should_process = (
                        current_status is None or
                        current_status == 'OFFLINE' or
                        (current_time - last_update) > 300
                    )
                elif new_status != current_status:
                    should_process = (current_time - last_update) > 60
                
                if should_process:
                    updates = {
                        'status': new_status,
                        'lastStatusUpdate': current_time,
                        'lastMessageType': message_type,
                        'lastMessageId': payload.get('message_id', '')
                    }
                    
                    if topic == 'esp32/network_info':
                        updates.update({
                            'IP': payload.get('IP'),
                            'MAC': payload.get('MAC'),
                            'lastNetworkUpdate': current_time
                        })
                    
                    if new_status == 'OFFLINE' and current_status == 'ONLINE':
                        logging.info(f"Dispositivo {esp32_id} está OFFLINE. Notificando...")
                        from notification_handler import NotificationHandler
                        notification_handler = NotificationHandler(self.db)
                        notification_handler.send_offline_notification(esp32_id)
                        
                    elif new_status == 'ONLINE' and current_status == 'OFFLINE':
                        logging.info(f"Dispositivo {esp32_id} ha vuelto a ONLINE. Notificando...")
                        from notification_handler import NotificationHandler
                        notification_handler = NotificationHandler(self.db)
                        notification_handler.send_online_notification(esp32_id)
                    
                    esp32_ref.update(updates)
                    logging.info(f"Estado actualizado para ESP32 {esp32_id}")
                    
                    if new_status == 'ONLINE' and current_status == 'OFFLINE':
                        self._update_relay_states_after_reconnection(esp32_id, esp32_data)

        except Exception as e:
            logging.error(f"Error en manejo de estado: {e}", exc_info=True)

    def _update_relay_states_after_reconnection(self, esp32_id: str, esp32_data: Dict[str, Any]):
        """Actualiza los estados de los relays después de una reconexión"""
        try:
            client_id = esp32_data.get('client_id')
            panel_id = esp32_data.get('panel_id')
            
            if not client_id or not panel_id:
                return
                
            relays_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}/relays')
            
            for relay_doc in relays_ref.stream():
                relay_data = relay_doc.to_dict()
                relay_ref = relays_ref.document(relay_doc.id)
                
                relay_ref.update({
                    'lastUpdate': firestore.SERVER_TIMESTAMP,
                    'needsUpdate': True
                })
                
        except Exception as e:
            logging.error(f"Error actualizando estados de relay después de reconexión: {e}", exc_info=True)

    def _on_message(self, client, userdata, msg):
        """Procesa mensajes MQTT recibidos"""
        try:
            # Ignorar retain solo durante los primeros 30s después de conectar (evita duplicados al reiniciar servidor)
            if msg.retain and self.connection_time:
                elapsed = time.time() - self.connection_time
                if elapsed < self.ignore_retain_period:
                    logging.info(f"Ignorando mensaje retain antiguo en {msg.topic} (elapsed: {elapsed:.1f}s)")
                    return
                else:
                    logging.info(f"Procesando mensaje retain (nuevo evento) en {msg.topic}")

            payload = json.loads(msg.payload.decode())
            logging.info(f"Mensaje recibido en tópico: {msg.topic}")
            
            if msg.topic.startswith("esp32/connectivity/"):
                self._handle_connectivity_message(msg.topic, payload)
            elif msg.topic == "esp32/network_info":
                self._handle_network_info(payload)
            elif msg.topic.startswith("system/status/"):
                self._handle_system_status(msg.topic, payload)
            elif msg.topic.startswith("clients/"):
                self.message_handler(msg)
                
        except Exception as e:
            logging.error(f"Error procesando mensaje MQTT: {e}", exc_info=True)

    def _handle_system_status(self, topic: str, payload: Dict[str, Any]):
        """Maneja mensajes de estado del sistema de los ESP32"""
        try:
            esp32_id = topic.split('/')[-1]
            
            if not esp32_id or 'status' not in payload:
                logging.warning(f"Mensaje de estado incompleto: {payload}")
                return
                    
            esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            esp32_doc = esp32_ref.get()
            
            if not esp32_doc.exists:
                return
                
            current_data = esp32_doc.to_dict()
            
            updates = {
                'lastUpdate': firestore.SERVER_TIMESTAMP
            }
            
            requested_status = payload['status']
            has_assignment = current_data.get('client_id') and current_data.get('panel_id')
            
            if requested_status == 'ONLINE' and not has_assignment:
                updates['status'] = 'AWAITING_CONFIG'
                logging.info(f"ESP32 {esp32_id} intentó cambiar a ONLINE sin asignación, manteniendo AWAITING_CONFIG")
            else:
                updates['status'] = requested_status
            
            if payload.get('type') == 'lwt':
                updates['lastDisconnect'] = firestore.SERVER_TIMESTAMP
                
                if current_data.get('status') != 'OFFLINE' and payload['status'] == 'OFFLINE':
                    from notification_handler import NotificationHandler
                    notification_handler = NotificationHandler(self.db)
                    notification_handler.send_offline_notification(esp32_id)
                    logging.info(f"LWT recibido y notificado para ESP32 {esp32_id}")
            
            elif requested_status == 'ONLINE' and current_data.get('status') == 'OFFLINE' and has_assignment:
                from notification_handler import NotificationHandler
                notification_handler = NotificationHandler(self.db)
                notification_handler.send_online_notification(esp32_id)
                logging.info(f"Dispositivo {esp32_id} volvió a estar ONLINE - Notificación enviada")
            
            if 'version' in payload:
                updates['firmwareVersion'] = payload['version']
            if 'capabilities' in payload:
                updates['capabilities'] = payload['capabilities']
                    
            esp32_ref.set(updates, merge=True)
            logging.info(f"Estado actualizado para ESP32 {esp32_id}: {updates.get('status', requested_status)}")
                
        except Exception as e:
            logging.error(f"Error procesando estado del sistema: {e}", exc_info=True)

    def _handle_network_info(self, payload: Dict[str, Any]):
        """Maneja mensajes de información de red de los ESP32"""
        try:
            esp32_id = payload.get('esp32_id')
            if not esp32_id:
                logging.error("Mensaje de red sin ESP32 ID")
                return
                
            esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            esp32_doc = esp32_ref.get()
            
            current_data = {}
            if esp32_doc.exists:
                current_data = esp32_doc.to_dict()
            
            current_status = current_data.get('status')
            
            update_data = {
                'MAC': payload.get('MAC'),
                'IP': payload.get('IP'),
                'lastNetworkUpdate': firestore.SERVER_TIMESTAMP,
                'lastUpdate': firestore.SERVER_TIMESTAMP
            }
            
            if 'capabilities' in payload:
                update_data['capabilities'] = payload['capabilities']
                
            if 'version' in payload:
                update_data['firmwareVersion'] = payload['version']
                
            if 'status' in payload:
                if payload['status'] == 'ONLINE' and (not current_data.get('client_id') or not current_data.get('panel_id')):
                    update_data['status'] = 'AWAITING_CONFIG'
                    logging.info(f"ESP32 {esp32_id} sin asignación de cliente/panel, manteniendo en AWAITING_CONFIG")
                else:
                    update_data['status'] = payload['status']
                    
                    if payload['status'] == 'ONLINE' and current_status == 'OFFLINE':
                        logging.info(f"Dispositivo {esp32_id} volvió a estar ONLINE (desde network_info) - Enviando notificación")
                        from notification_handler import NotificationHandler
                        notification_handler = NotificationHandler(self.db)
                        notification_handler.send_online_notification(esp32_id)
            
            esp32_ref.set(update_data, merge=True)
            logging.info(f"Información de red actualizada para ESP32 {esp32_id}")
            
        except Exception as e:
            logging.error(f"Error procesando información de red: {e}", exc_info=True)

    def _on_disconnect(self, client, userdata, rc):
        """Maneja desconexiones"""
        self.connected = False
        if rc != 0:
            logging.warning(f"Desconexión inesperada, código: {rc}")
        else:
            logging.info("Desconexión normal del broker MQTT")

    def _on_subscribe(self, client, userdata, mid, granted_qos):
        """Confirma suscripciones"""
        logging.info(f"Suscripción confirmada con QoS: {granted_qos}")

    def send_offline_status(self):
        """Envía estado OFFLINE antes de desconectarse"""
        try:
            if self.connected and self.client:
                offline_msg = {
                    'client_id': self.client_id,
                    'status': 'OFFLINE',
                    'type': 'shutdown',
                    'timestamp': int(time.time() * 1000)
                }
                
                try:
                    self.client.publish(
                        f"system/status/{self.client_id}",
                        json.dumps(offline_msg),
                        qos=2,
                        retain=True
                    )
                    time.sleep(0.5)
                except Exception as e:
                    logging.error(f"Error enviando estado offline: {e}")
                    
        except Exception as e:
            logging.error(f"Error en send_offline_status: {e}")

    def cleanup(self):
        """Limpia recursos del cliente MQTT"""
        try:
            self.send_offline_status()
            
            if self.client:
                try:
                    self.client.disconnect()
                    time.sleep(0.1)
                    self.client.loop_stop()
                    logging.info("Desconexión MQTT exitosa")
                    
                except Exception as e:
                    logging.error(f"Error en desconexión MQTT: {e}")
                    
            self.client = None
            self.connected = False
            
        except Exception as e:
            logging.error(f"Error en cleanup: {e}")