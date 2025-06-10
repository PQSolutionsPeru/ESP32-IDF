import paho.mqtt.client as mqtt
import ssl
import json
import time
import logging
from config import MQTT_CONFIG
from typing import Dict, Any, Optional, Callable
from google.cloud import firestore

class MQTTClient:
    def __init__(self, message_handler: Callable, db=None):
        """Inicializa el cliente MQTT"""
        self.client_id = MQTT_CONFIG['CLIENT_ID']
        self.message_handler = message_handler
        self.connected = False
        self.db = db  # Referencia a la base de datos
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
            # Configurar credenciales
            self.client.username_pw_set(MQTT_CONFIG['USER'], MQTT_CONFIG['PASSWORD'])
            
            # Configuración TLS
            context = ssl.create_default_context()
            context.load_verify_locations(MQTT_CONFIG['TLS_CA_CERTS'])
            context.check_hostname = False
            
            self.client.tls_set_context(context)
            self.client.tls_insecure_set(False)
            
            # Callbacks
            self.client.on_connect = self._on_connect
            self.client.on_message = self._on_message
            self.client.on_disconnect = self._on_disconnect
            self.client.on_subscribe = self._on_subscribe
            
            # Will message
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
                self.client.loop_forever()
                
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
            logging.info("Conectado al broker MQTT!")
            
            # Publicar estado online
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
            
            # Suscribirse a tópicos (removido OTA)
            topics = [
                ("clients/+/panels/+/#", 2),
                ("system/status/+", 2),
                ("esp32/status/+", 2),
                ("esp32/network_info", 2)
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
            ("esp32/network_info", 2)
        ]
        
        for topic, qos in topics:
            try:
                result, mid = self.client.subscribe(topic, qos)
                if result == mqtt.MQTT_ERR_SUCCESS:
                    logging.info(f"Suscrito a: {topic}")
            except Exception as e:
                logging.error(f"Error en suscripción a {topic}: {e}")

    def _handle_state_change(self, topic: str, payload: Dict[str, Any]):
        """Maneja cambios de estado de los ESP32"""
        try:
            esp32_id = payload['esp32_id']
            new_status = payload['status']
            message_type = payload.get('type', '')
            
            logging.info(f"Procesando estado de ESP32 {esp32_id}: {new_status}")
            
            # Obtener estado actual
            esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            esp32_doc = esp32_ref.get()
            
            if esp32_doc.exists:
                esp32_data = esp32_doc.to_dict()
                current_status = esp32_data.get('status')
                last_update = esp32_data.get('lastStatusUpdate', 0)
                current_time = int(time.time())
                
                # Determinar si procesar el cambio de estado
                should_process = False
                
                if message_type == 'lwt':
                    # Siempre procesar LWT (Last Will Testament)
                    should_process = True
                elif topic == 'esp32/network_info':
                    # Solo procesar network_info si:
                    # 1. Es el primer mensaje (no hay estado actual)
                    # 2. El dispositivo estaba OFFLINE
                    # 3. Han pasado más de 5 minutos desde la última actualización
                    should_process = (
                        current_status is None or
                        current_status == 'OFFLINE' or
                        (current_time - last_update) > 300  # 5 minutos
                    )
                elif new_status != current_status:
                    # Para otros mensajes, procesar si el estado cambió y pasó suficiente tiempo
                    should_process = (current_time - last_update) > 60
                
                if should_process:
                    updates = {
                        'status': new_status,
                        'lastStatusUpdate': current_time,
                        'lastMessageType': message_type,
                        'lastMessageId': payload.get('message_id', '')
                    }
                    
                    # Si es network_info, actualizar información adicional
                    if topic == 'esp32/network_info':
                        updates.update({
                            'IP': payload.get('IP'),
                            'MAC': payload.get('MAC'),
                            'lastNetworkUpdate': current_time
                        })
                    
                    # Manejar notificaciones solo para cambios reales de estado
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
                    
                    # Si el dispositivo está volviendo a ONLINE, actualizar estados de relay
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
                
            # Obtener la colección de relays del panel
            relays_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}/relays')
            
            # Actualizar cada relay
            for relay_doc in relays_ref.stream():
                relay_data = relay_doc.to_dict()
                relay_ref = relays_ref.document(relay_doc.id)
                
                # Marcar para actualización y notificación
                relay_ref.update({
                    'lastUpdate': firestore.SERVER_TIMESTAMP,
                    'needsUpdate': True
                })
                
        except Exception as e:
            logging.error(f"Error actualizando estados de relay después de reconexión: {e}", exc_info=True)

    def _on_message(self, client, userdata, msg):
        """Procesa mensajes MQTT recibidos"""
        try:
            if msg.retain:
                logging.info(f"Ignorando mensaje retain en {msg.topic}")
                return

            payload = json.loads(msg.payload.decode())
            logging.info(f"Mensaje recibido en tópico: {msg.topic}")
            
            # Manejar diferentes tipos de mensajes (removido OTA)
            if msg.topic == "esp32/network_info":
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
                    
            # Obtener referencia del ESP32
            esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            esp32_doc = esp32_ref.get()
            
            if not esp32_doc.exists:
                return
                
            current_data = esp32_doc.to_dict()
            
            # Preparar actualizaciones básicas
            updates = {
                'lastUpdate': firestore.SERVER_TIMESTAMP
            }
            
            # Verificar asignación antes de cambiar estado
            requested_status = payload['status']
            has_assignment = current_data.get('client_id') and current_data.get('panel_id')
            
            # Si el ESP32 quiere estar ONLINE pero no tiene asignación, mantener AWAITING_CONFIG
            if requested_status == 'ONLINE' and not has_assignment:
                updates['status'] = 'AWAITING_CONFIG'
                logging.info(f"ESP32 {esp32_id} intentó cambiar a ONLINE sin asignación, manteniendo AWAITING_CONFIG")
            else:
                # En otros casos (OFFLINE, o ONLINE con asignación), permitir el cambio
                updates['status'] = requested_status
            
            # Si es un mensaje LWT, agregar timestamp de desconexión
            if payload.get('type') == 'lwt':
                updates['lastDisconnect'] = firestore.SERVER_TIMESTAMP
                
                # Solo notificar si el estado anterior no era OFFLINE y es un LWT
                if current_data.get('status') != 'OFFLINE' and payload['status'] == 'OFFLINE':
                    from notification_handler import NotificationHandler
                    notification_handler = NotificationHandler(self.db)
                    notification_handler.send_offline_notification(esp32_id)
                    logging.info(f"LWT recibido y notificado para ESP32 {esp32_id}")
            
            # Notificar cuando un dispositivo vuelve a estar online (solo si tiene asignación)
            elif requested_status == 'ONLINE' and current_data.get('status') == 'OFFLINE' and has_assignment:
                # Dispositivo volvió a estar online después de haber estado offline
                from notification_handler import NotificationHandler
                notification_handler = NotificationHandler(self.db)
                notification_handler.send_online_notification(esp32_id)
                logging.info(f"Dispositivo {esp32_id} volvió a estar ONLINE - Notificación enviada")
            
            # Actualizar información adicional si está presente
            if 'version' in payload:
                updates['firmwareVersion'] = payload['version']
            if 'capabilities' in payload:
                updates['capabilities'] = payload['capabilities']
                    
            # Actualizar estado en Firestore
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
                
            # Obtener referencia del ESP32
            esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            esp32_doc = esp32_ref.get()
            
            # Obtener datos actuales para comparar estados
            current_data = {}
            if esp32_doc.exists:
                current_data = esp32_doc.to_dict()
            
            current_status = current_data.get('status')
            
            # Preparar datos a actualizar
            update_data = {
                'MAC': payload.get('MAC'),
                'IP': payload.get('IP'),
                'lastNetworkUpdate': firestore.SERVER_TIMESTAMP,
                'lastUpdate': firestore.SERVER_TIMESTAMP
            }
            
            # Si hay información adicional de capacidades
            if 'capabilities' in payload:
                update_data['capabilities'] = payload['capabilities']
                
            # Si hay información de versión
            if 'version' in payload:
                update_data['firmwareVersion'] = payload['version']
                
            # MODIFICACIÓN: Solo establecer estado a ONLINE si tiene asignación de cliente y panel
            if 'status' in payload:
                if payload['status'] == 'ONLINE' and (not current_data.get('client_id') or not current_data.get('panel_id')):
                    # Si no tiene asignación, mantener en AWAITING_CONFIG
                    update_data['status'] = 'AWAITING_CONFIG'
                    logging.info(f"ESP32 {esp32_id} sin asignación de cliente/panel, manteniendo en AWAITING_CONFIG")
                else:
                    # Actualizar al estado reportado si tiene asignación
                    update_data['status'] = payload['status']
                    
                    # Notificación si cambió de OFFLINE a ONLINE
                    if payload['status'] == 'ONLINE' and current_status == 'OFFLINE':
                        logging.info(f"Dispositivo {esp32_id} volvió a estar ONLINE (desde network_info) - Enviando notificación")
                        from notification_handler import NotificationHandler
                        notification_handler = NotificationHandler(self.db)
                        notification_handler.send_online_notification(esp32_id)
            
            # Actualizar en Firestore
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
                    'type': 'shutdown',  # Para diferenciar de LWT
                    'timestamp': int(time.time() * 1000)
                }
                
                # Publicar estado offline
                try:
                    self.client.publish(
                        f"system/status/{self.client_id}",
                        json.dumps(offline_msg),
                        qos=2,
                        retain=True
                    )
                    # Dar tiempo para envío
                    time.sleep(0.5)
                except Exception as e:
                    logging.error(f"Error enviando estado offline: {e}")
                    
        except Exception as e:
            logging.error(f"Error en send_offline_status: {e}")

    def cleanup(self):
        """Limpia recursos del cliente MQTT"""
        try:
            # Primero enviar estado offline
            self.send_offline_status()
            
            # Luego desconectar
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