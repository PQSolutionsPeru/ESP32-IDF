from google.cloud import firestore
import firebase_admin
from firebase_admin import credentials, messaging
import logging
from typing import Dict, Any, Optional
from datetime import datetime
import pytz
from notification_handler import NotificationHandler
from mqtt_client import MQTTClient
import json

class FirestoreHandler:
    def __init__(self):
        # Primero inicializar Firebase Admin con las credenciales
        creds = firebase_admin.credentials.Certificate('/home/pqsolutionsperu/vm-service-key.json')
        if not firebase_admin._apps:
            firebase_admin.initialize_app(creds)
        
        # Luego inicializar Firestore
        self.db = firestore.Client(
            project='fir-hdd-monitor-d00de',
            credentials=creds.get_credential()
        )
            
        self.notification_handler = NotificationHandler(self.db)
        
        # Iniciar cliente MQTT con referencia a la base de datos
        self.mqtt_client = MQTTClient(self.handle_mqtt_message, db=self.db)
        
        self._watch_references = []
        self._relay_states = {}
        self._initial_load_complete = False
        self._events_initial_snapshots = set()

        # Iniciar todos los observadores
        try:
            self.watch_events()
            self.watch_relay_states()
            self.watch_esp32_updates()  # Nuevo observador para OTA
        except Exception as e:
            logging.error(f"Error iniciando observadores: {e}", exc_info=True)
            raise

    def handle_mqtt_message(self, msg):
        """Maneja los mensajes MQTT recibidos"""
        try:
            if msg.retain:
                logging.info(f"Ignorando mensaje retain en {msg.topic}")
                return

            payload = json.loads(msg.payload.decode())
            if not payload:
                return

            # Manejar diferentes tipos de mensajes
            if msg.topic.startswith("clients/") and "panels" in msg.topic:
                self.handle_panel_message(msg.topic, payload)
            elif msg.topic.startswith("esp32/ota/"):
                self.handle_ota_message(msg.topic, payload)
            
        except Exception as e:
            logging.error(f"Error procesando mensaje: {e}", exc_info=True)

    def handle_ota_message(self, topic: str, payload: Dict[str, Any]):
        """Maneja mensajes relacionados con actualizaciones OTA"""
        try:
            parts = topic.split('/')
            if len(parts) < 3:
                return
                
            esp32_id = parts[2]
            esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            
            update_data = {
                'lastUpdate': firestore.SERVER_TIMESTAMP,
                'otaStatus': payload.get('status'),
                'otaMessage': payload.get('message'),
                'otaProgress': payload.get('progress', 0)
            }
            
            # Si la actualización fue exitosa
            if payload.get('status') == 'success':
                update_data['firmwareVersion'] = payload.get('version')
                
            esp32_ref.update(update_data)
            logging.info(f"Estado OTA actualizado para ESP32 {esp32_id}: {update_data}")
            
        except Exception as e:
            logging.error(f"Error procesando mensaje OTA: {e}", exc_info=True)

    def _cache_relay_state(self, relay_path: str, state: Dict[str, Any]):
        """Almacena el estado de un relay en el caché local"""
        self._relay_states[relay_path] = {
            'status': state.get('status'),
            'lastUpdate': state.get('lastUpdate'),
            'name': state.get('name', '')
        }

    def _get_cached_relay_state(self, relay_path: str) -> Optional[Dict[str, Any]]:
        """Obtiene el estado cacheado de un relay"""
        return self._relay_states.get(relay_path)

    def watch_events(self):
        """Observa cambios en documentos de eventos para cada cliente"""
        try:
            # Obtener todos los clientes
            clients_ref = self.db.collection('hdd-monitor/accounts/clients')
            clients = clients_ref.stream()

            for client in clients:
                def create_snapshot_handler(client_id):
                    snapshot_key = f"events_{client_id}"
                    initial_snapshot_processed = False
                    last_snapshot = {}  # Almacenar el snapshot anterior
                    
                    def on_snapshot(doc_snapshot, changes, read_time):
                        nonlocal initial_snapshot_processed, last_snapshot
                        
                        # Solo loggear la carga inicial una vez
                        if not initial_snapshot_processed:
                            initial_snapshot_processed = True
                            # Guardar el estado inicial de los documentos
                            for doc in doc_snapshot:
                                last_snapshot[doc.id] = doc.to_dict()
                            logging.info(f"Carga inicial de eventos para cliente {client_id}")
                            return

                        # Procesar todos los cambios después de la carga inicial
                        for change in changes:
                            try:
                                doc = change.document
                                new_data = doc.to_dict() if change.type.name != 'REMOVED' else None
                                old_data = last_snapshot.get(doc.id, {})

                                if change.type.name == 'ADDED':
                                    if new_data:
                                        logging.info(f"Nuevo evento detectado:")
                                        logging.info(f"ID: {doc.id}")
                                        logging.info(f"Estado inicial: {new_data.get('status')}")
                                        self.notification_handler.process_event_update(
                                            doc.reference,
                                            {},
                                            new_data
                                        )
                                        last_snapshot[doc.id] = new_data
                                
                                elif change.type.name == 'MODIFIED':
                                    logging.info(f"Evento modificado detectado:")
                                    logging.info(f"ID: {doc.id}")
                                    logging.info(f"Estado anterior: {old_data.get('status')}")
                                    logging.info(f"Nuevo estado: {new_data.get('status')}")
                                    
                                    self.notification_handler.process_event_update(
                                        doc.reference,
                                        old_data,
                                        new_data
                                    )
                                    last_snapshot[doc.id] = new_data
                                
                                elif change.type.name == 'REMOVED':
                                    logging.info(f"Evento eliminado detectado: {doc.id}")
                                    self.notification_handler.process_event_update(
                                        doc.reference,
                                        last_snapshot.get(doc.id, {}),
                                        None
                                    )
                                    last_snapshot.pop(doc.id, None)
                                                
                            except Exception as e:
                                logging.error(f"Error procesando cambio de evento: {e}", exc_info=True)

                        # Actualizar el snapshot con el estado actual de todos los documentos
                        current_snapshot = {doc.id: doc.to_dict() for doc in doc_snapshot}
                        last_snapshot.update(current_snapshot)
                        
                    return on_snapshot

                # Observar colección de eventos de cada cliente
                events_ref = clients_ref.document(client.id).collection('events')
                watch = events_ref.on_snapshot(create_snapshot_handler(client.id))
                self._watch_references.append(watch)
                logging.info(f"Observador de eventos iniciado para cliente {client.id}")

        except Exception as e:
            logging.error(f"Error iniciando observadores de eventos: {e}", exc_info=True)

    def watch_relay_states(self):
        """Observa cambios en estados de relays en todos los paneles"""
        try:
            # Obtener todos los clientes
            clients_ref = self.db.collection('hdd-monitor/accounts/clients')
            clients = clients_ref.stream()

            for client in clients:
                # Obtener todos los paneles de cada cliente
                panels_ref = clients_ref.document(client.id).collection('panels')
                panels = panels_ref.stream()

                for panel in panels:
                    # Obtener estado inicial de los relays
                    relays_ref = panels_ref.document(panel.id).collection('relays')
                    for relay_doc in relays_ref.stream():
                        self._relay_states[relay_doc.reference.path] = relay_doc.to_dict()

                    # Observar colección de relays de cada panel
                    watch = relays_ref.on_snapshot(self._on_relay_snapshot)
                    self._watch_references.append(watch)
                    logging.info(f"Observador de relays iniciado para panel {panel.id} del cliente {client.id}")

        except Exception as e:
            logging.error(f"Error iniciando observadores de relays: {e}", exc_info=True)

    def watch_esp32_updates(self):
        """Observa actualizaciones de estado de ESP32s"""
        try:
            # Obtener referencia a la colección de ESP32s
            esp32s_ref = self.db.collection('hdd-monitor/esp32/registered')
            
            def on_snapshot(doc_snapshot, changes, read_time):
                for change in changes:
                    try:
                        if change.type.name == 'MODIFIED':
                            doc = change.document
                            new_data = doc.to_dict()
                            old_data = change.old_snapshot.to_dict() if hasattr(change, 'old_snapshot') else {}
                            
                            # Verificar si hay cambios en estado OTA
                            if (new_data.get('otaStatus') != old_data.get('otaStatus') or 
                                new_data.get('otaProgress') != old_data.get('otaProgress')):
                                
                                self.notification_handler.process_ota_update(
                                    doc.reference,
                                    old_data,
                                    new_data
                                )
                                
                    except Exception as e:
                        logging.error(f"Error procesando cambio en ESP32: {e}", exc_info=True)
            
            # Iniciar observador
            watch = esp32s_ref.on_snapshot(on_snapshot)
            self._watch_references.append(watch)
            logging.info("Observador de ESP32s iniciado")
            
        except Exception as e:
            logging.error(f"Error iniciando observador de ESP32s: {e}", exc_info=True)

    def _on_relay_snapshot(self, doc_snapshot, changes, read_time):
        """Maneja cambios en los relays"""
        for change in changes:
            try:
                if change.type.name == 'MODIFIED':
                    doc = change.document
                    new_data = doc.to_dict()
                    doc_path = doc.reference.path
                    
                    # Obtener estado anterior del caché
                    old_data = self._relay_states.get(doc_path)
                    
                    if old_data is None:
                        logging.error(f"Error crítico: Estado no encontrado en caché para relay {doc.id}")
                        return
                    
                    logging.info(f"Cambio detectado en relay: {doc.id}")
                    logging.info(f"Estado anterior: {old_data.get('status')}")
                    logging.info(f"Nuevo estado: {new_data.get('status')}")
                    
                    # Solo procesar si el cambio viene de MQTT (source == 'mqtt')
                    if new_data.get('source') == 'mqtt' and old_data.get('status') != new_data.get('status'):
                        self.notification_handler.process_relay_update(doc.reference, old_data, new_data)
                    
                    # Actualizar caché con el nuevo estado
                    self._relay_states[doc_path] = new_data
                    
            except Exception as e:
                logging.error(f"Error procesando cambio de relay: {e}", exc_info=True)

    def handle_panel_message(self, topic: str, payload: Dict[str, Any]):
        """Maneja mensajes MQTT de paneles"""
        try:
            parts = topic.split('/')
            if len(parts) < 4 or parts[0] != "clients" or parts[2] != "panels":
                logging.warning(f"Formato de tópico inválido: {topic}")
                return

            client_id = parts[1]
            panel_id = parts[3]
            
            if 'relay' in payload and 'state' in payload:
                self._update_relay_state(client_id, panel_id, payload)
                
        except Exception as e:
            logging.error(f"Error en handle_panel_message: {e}", exc_info=True)

    def _update_relay_state(self, client_id: str, panel_id: str, payload: Dict[str, Any]):
        """Actualiza el estado de un relay"""
        try:
            relay_name = payload['relay']
            new_state = payload['state']
            
            panel_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
            relay_ref = panel_ref.collection('relays').document(relay_name)
            
            # Verificar panel
            panel_snap = panel_ref.get()
            if not panel_snap.exists:
                logging.error(f"Panel no encontrado: {panel_id}")
                return
                    
            # Obtener estado anterior
            relay_snap = relay_ref.get()
            old_data = relay_snap.to_dict() if relay_snap.exists else {'status': None}
            
            # Solo actualizar si el estado es diferente
            if old_data.get('status') != new_state:
                # Actualizar estado
                new_data = {
                    'status': new_state,
                    'date_time': datetime.now(pytz.timezone('America/Bogota')).strftime('%d/%m/%Y, %H:%M'),
                    'lastUpdate': firestore.SERVER_TIMESTAMP,
                    'source': 'mqtt'
                }
                
                # Logear el cambio para debugging
                logging.info(f"Cambio de estado en relay {relay_name}:")
                logging.info(f"Estado anterior en BD: {old_data.get('status')}")
                logging.info(f"Nuevo estado del ESP32: {new_state}")
                
                # Actualizar en BD
                relay_ref.set(new_data, merge=True)
                logging.info(f"Estado actualizado para relay {relay_name}")
                    
        except Exception as e:
            logging.error(f"Error en _update_relay_state: {e}", exc_info=True)

    def cleanup(self):
        """Limpia los observadores al cerrar"""
        for watch in self._watch_references:
            try:
                watch.unsubscribe()
            except Exception as e:
                logging.error(f"Error al limpiar observador: {e}")
        self._watch_references.clear()
        
        # Limpiar MQTT si existe
        if hasattr(self, 'mqtt_client'):
            try:
                self.mqtt_client.cleanup()
            except Exception as e:
                logging.error(f"Error limpiando cliente MQTT: {e}")