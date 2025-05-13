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
# Importar la configuración
from config import ESP32_CONFIG_MQTT as MQTT_CONFIG

# Estados del ESP32
ESP32_STATES = {
    'AWAITING_CONFIG': 'AWAITING_CONFIG',
    'CONFIGURED': 'CONFIGURED',
    'ERROR': 'ERROR',
    'OK': 'OK',
    'DISC': 'DISC',  # Estado de desconexión
    'REGISTERED': 'REGISTERED'  # Estado para configuración inicial
}

# Estados de relay
RELAY_STATES = {
    'OK': 'OK',
    'DISC': 'DISC',
    'ERROR': 'ERROR'
}

# Estados por defecto
DEFAULT_STATUS = 'OK'
DEFAULT_RELAY_STATUS = RELAY_STATES['DISC']  # Estado inicial de los relays
DEFAULT_ESP32_STATUS = ESP32_STATES['AWAITING_CONFIG']

class ESP32ConfigManager:
    def __init__(self):
        # Inicializar client_id primero
        self.client_id = MQTT_CONFIG['CLIENT_ID']
        self.connected = False
        self.client = None
        
        # Inicializar Firestore
        self.db = firestore.Client(project='fir-hdd-monitor-d00de')
        
        # Inicializar cliente MQTT
        self.client = self._setup_mqtt_client()
        
        # Caché de configuraciones
        self._config_cache = {}
        self._esp32_status = {}

        # Lista para mantener referencias de observadores
        self._watch_references = []

    def _handle_registration(self, esp32_id: str, payload: Dict[str, Any]):
        """Maneja el registro inicial de un ESP32"""
        try:
            esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            esp32_doc = esp32_ref.get()
            current_time = datetime.now(pytz.UTC)

            if not esp32_doc.exists:
                # Para nuevo ESP32, solo registrar información básica
                esp32_data = {
                    'MAC': payload.get('MAC', ''),
                    'IP': payload.get('IP', ''),
                    'firstSeen': current_time,
                    'lastUpdate': current_time,
                    'status': ESP32_STATES['AWAITING_CONFIG']
                }
                esp32_ref.set(esp32_data)
                logging.info(f"Nuevo ESP32 registrado: {esp32_id}")
                
            else:
                # Para ESP32 existente
                esp32_data = esp32_doc.to_dict()
                
                # Verificar si ya tiene asignación
                if esp32_data.get('client_id') and esp32_data.get('panel_id'):
                    self._send_config(esp32_id, esp32_data)
                else:
                    # Solo actualizar información básica
                    updates = {
                        'IP': payload.get('IP', ''),
                        'lastUpdate': current_time,
                        'status': ESP32_STATES['AWAITING_CONFIG']
                    }
                    esp32_ref.update(updates)
                    logging.info(f"ESP32 {esp32_id} actualizado con: {updates}")

        except Exception as e:
            logging.error(f"Error en registro de ESP32: {e}", exc_info=True)

    def _send_config(self, esp32_id: str, esp32_data: Dict[str, Any]):
        """Envía la configuración al ESP32"""
        try:
            client_id = esp32_data.get('client_id')
            panel_id = esp32_data.get('panel_id')

            if not client_id or not panel_id:
                logging.error(f"ESP32 {esp32_id} no tiene asignación de cliente/panel")
                return

            # Obtener configuración del panel
            panel_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
            panel_doc = panel_ref.get()

            if not panel_doc.exists:
                logging.error(f"Panel {panel_id} no encontrado para ESP32 {esp32_id}")
                return

            panel_data = panel_doc.to_dict()

            # Obtener configuración de relays
            relays_ref = panel_ref.collection('relays')
            relays = {}
            for relay_doc in relays_ref.stream():
                relay_data = relay_doc.to_dict()
                status = relay_data.get('status')
                # Si no hay estado o el estado no es válido, usar el estado por defecto
                if not status or status not in RELAY_STATES:
                    status = DEFAULT_RELAY_STATUS
                relays[relay_doc.id] = {
                    'name': relay_data.get('name', relay_doc.id),
                    'status': status
                }

            # Preparar configuración
            config = {
                'client_id': client_id,
                'panel_id': panel_id,
                'panel_name': panel_data.get('name', ''),
                'location': panel_data.get('location', ''),
                'status': ESP32_STATES['REGISTERED'],  # Estado inicial para configuración
                'relays': relays,
                'mqtt': {
                    'broker': MQTT_CONFIG['BROKER'],
                    'port': MQTT_CONFIG['PORT'],
                    'user': MQTT_CONFIG['USER'],
                    'password': MQTT_CONFIG['PASSWORD'],
                    'client_id': f"esp32_{esp32_id}",
                    'topics': {
                        'status': f"clients/{client_id}/panels/{panel_id}/status",
                        'relays': f"clients/{client_id}/panels/{panel_id}/relays",
                        'config': f"esp32/config/{esp32_id}"
                    }
                }
            }

            # Log de la configuración preparada
            logging.info(f"Preparando configuración para ESP32 {esp32_id}: {json.dumps(config, indent=2)}")

            # Actualizar caché
            self._config_cache[esp32_id] = (time.time(), config)

            # Log detallado antes de enviar
            config_json = json.dumps(config)
            logging.info(f"Configuración JSON a enviar: {config_json}")
            
            # Verificar que el campo status está presente
            parsed_config = json.loads(config_json)
            if 'status' not in parsed_config:
                logging.error("Campo 'status' no presente en la configuración JSON")
            
            # Enviar configuración
            self.client.publish(
                f"esp32/config/{esp32_id}",
                config_json,
                qos=MQTT_CONFIG['QOS']
            )
            
            # Actualizar estado en Firestore
            esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            esp32_ref.update({
                'status': ESP32_STATES['CONFIGURED'],
                'lastUpdate': datetime.now(pytz.UTC)
            })
            
            logging.info(f"Configuración enviada a ESP32 {esp32_id}")

        except Exception as e:
            logging.error(f"Error enviando configuración: {e}", exc_info=True)

    def _handle_config_response(self, esp32_id: str, payload: Dict[str, Any]):
        """Maneja respuestas a la configuración enviada"""
        try:
            # Aceptar tanto 'SUCCESS' como 'CONFIG_ACCEPTED'
            if payload.get('status') in ['SUCCESS', 'CONFIG_ACCEPTED']:
                esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
                esp32_ref.update({
                    'status': ESP32_STATES['CONFIGURED'],
                    'lastUpdate': datetime.now(pytz.UTC)
                })
                logging.info(f"ESP32 {esp32_id} configurado exitosamente")
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
        """Maneja actualizaciones de estado de los ESP32"""
        try:
            esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            esp32_doc = esp32_ref.get()

            if esp32_doc.exists:
                esp32_data = esp32_doc.to_dict()
                current_time = datetime.now(pytz.UTC)

                # Si no tiene asignación, buscar una existente
                if not esp32_data.get('client_id') or not esp32_data.get('panel_id'):
                    existing_panel = self._find_existing_panel_assignment(esp32_id)
                    if existing_panel:
                        esp32_data.update(existing_panel)
                        esp32_ref.update(existing_panel)

                # Actualizar estado
                updates = {
                    'lastUpdate': current_time,
                    'status': payload.get('status', esp32_data.get('status'))
                }
                esp32_ref.update(updates)
                logging.info(f"Estado de ESP32 {esp32_id} actualizado: {updates}")

                # Verificar si necesita configuración
                if ((payload.get('status') == ESP32_STATES['AWAITING_CONFIG'] or 
                    not payload.get('status')) and 
                    esp32_data.get('client_id') and 
                    esp32_data.get('panel_id')):
                    self._send_config(esp32_id, esp32_data)

        except Exception as e:
            logging.error(f"Error procesando actualización de estado: {e}", exc_info=True)

    def _setup_mqtt_client(self) -> mqtt.Client:
        """Configura y retorna el cliente MQTT"""
        try:
            self.client = mqtt.Client(
                client_id=MQTT_CONFIG['CLIENT_ID'], 
                clean_session=True,
                protocol=mqtt.MQTTv311
            )
            
            # Configurar credenciales
            self.client.username_pw_set(MQTT_CONFIG['USER'], MQTT_CONFIG['PASSWORD'])
            
            # Configurar TLS con más opciones de seguridad
            context = ssl.create_default_context()
            context.load_verify_locations(MQTT_CONFIG['TLS_CA_CERTS'])
            context.check_hostname = False
            context.set_ciphers('ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384')
            
            self.client.tls_set_context(context)
            self.client.tls_insecure_set(False)
            
            # Configurar callbacks
            self.client.on_connect = self._on_connect
            self.client.on_message = self._on_message
            self.client.on_disconnect = self._on_disconnect
            
            # Will message
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
        """Maneja la conexión y reconexión"""
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
        """Callback para cuando se establece la conexión MQTT"""
        if rc == 0:
            self.connected = True
            logging.info("Conectado al Broker MQTT!")
            
            # Publicar estado online
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
                
                # Suscribirse a tópicos
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
        """Procesa mensajes MQTT recibidos"""
        try:
            if msg.retain:
                logging.info(f"Ignorando mensaje retain en {msg.topic}")
                return

            import json
            
            try:
                # Intentar decodificar el mensaje como JSON directamente
                payload_str = msg.payload.decode().strip('[] ')
                payload = json.loads(payload_str)
                
                logging.info(f"Mensaje recibido en {msg.topic}: {payload}")
                
                topic_parts = msg.topic.split('/')

                if msg.topic.startswith("esp32/network_info"):
                    # Procesar mensaje de información de red
                    esp32_id = payload.get('esp32_id')
                    if esp32_id:
                        network_info = {
                            'MAC': payload.get('MAC'),
                            'IP': payload.get('IP'),
                            'status': payload.get('status')
                        }
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
        """Callback para cuando se desconecta del broker MQTT"""
        if rc != 0:
            logging.warning(f"Desconexión inesperada del broker MQTT: {rc}")

    def _find_existing_panel_assignment(self, esp32_id: str) -> Optional[Dict[str, str]]:
        """Busca si el ESP32 está asignado a algún panel"""
        try:
            # Buscar en todos los clientes
            clients_ref = self.db.collection('hdd-monitor/accounts/clients')
            for client in clients_ref.stream():
                # Buscar en todos los paneles del cliente
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
        """Asigna un ESP32 a un panel específico"""
        try:
            # Verificar que el ESP32 existe
            esp32_ref = self.db.document(f'esp32/registered/{esp32_id}')
            esp32_doc = esp32_ref.get()

            if not esp32_doc.exists:
                raise ValueError(f"ESP32 {esp32_id} no encontrado")

            # Verificar que el panel existe
            panel_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
            if not panel_ref.get().exists:
                raise ValueError(f"Panel {panel_id} no encontrado")

            # Actualizar asignación
            esp32_ref.update({
                'client_id': client_id,
                'panel_id': panel_id,
                'lastUpdate': datetime.now(pytz.UTC)
            })

            # Enviar nueva configuración
            esp32_data = esp32_doc.to_dict()
            esp32_data.update({'client_id': client_id, 'panel_id': panel_id})
            self._send_config(esp32_id, esp32_data)

            logging.info(f"ESP32 {esp32_id} asignado al panel {panel_id} del cliente {client_id}")

        except Exception as e:
            logging.error(f"Error asignando panel: {e}", exc_info=True)
            raise

    def start(self):
        """Inicia el gestor de configuración"""
        try:
            # Conectar al broker MQTT
            self.client.connect(
                MQTT_CONFIG['BROKER'],
                MQTT_CONFIG['PORT'],
                keepalive=MQTT_CONFIG['KEEPALIVE']
            )
            
            # Iniciar loop en segundo plano
            self.client.loop_start()
            
            # Verificar ESP32s que necesitan configuración
            self._check_pending_configurations()
            
            # Añadir observador para nuevas asignaciones de paneles
            self._watch_panel_assignments()
            
            logging.info("Gestor de configuración ESP32 iniciado")
            
        except Exception as e:
            logging.error(f"Error iniciando gestor de configuración: {e}", exc_info=True)
            raise

    def stop(self):
        """Detiene el gestor de configuración"""
        try:
            # Detener todos los observadores
            for watch in self._watch_references:
                try:
                    watch.unsubscribe()
                except Exception as e:
                    logging.error(f"Error deteniendo observador: {e}")
            self._watch_references.clear()
            
            # Detener cliente MQTT
            if self.client:
                try:
                    self.client.loop_stop()
                    self.client.disconnect()
                except Exception as e:
                    logging.error(f"Error desconectando cliente MQTT: {e}")
            logging.info("Gestor de configuración ESP32 detenido")
        except Exception as e:
            logging.error(f"Error deteniendo gestor de configuración: {e}", exc_info=True)

    def _watch_panel_assignments(self):
        """Observa cambios en asignaciones de paneles"""
        try:
            # Obtener ESP32s en estado AWAITING_CONFIG
            esp32s_ref = self.db.collection('hdd-monitor/esp32/registered')
            query = esp32s_ref.where('status', '==', ESP32_STATES['AWAITING_CONFIG'])
            
            def on_snapshot(doc_snapshot, changes, read_time):
                for change in changes:
                    try:
                        if change.type.name == 'MODIFIED':
                            esp32_data = change.document.to_dict()
                            esp32_id = change.document.id
                            
                            # Si se asignó a un panel
                            if esp32_data.get('client_id') and esp32_data.get('panel_id'):
                                logging.info(f"Detectada nueva asignación para ESP32 {esp32_id}")
                                self._send_config(esp32_id, esp32_data)
                                
                    except Exception as e:
                        logging.error(f"Error procesando cambio de panel: {e}")

            # Iniciar observador
            query_watch = query.on_snapshot(on_snapshot)
            self._watch_references.append(query_watch)
            logging.info("Observador de asignaciones de paneles iniciado")
            
        except Exception as e:
            logging.error(f"Error iniciando observador de paneles: {e}", exc_info=True)

    def _check_pending_configurations(self):
        """Verifica ESP32s que necesitan configuración al inicio"""
        try:
            # Corregir la ruta de acceso a la colección 'registered'
            esp32s_ref = self.db.collection('esp32/registered/documents')
            esp32s = esp32s_ref.stream()

            for esp32_doc in esp32s:
                try:
                    esp32_data = esp32_doc.to_dict()
                    esp32_id = esp32_doc.id
                    
                    # Si no tiene asignación, buscar una existente
                    if not esp32_data.get('client_id') or not esp32_data.get('panel_id'):
                        existing_panel = self._find_existing_panel_assignment(esp32_id)
                        if existing_panel:
                            esp32_data.update(existing_panel)
                            esp32_doc.reference.update(existing_panel)
                    
                    # Si tiene asignación y necesita configuración, enviarla
                    if (esp32_data.get('client_id') and 
                        esp32_data.get('panel_id') and 
                        esp32_data.get('status') in ['AWAITING_CONFIG', None]):
                        self._send_config(esp32_id, esp32_data)
                        
                except Exception as e:
                    logging.error(f"Error procesando ESP32 {esp32_doc.id}: {e}")
                    
        except Exception as e:
            logging.error(f"Error verificando configuraciones pendientes: {e}", exc_info=True)

if __name__ == '__main__':
    # Configuración de logging
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(levelname)s - %(message)s'
    )
    
    # Iniciar gestor
    config_manager = ESP32ConfigManager()
    
    try:
        config_manager.start()
        
        # Mantener el script corriendo
        while True:
            time.sleep(1)
            
    except KeyboardInterrupt:
        logging.info("Deteniendo gestor de configuración...")
        config_manager.stop()
    except Exception as e:
        logging.error(f"Error fatal: {e}", exc_info=True)
        config_manager.stop()