from google.cloud import firestore
import firebase_admin
from firebase_admin import credentials, messaging
import logging
from typing import Dict, Any, Optional
from datetime import datetime
import pytz
import json
import time
import paho.mqtt.client as mqtt
import ssl
from config import ESP32_CONFIG_MQTT as MQTT_CONFIG

ESP32_STATES = {
    'AWAITING_CONFIG': 'AWAITING_CONFIG',
    'ONLINE': 'ONLINE',
    'ERROR': 'ERROR',
    'OFFLINE': 'OFFLINE'
}

RELAY_STATES = {
    'OK': 'OK',
    'DISC': 'DISC',
    'ERROR': 'ERROR'
}

DEFAULT_RELAY_STATUS = RELAY_STATES['DISC']

class ESP32ConfigManager:
    def __init__(self):
        self.client_id = MQTT_CONFIG['CLIENT_ID']
        self.connected = False
        self.client = None
        
        self.db = firestore.Client(project='fir-hdd-monitor-d00de')
        
        self.client = self._setup_mqtt_client()
        
        self._config_cache = {}
        self._esp32_status = {}
        self._last_config_sent = {}

        self._watch_references = []

    def _should_send_config(self, esp32_id: str, esp32_data: Dict[str, Any]) -> bool:
        try:
            current_status = esp32_data.get('status')
            client_id = esp32_data.get('client_id')
            panel_id = esp32_data.get('panel_id')
            
            if current_status != ESP32_STATES['AWAITING_CONFIG']:
                logging.debug(f"ESP32 {esp32_id} en estado {current_status}, no necesita configuración")
                return False
            
            if not client_id or not panel_id:
                logging.debug(f"ESP32 {esp32_id} sin asignación completa (client: {client_id}, panel: {panel_id})")
                return False
            
            current_time = time.time()
            last_sent = self._last_config_sent.get(esp32_id, 0)
            
            if current_time - last_sent < 300:
                time_diff = int(current_time - last_sent)
                logging.info(f"Configuración enviada a ESP32 {esp32_id} hace {time_diff}s, saltando")
                return False
            
            return True
            
        except Exception as e:
            logging.error(f"Error verificando si enviar config a {esp32_id}: {e}")
            return False

    def _handle_registration(self, esp32_id: str, payload: Dict[str, Any]):
        try:
            esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            esp32_doc = esp32_ref.get()
            current_time = datetime.now(pytz.UTC)

            if not esp32_doc.exists:
                esp32_data = {
                    'MAC': payload.get('MAC', ''),
                    'IP': payload.get('IP', ''),
                    'firstSeen': current_time,
                    'lastUpdate': current_time,
                    'status': ESP32_STATES['AWAITING_CONFIG']
                }
                esp32_ref.set(esp32_data)
                logging.info(f"Nuevo ESP32 registrado: {esp32_id} (AWAITING_CONFIG)")
                
            else:
                esp32_data = esp32_doc.to_dict()
                current_status = esp32_data.get('status')
                
                if esp32_data.get('client_id') and esp32_data.get('panel_id'):
                    if current_status == ESP32_STATES['AWAITING_CONFIG']:
                        if self._should_send_config(esp32_id, esp32_data):
                            self._send_config(esp32_id, esp32_data)
                    else:
                        updates = {
                            'IP': payload.get('IP', ''),
                            'lastUpdate': current_time,
                            'lastNetworkUpdate': current_time
                        }
                        esp32_ref.update(updates)
                        logging.debug(f"ESP32 {esp32_id} ya configurado, solo actualizando info de red")
                else:
                    updates = {
                        'IP': payload.get('IP', ''),
                        'lastUpdate': current_time,
                        'status': ESP32_STATES['AWAITING_CONFIG']
                    }
                    esp32_ref.update(updates)
                    logging.info(f"ESP32 {esp32_id} sin asignación, marcado como AWAITING_CONFIG")

        except Exception as e:
            logging.error(f"Error en registro de ESP32: {e}", exc_info=True)

    def _send_config(self, esp32_id: str, esp32_data: Dict[str, Any]):
        try:
            client_id = esp32_data.get('client_id')
            panel_id = esp32_data.get('panel_id')

            if not client_id or not panel_id:
                logging.error(f"ESP32 {esp32_id} no tiene asignación de cliente/panel")
                return

            panel_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
            panel_doc = panel_ref.get()

            if not panel_doc.exists:
                logging.error(f"Panel {panel_id} no encontrado para ESP32 {esp32_id}")
                return

            panel_data = panel_doc.to_dict()

            config = {
                'client_id': client_id,
                'panel_id': panel_id,
                'panel_name': panel_data.get('name', ''),
                'location': panel_data.get('location', '')
            }

            logging.info(f"Enviando configuración a ESP32 {esp32_id}: {config}")

            config_json = json.dumps(config, separators=(',', ':'))
            
            self.client.publish(
                f"esp32/config/{esp32_id}",
                config_json,
                qos=MQTT_CONFIG['QOS']
            )
            
            self._last_config_sent[esp32_id] = time.time()
            
            self._create_initial_relays(client_id, panel_id, esp32_id)
            
            logging.info(f"Configuración enviada exitosamente a ESP32 {esp32_id}")
            
        except Exception as e:
            logging.error(f"Error enviando configuración: {e}", exc_info=True)

    def _create_initial_relays(self, client_id: str, panel_id: str, esp32_id: str):
        try:
            relays_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}/relays')
            
            default_relays = [
                {'id': 'relay_1', 'name': 'Relay 1'},
                {'id': 'relay_2', 'name': 'Relay 2'},
                {'id': 'relay_3', 'name': 'Relay 3'},
                {'id': 'relay_4', 'name': 'Relay 4'},
                {'id': 'relay_5', 'name': 'Relay 5'},
                {'id': 'relay_6', 'name': 'Relay 6'}
            ]
            
            for relay in default_relays:
                relay_doc = relays_ref.document(relay['id'])
                
                if not relay_doc.get().exists:
                    relay_data = {
                        'name': relay['name'],
                        'customName': '',
                        'status': 'DISC',
                        'isActive': False,
                        'contactType': 'NO',
                        'date_time': datetime.now(pytz.timezone('America/Lima')).strftime('%d/%m/%Y, %H:%M'),
                        'lastUpdate': firestore.SERVER_TIMESTAMP,
                        'created': firestore.SERVER_TIMESTAMP
                    }
                    
                    relay_doc.set(relay_data)
                    logging.info(f"Relay {relay['id']} creado para panel {panel_id}")
            
            logging.info(f"Relays iniciales verificados para panel {panel_id} del cliente {client_id}")
            
        except Exception as e:
            logging.error(f"Error creando relays iniciales: {e}", exc_info=True)

    def _handle_config_response(self, esp32_id: str, payload: Dict[str, Any]):
        try:
            if payload.get('status') in ['SUCCESS', 'CONFIG_ACCEPTED']:
                esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
                esp32_ref.update({
                    'status': ESP32_STATES['ONLINE'],
                    'lastUpdate': datetime.now(pytz.UTC),
                    'lastConfigResponse': datetime.now(pytz.UTC)
                })
                logging.info(f"ESP32 {esp32_id} configurado exitosamente - Estado: ONLINE")
            else:
                error_msg = payload.get('message', 'Unknown error')
                logging.error(f"Error configurando ESP32 {esp32_id}: {error_msg}")
                esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
                esp32_ref.update({
                    'status': ESP32_STATES['ERROR'],
                    'lastUpdate': datetime.now(pytz.UTC),
                    'error_message': error_msg
                })

        except Exception as e:
            logging.error(f"Error procesando respuesta de configuración: {e}", exc_info=True)

    def _handle_status_update(self, esp32_id: str, payload: Dict[str, Any]):
        try:
            esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            esp32_doc = esp32_ref.get()

            if esp32_doc.exists:
                esp32_data = esp32_doc.to_dict()
                current_time = datetime.now(pytz.UTC)
                reported_status = payload.get('status')

                if not esp32_data.get('client_id') or not esp32_data.get('panel_id'):
                    existing_panel = self._find_existing_panel_assignment(esp32_id)
                    if existing_panel:
                        esp32_data.update(existing_panel)
                        esp32_ref.update(existing_panel)

                if esp32_data.get('client_id') and esp32_data.get('panel_id'):
                    if reported_status == ESP32_STATES['AWAITING_CONFIG']:
                        if self._should_send_config(esp32_id, esp32_data):
                            self._send_config(esp32_id, esp32_data)
                        updates = {'lastUpdate': current_time}
                    elif reported_status in [ESP32_STATES['ONLINE'], ESP32_STATES['ERROR']]:
                        updates = {
                            'lastUpdate': current_time,
                            'status': reported_status
                        }
                    else:
                        updates = {'lastUpdate': current_time}
                else:
                    updates = {
                        'lastUpdate': current_time,
                        'status': ESP32_STATES['AWAITING_CONFIG']
                    }
                
                esp32_ref.update(updates)
                logging.info(f"Estado de ESP32 {esp32_id} actualizado: {updates}")

        except Exception as e:
            logging.error(f"Error procesando actualización de estado: {e}", exc_info=True)

    def _setup_mqtt_client(self) -> mqtt.Client:
        try:
            self.client = mqtt.Client(
                client_id=MQTT_CONFIG['CLIENT_ID'], 
                clean_session=True,
                protocol=mqtt.MQTTv311
            )
            
            self.client.username_pw_set(MQTT_CONFIG['USER'], MQTT_CONFIG['PASSWORD'])
            
            context = ssl.create_default_context()
            context.load_verify_locations(MQTT_CONFIG['TLS_CA_CERTS'])
            context.check_hostname = False
            context.set_ciphers('ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384')
            
            self.client.tls_set_context(context)
            self.client.tls_insecure_set(False)
            
            self.client.on_connect = self._on_connect
            self.client.on_message = self._on_message
            self.client.on_disconnect = self._on_disconnect
            
            will_payload = json.dumps({
                "status": "OFFLINE",
                "client_id": self.client_id,
                "timestamp": int(time.time() * 1000)
            }).encode('utf-8')
            
            self.client.will_set(
                f"system/status/{self.client_id}",
                payload=will_payload,
                qos=MQTT_CONFIG['QOS'],
                retain=True
            )
            
            return self.client
                
        except Exception as e:
            logging.error(f"Error configurando cliente MQTT: {str(e)}", exc_info=True)
            raise

    def connect_and_loop(self):
        retry_count = 0
        max_retries = MQTT_CONFIG.get('MAX_RETRIES', 10)
        
        while True:
            try:
                if not self.connected:
                    logging.info(f"Intentando conexión MQTT a {MQTT_CONFIG['BROKER']}:{MQTT_CONFIG['PORT']}...")
                    
                    self.client.connect(
                        MQTT_CONFIG['BROKER'],
                        MQTT_CONFIG['PORT'],
                        keepalive=MQTT_CONFIG['KEEPALIVE']
                    )
                
                retry_count = 0
                self.client.loop_forever()
                
            except Exception as e:
                retry_count += 1
                delay = min(2 ** retry_count, MQTT_CONFIG.get('RECONNECT_DELAY_MAX', 60))
                
                logging.error(f"Error en conexión MQTT (intento {retry_count}/{max_retries}): {str(e)}")
                
                try:
                    self.client.disconnect()
                except:
                    pass
                
                self.connected = False
                time.sleep(delay)
                
                if retry_count >= max_retries:
                    logging.warning("Reiniciando cliente MQTT después de alcanzar máximo número de intentos...")
                    self.client = self._setup_mqtt_client()
                    retry_count = 0

    def _on_connect(self, client, userdata, flags, rc):
        if rc == 0:
            self.connected = True
            logging.info("Conectado al Broker MQTT!")
            
            online_payload = json.dumps({
                "status": "ONLINE",
                "client_id": self.client_id,
                "timestamp": int(time.time() * 1000)
            }).encode('utf-8')
            
            try:
                self.client.publish(
                    f"system/status/{self.client_id}",
                    payload=online_payload,
                    qos=MQTT_CONFIG['QOS'],
                    retain=True
                )
                
                topics = [
                    ("esp32/network_info", MQTT_CONFIG['QOS']),
                    ("esp32/register/+", MQTT_CONFIG['QOS']),
                    ("esp32/status/+", MQTT_CONFIG['QOS']),
                    ("esp32/config/+/response", MQTT_CONFIG['QOS'])
                ]
                
                for topic, qos in topics:
                    self.client.subscribe(topic, qos)
                    logging.info(f"Suscrito a: {topic}")
            except Exception as e:
                logging.error(f"Error en operaciones post-conexión: {e}", exc_info=True)
                self.connected = False
                self.client.disconnect()
        else:
            self.connected = False
            logging.error(f"Error de conexión MQTT, código: {rc}")

    def _on_message(self, client, userdata, msg):
        try:
            if msg.retain:
                logging.debug(f"Ignorando mensaje retain en {msg.topic}")
                return

            import json
            
            try:
                payload_str = msg.payload.decode().strip('[] ')
                payload = json.loads(payload_str)
                
                logging.debug(f"Mensaje recibido en {msg.topic}: {payload}")
                
                topic_parts = msg.topic.split('/')

                if msg.topic.startswith("esp32/network_info"):
                    esp32_id = payload.get('esp32_id')
                    if esp32_id:
                        network_info = {
                            'MAC': payload.get('MAC'),
                            'IP': payload.get('IP'),
                            'status': payload.get('status', 'ONLINE')
                        }
                        logging.info(f"Procesando network_info de ESP32 {esp32_id}")
                        self._handle_registration(esp32_id, network_info)
                        
                elif topic_parts[0] == "esp32":
                    if topic_parts[1] == "status" and len(topic_parts) > 2:
                        self._handle_status_update(topic_parts[2], payload)
                    elif topic_parts[1] == "config" and len(topic_parts) > 3 and topic_parts[3] == "response":
                        self._handle_config_response(topic_parts[2], payload)

            except Exception as e:
                logging.error(f"Error procesando mensaje MQTT: {e}", exc_info=True)
                
        except Exception as e:
            logging.error(f"Error en _on_message: {e}", exc_info=True)

    def _on_disconnect(self, client, userdata, rc):
        self.connected = False
        if rc != 0:
            logging.warning(f"Desconexión inesperada del broker MQTT: {rc}")

    def _find_existing_panel_assignment(self, esp32_id: str) -> Optional[Dict[str, str]]:
        try:
            clients_ref = self.db.collection('hdd-monitor/accounts/clients')
            for client in clients_ref.stream():
                panels_ref = client.reference.collection('panels')
                query = panels_ref.where('esp32_id', '==', esp32_id)
                panels = query.stream()
                
                for panel in panels:
                    panel_data = panel.to_dict()
                    logging.info(f"Panel encontrado para ESP32 {esp32_id}: {panel.id} en cliente {client.id}")
                    return {
                        'client_id': client.id,
                        'panel_id': panel.id
                    }
            return None
        except Exception as e:
            logging.error(f"Error buscando asignación de panel: {e}", exc_info=True)
            return None

    def assign_panel(self, esp32_id: str, client_id: str, panel_id: str):
        try:
            esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            esp32_doc = esp32_ref.get()

            if not esp32_doc.exists:
                raise ValueError(f"ESP32 {esp32_id} no encontrado")

            panel_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
            if not panel_ref.get().exists:
                raise ValueError(f"Panel {panel_id} no encontrado")

            esp32_ref.update({
                'client_id': client_id,
                'panel_id': panel_id,
                'status': ESP32_STATES['AWAITING_CONFIG'],
                'lastUpdate': datetime.now(pytz.UTC)
            })

            if esp32_id in self._last_config_sent:
                del self._last_config_sent[esp32_id]

            logging.info(f"ESP32 {esp32_id} asignado al panel {panel_id} del cliente {client_id}")

        except Exception as e:
            logging.error(f"Error asignando panel: {e}", exc_info=True)
            raise

    def start(self):
        try:
            self.client.connect(
                MQTT_CONFIG['BROKER'],
                MQTT_CONFIG['PORT'],
                keepalive=MQTT_CONFIG['KEEPALIVE']
            )
            
            self.client.loop_start()
            
            self._check_pending_configurations()
            
            self._watch_panel_assignments()
            self._watch_esp32_deletions()
            
            logging.info("Gestor de configuración ESP32 iniciado")
            
        except Exception as e:
            logging.error(f"Error iniciando gestor de configuración: {e}", exc_info=True)
            raise

    def stop(self):
        try:
            for watch in self._watch_references:
                try:
                    watch.unsubscribe()
                except Exception as e:
                    logging.error(f"Error deteniendo observador: {e}")
            self._watch_references.clear()
            
            if self.client:
                try:
                    self.client.loop_stop()
                    self.client.disconnect()
                except Exception as e:
                    logging.error(f"Error desconectando cliente MQTT: {e}")
            logging.info("Gestor de configuración ESP32 detenido")
        except Exception as e:
            logging.error(f"Error deteniendo gestor de configuración: {e}", exc_info=True)

    def _watch_esp32_deletions(self):
        try:
            esp32s_ref = self.db.collection('hdd-monitor/esp32/registered')
            
            def on_snapshot(doc_snapshot, changes, read_time):
                for change in changes:
                    try:
                        if change.type.name == 'REMOVED':
                            esp32_id = change.document.id
                            logging.info(f"ESP32 {esp32_id} eliminado de la base de datos")
                            
                            if esp32_id in self._last_config_sent:
                                del self._last_config_sent[esp32_id]
                            
                            delete_message = json.dumps({
                                'action': 'DELETED',
                                'timestamp': int(time.time() * 1000)
                            })
                            
                            self.client.publish(
                                f"esp32/notify/{esp32_id}/deleted",
                                delete_message,
                                qos=MQTT_CONFIG['QOS']
                            )
                            
                            logging.info(f"Notificación de eliminación enviada a ESP32 {esp32_id}")
                                
                    except Exception as e:
                        logging.error(f"Error procesando eliminación de ESP32: {e}")

            query_watch = esp32s_ref.on_snapshot(on_snapshot)
            self._watch_references.append(query_watch)
            logging.info("Observador de eliminaciones de ESP32 iniciado")
            
        except Exception as e:
            logging.error(f"Error iniciando observador de eliminaciones: {e}", exc_info=True)

    def _watch_panel_assignments(self):
        try:
            esp32s_ref = self.db.collection('hdd-monitor/esp32/registered')
            
            def on_snapshot(doc_snapshot, changes, read_time):
                for change in changes:
                    try:
                        if change.type.name == 'MODIFIED':
                            esp32_data = change.document.to_dict()
                            esp32_id = change.document.id
                            
                            if (esp32_data.get('client_id') and 
                                esp32_data.get('panel_id') and
                                esp32_data.get('status') == ESP32_STATES['AWAITING_CONFIG']):
                                
                                if self._should_send_config(esp32_id, esp32_data):
                                    logging.info(f"Detectada nueva asignación para ESP32 {esp32_id}")
                                    self._send_config(esp32_id, esp32_data)
                                
                    except Exception as e:
                        logging.error(f"Error procesando cambio de panel: {e}")

            query_watch = esp32s_ref.on_snapshot(on_snapshot)
            self._watch_references.append(query_watch)
            logging.info("Observador de asignaciones de paneles iniciado")
            
        except Exception as e:
            logging.error(f"Error iniciando observador de paneles: {e}", exc_info=True)

    def _check_pending_configurations(self):
        try:
            esp32s_ref = self.db.collection('hdd-monitor/esp32/registered')
            esp32s = esp32s_ref.stream()

            for esp32_doc in esp32s:
                try:
                    esp32_data = esp32_doc.to_dict()
                    esp32_id = esp32_doc.id
                    
                    if not esp32_data.get('client_id') or not esp32_data.get('panel_id'):
                        existing_panel = self._find_existing_panel_assignment(esp32_id)
                        if existing_panel:
                            esp32_data.update(existing_panel)
                            esp32_doc.reference.update(existing_panel)
                    
                    if (esp32_data.get('client_id') and 
                        esp32_data.get('panel_id') and 
                        esp32_data.get('status') == ESP32_STATES['AWAITING_CONFIG']):
                        
                        if self._should_send_config(esp32_id, esp32_data):
                            logging.info(f"Enviando configuración pendiente a ESP32 {esp32_id}")
                            self._send_config(esp32_id, esp32_data)
                        
                except Exception as e:
                    logging.error(f"Error procesando ESP32 {esp32_doc.id}: {e}")
                    
        except Exception as e:
            logging.error(f"Error verificando configuraciones pendientes: {e}", exc_info=True)

if __name__ == '__main__':
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(levelname)s - %(message)s'
    )
    
    config_manager = ESP32ConfigManager()
    
    try:
        config_manager.start()
        
        while True:
            time.sleep(1)
            
    except KeyboardInterrupt:
        logging.info("Deteniendo gestor de configuración...")
        config_manager.stop()
    except Exception as e:
        logging.error(f"Error fatal: {e}", exc_info=True)
        config_manager.stop()