from google.cloud import firestore
import firebase_admin
from firebase_admin import credentials, messaging
import logging
from typing import Dict, Any, Optional
from datetime import datetime, timedelta
import pytz
from notification_handler import NotificationHandler
from mqtt_client import MQTTClient
import json
import time
import threading
import paho.mqtt.client as mqtt

class EventReminderChecker:
    def __init__(self, db: firestore.Client, notification_handler: NotificationHandler):
        self.db = db
        self.notification_handler = notification_handler
        self.peru_timezone = pytz.timezone('America/Lima')
        self.date_formatter = "%d-%m-%Y %H:%M"
        self.reminder_minutes = 60
        self.check_interval = 60
        self.processed_events = set()
        self.maintenance_counter = 0
        self.maintenance_interval = 100
        self.running = True

    def get_current_time(self) -> datetime:
        return datetime.now(self.peru_timezone)

    def parse_event_datetime(self, date_time_str: str) -> datetime:
        try:
            dt = datetime.strptime(date_time_str, self.date_formatter)
            return self.peru_timezone.localize(dt)
        except Exception as e:
            logging.error(f"Error analizando fecha de evento '{date_time_str}': {e}")
            return self.get_current_time() - timedelta(days=1)

    def should_notify(self, event_datetime: datetime, current_time: datetime) -> bool:
        reminder_time = event_datetime - timedelta(minutes=self.reminder_minutes)
        time_diff = (current_time - reminder_time).total_seconds()
        return 0 <= time_diff <= 120

    def check_upcoming_events(self):
        try:
            current_time = self.get_current_time()
            logging.info(f"Verificando eventos próximos a las {current_time.strftime('%d/%m/%Y, %H:%M:%S')}")
            
            clients_ref = self.db.collection('hdd-monitor/accounts/clients')
            clients = clients_ref.stream()
            
            for client in clients:
                client_id = client.id
                try:
                    events_ref = clients_ref.document(client_id).collection('events')
                    events = events_ref.where('status', '==', 'PROGRAMADO').stream()
                    
                    for event in events:
                        try:
                            event_data = event.to_dict()
                            event_id = event.id
                            
                            event_key = f"{client_id}_{event_id}"
                            
                            if event_key in self.processed_events:
                                continue
                            
                            event_datetime_str = event_data.get('date_time')
                            if not event_datetime_str:
                                continue
                                
                            event_datetime = self.parse_event_datetime(event_datetime_str)
                            
                            if self.should_notify(event_datetime, current_time):
                                logging.info(f"¡Enviando recordatorio para evento '{event_data.get('title')}' programado para {event_datetime_str}!")
                                
                                self.send_event_reminder(client_id, event_id, event_data)
                                self.processed_events.add(event_key)
                                
                        except Exception as e:
                            logging.error(f"Error procesando evento {event.id}: {e}")
                            
                except Exception as e:
                    logging.error(f"Error obteniendo eventos para cliente {client_id}: {e}")
                    
            self.maintenance_counter += 1
            if self.maintenance_counter >= self.maintenance_interval:
                self.maintenance_counter = 0
                self.clean_processed_events()
                
        except Exception as e:
            logging.error(f"Error verificando eventos próximos: {e}")

    def clean_processed_events(self):
        try:
            current_time = self.get_current_time()
            old_events = set()
            
            for event_key in self.processed_events:
                try:
                    client_id, event_id = event_key.split('_', 1)
                    event_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/events/{event_id}')
                    event_doc = event_ref.get()
                    
                    if not event_doc.exists:
                        old_events.add(event_key)
                        continue
                        
                    event_data = event_doc.to_dict()
                    event_datetime_str = event_data.get('date_time')
                    if not event_datetime_str:
                        continue
                        
                    event_datetime = self.parse_event_datetime(event_datetime_str)
                    
                    if current_time > (event_datetime + timedelta(hours=12)):
                        old_events.add(event_key)
                        
                except Exception as e:
                    logging.error(f"Error verificando evento en limpieza: {e}")
                    
            if old_events:
                logging.info(f"Limpiando {len(old_events)} eventos procesados antiguos")
                self.processed_events -= old_events
                
            logging.info(f"Total eventos en memoria después de limpieza: {len(self.processed_events)}")
            
        except Exception as e:
            logging.error(f"Error durante limpieza de eventos procesados: {e}")

    def send_event_reminder(self, client_id: str, event_id: str, event_data: Dict[str, Any]):
        try:
            event_title = event_data.get('title', 'Evento')
            event_type = event_data.get('type', 'Evento')
            event_datetime = event_data.get('date_time', '')
            panel_name = event_data.get('panelName')
            panel_id = event_data.get('panelDocName')
            
            message = f"Recordatorio: El evento \"{event_title}\" "
            if panel_name:
                message += f"para el panel \"{panel_name}\" "
            message += f"está programado para {event_datetime} (en aproximadamente 1 hora)"
            
            timestamp = int(time.time() * 1000)
            notification_id = f"notification_{client_id}_{event_id}_{timestamp}"
            
            client_name = ""
            try:
                client_doc = self.db.document(f'hdd-monitor/accounts/clients/{client_id}').get()
                if client_doc.exists:
                    client_data = client_doc.to_dict()
                    client_name = client_data.get('name', '')
            except Exception as e:
                logging.error(f"Error obteniendo información del cliente: {e}")
            
            notification_data = {
                "type": "event",
                "event_type": event_type,
                "title": event_title,
                "message": message,
                "date_time": datetime.now(self.peru_timezone).strftime('%d/%m/%Y, %H:%M'),
                "timestamp": timestamp,
                "event_id": event_id,
                "status": "PROGRAMADO",
                "panel_name": panel_name,
                "panel_id": panel_id,
                "isRead": False,
                "action": "REMINDER",
                "documentName": notification_id,
                "client_name": client_name,
                "readByAdmin": False,
                "readByUser": False
            }
            
            notifications_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/notifications')
            notifications_ref.document(notification_id).set(notification_data)
            logging.info(f"Notificación de recordatorio creada con ID: {notification_id}")
            
            self.notification_handler.send_fcm_notifications(client_id, notification_data, "event")
            logging.info(f"Notificación FCM enviada para evento {event_id}")
            
        except Exception as e:
            logging.error(f"Error enviando recordatorio para evento {event_id}: {e}")

    def run(self):
        logging.info("Iniciando verificador de recordatorios de eventos")
        
        try:
            while self.running:
                self.check_upcoming_events()
                time.sleep(self.check_interval)
                
        except Exception as e:
            logging.error(f"Error en el bucle del verificador de recordatorios: {e}")

    def stop(self):
        self.running = False
        logging.info("Deteniendo verificador de recordatorios de eventos")

class FirestoreHandler:
    def __init__(self):
        creds = firebase_admin.credentials.Certificate('/home/pqsolutionsperu/vm-service-key.json')
        if not firebase_admin._apps:
            firebase_admin.initialize_app(creds)
        
        self.db = firestore.Client(
            project='fir-hdd-monitor-d00de',
            credentials=creds.get_credential()
        )
            
        self.notification_handler = NotificationHandler(self.db)
        self.mqtt_client = MQTTClient(self.handle_mqtt_message, db=self.db)
        
        self._watch_references = []
        self._relay_states = {}
        self._relay_configs = {}
        self._initial_load_complete = False
        self._events_initial_snapshots = {}
        self._notification_cache = {}
        self._config_cache = {}
        self._last_config_sent = {}

        try:
            logging.info("Iniciando observadores...")
            self.watch_events()
            self.watch_relay_states()
            self.watch_relay_configurations()
            logging.info("Observadores iniciados correctamente")
        except Exception as e:
            logging.error(f"Error crítico iniciando observadores: {e}", exc_info=True)
            raise

        try:
            self.event_reminder = EventReminderChecker(self.db, self.notification_handler)
            self.reminder_thread = threading.Thread(target=self.event_reminder.run, daemon=True)
            self.reminder_thread.start()
            logging.info("Hilo de recordatorio de eventos iniciado correctamente")
        except Exception as e:
            logging.error(f"Error iniciando verificador de recordatorios de eventos: {e}", exc_info=True)

    def handle_mqtt_message(self, msg):
        try:
            if msg.retain:
                logging.info(f"Ignorando mensaje retain en {msg.topic}")
                return

            payload = json.loads(msg.payload.decode())
            if not payload:
                return

            if msg.topic.startswith("clients/") and "panels" in msg.topic:
                self.handle_panel_message(msg.topic, payload)
            
        except Exception as e:
            logging.error(f"Error procesando mensaje: {e}", exc_info=True)

    def _cache_relay_state(self, relay_path: str, state: Dict[str, Any]):
        self._relay_states[relay_path] = {
            'status': state.get('status'),
            'lastUpdate': state.get('lastUpdate'),
            'name': state.get('name', '')
        }

    def _get_cached_relay_state(self, relay_path: str) -> Optional[Dict[str, Any]]:
        return self._relay_states.get(relay_path)

    def watch_events(self):
        try:
            def on_event_change(doc_snapshot, changes, read_time):
                for change in changes:
                    try:
                        doc = change.document
                        doc_path = doc.reference.path
                        path_parts = doc_path.split('/')
                        
                        if len(path_parts) >= 6 and path_parts[4] == 'events':
                            old_data = {}
                            new_data = doc.to_dict() if change.type.name != 'REMOVED' else None
                            
                            if change.type.name == 'MODIFIED':
                                for existing_doc in doc_snapshot:
                                    if existing_doc.id == doc.id and existing_doc.reference.path == doc_path:
                                        old_data = self._events_initial_snapshots.get(doc_path, {})
                                        break
                            
                            if change.type.name in ['ADDED', 'MODIFIED', 'REMOVED']:
                                logging.info(f"Evento {change.type.name}: {doc.id}")
                                self.notification_handler.process_event_update(
                                    doc.reference,
                                    old_data if change.type.name == 'MODIFIED' else {},
                                    new_data
                                )
                            
                            if new_data and change.type.name != 'REMOVED':
                                self._events_initial_snapshots[doc_path] = new_data
                            elif doc_path in self._events_initial_snapshots:
                                del self._events_initial_snapshots[doc_path]
                    
                    except Exception as e:
                        logging.error(f"Error procesando cambio de evento: {e}", exc_info=True)
            
            query = self.db.collection_group('events')
            watch = query.on_snapshot(on_event_change)
            self._watch_references.append(watch)
            
            logging.info("Observador global de eventos iniciado")
            
        except Exception as e:
            logging.error(f"Error iniciando observador de eventos: {e}", exc_info=True)

    def watch_relay_states(self):
        try:
            def on_relay_state_change(doc_snapshot, changes, read_time):
                for change in changes:
                    try:
                        if change.type.name == 'MODIFIED':
                            doc = change.document
                            doc_path = doc.reference.path
                            path_parts = doc_path.split('/')
                            
                            if len(path_parts) >= 8 and path_parts[4] == 'panels' and path_parts[6] == 'relays':
                                new_data = doc.to_dict()
                                old_data = self._relay_states.get(doc_path, {})
                                
                                if old_data.get('status') != new_data.get('status'):
                                    if new_data.get('source') != 'mqtt':
                                        logging.info(f"Cambio de estado detectado: {doc.id}")
                                        self.notification_handler.process_relay_update(doc.reference, old_data, new_data)
                                
                                self._relay_states[doc_path] = new_data
                    
                    except Exception as e:
                        logging.error(f"Error procesando cambio de estado: {e}")
            
            query = self.db.collection_group('relays')
            watch = query.on_snapshot(on_relay_state_change)
            self._watch_references.append(watch)
            
            logging.info("Observador global de estados de relay iniciado")
            
        except Exception as e:
            logging.error(f"Error iniciando observador de estados: {e}", exc_info=True)

    def watch_relay_configurations(self):
        logging.info("Iniciando observador global de configuraciones de relay")
        try:
            self._relay_configs = {}
            
            def on_relay_config_change(doc_snapshot, changes, read_time):
                for change in changes:
                    try:
                        doc = change.document
                        doc_path = doc.reference.path
                        path_parts = doc_path.split('/')
                        
                        if len(path_parts) >= 8 and path_parts[4] == 'panels' and path_parts[6] == 'relays':
                            client_id = path_parts[3]
                            panel_id = path_parts[5]
                            relay_id = path_parts[7]
                            
                            if change.type.name == 'REMOVED':
                                if doc_path in self._relay_configs:
                                    del self._relay_configs[doc_path]
                                continue
                            
                            new_data = doc.to_dict()
                            old_data = self._relay_configs.get(doc_path, {})
                            
                            if change.type.name == 'ADDED':
                                self._relay_configs[doc_path] = new_data
                                continue
                            
                            if change.type.name == 'MODIFIED':
                                config_key = f"{client_id}_{panel_id}_{relay_id}"
                                current_time = time.time()
                                
                                if config_key in self._last_config_sent:
                                    time_diff = current_time - self._last_config_sent[config_key]
                                    if time_diff < 10:
                                        logging.debug(f"Configuración enviada recientemente para {relay_id}, saltando")
                                        self._relay_configs[doc_path] = new_data
                                        continue
                                
                                config_changed = False
                                
                                if old_data.get('isActive') != new_data.get('isActive'):
                                    config_changed = True
                                
                                if old_data.get('customName') != new_data.get('customName'):
                                    config_changed = True
                                
                                if old_data.get('contactType') != new_data.get('contactType'):
                                    config_changed = True
                                
                                if config_changed:
                                    self._last_config_sent[config_key] = current_time
                                    
                                    panel_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
                                    panel_doc = panel_ref.get()
                                    
                                    if panel_doc.exists:
                                        panel_data = panel_doc.to_dict()
                                        esp32_id = panel_data.get('esp32_id')
                                        
                                        if esp32_id:
                                            command = {
                                                'command': 'update_config',
                                                'relay_id': relay_id,
                                                'is_active': new_data.get('isActive', True),
                                                'contact_type': new_data.get('contactType', 'NO'),
                                                'timestamp': int(time.time() * 1000)
                                            }
                                            
                                            custom_name = new_data.get('customName')
                                            if custom_name and custom_name.strip():
                                                command['custom_name'] = custom_name.strip()
                                            
                                            topic = f"clients/{client_id}/panels/{panel_id}/relay_config"
                                            
                                            try:
                                                if self.mqtt_client and self.mqtt_client.connected:
                                                    result = self.mqtt_client.client.publish(
                                                        topic,
                                                        json.dumps(command),
                                                        qos=2
                                                    )
                                                    if result.rc == mqtt.MQTT_ERR_SUCCESS:
                                                        logging.info(f"Configuración enviada para {relay_id}")
                                            except Exception as e:
                                                logging.error(f"Error publicando MQTT: {e}")
                                
                                self._relay_configs[doc_path] = new_data
                    
                    except Exception as e:
                        logging.error(f"Error procesando cambio: {e}", exc_info=True)
            
            query = self.db.collection_group('relays')
            watch = query.on_snapshot(on_relay_config_change)
            self._watch_references.append(watch)
            
            logging.info("Observador global de configuraciones iniciado")
            
        except Exception as e:
            logging.error(f"Error iniciando observador global: {e}", exc_info=True)
            raise

    def _on_relay_snapshot(self, doc_snapshot, changes, read_time):
        for change in changes:
            try:
                if change.type.name == 'MODIFIED':
                    doc = change.document
                    new_data = doc.to_dict()
                    doc_path = doc.reference.path
                    
                    old_data = self._relay_states.get(doc_path)
                    
                    if old_data is None:
                        logging.error(f"Estado no encontrado en caché para relay {doc.id}")
                        self._relay_states[doc_path] = new_data
                        return
                    
                    if old_data.get('status') != new_data.get('status'):
                        if new_data.get('source') == 'mqtt':
                            logging.debug(f"Cambio en relay {doc.id} originado por MQTT - Ya procesado")
                        else:
                            logging.info(f"Cambio externo detectado en relay {doc.id}: {old_data.get('status')} -> {new_data.get('status')}")
                            self.notification_handler.process_relay_update(doc.reference, old_data, new_data)
                    
                    self._relay_states[doc_path] = new_data
                        
            except Exception as e:
                logging.error(f"Error procesando cambio de relay: {e}", exc_info=True)

    def handle_panel_message(self, topic: str, payload: Dict[str, Any]):
        try:
            parts = topic.split('/')
            if len(parts) < 4 or parts[0] != "clients" or parts[2] != "panels":
                return

            client_id = parts[1]
            panel_id = parts[3]
            
            if 'relay' in payload and 'status' in payload:
                logging.debug(f"Procesando mensaje de relay desde {topic}")
                self._update_relay_state(client_id, panel_id, payload)
                    
        except Exception as e:
            logging.error(f"Error en handle_panel_message: {e}", exc_info=True)

    def _send_relay_notification_fast(self, client_id: str, panel_id: str, relay_name: str, old_status: str, new_status: str, relay_data: Dict[str, Any] = None):
        try:
            current_time = time.time() * 1000
            
            if relay_data is None:
                try:
                    relay_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}/relays/{relay_name}')
                    relay_doc = relay_ref.get()
                    relay_data = relay_doc.to_dict() if relay_doc.exists else {}
                except Exception as e:
                    logging.error(f"Error obteniendo datos del relay: {e}")
                    relay_data = {}
            
            cache_key = f"{client_id}_{panel_id}_{relay_name}_{old_status}_{new_status}_{int(current_time / 1000)}"
            
            if cache_key in self._notification_cache:
                time_diff = current_time - self._notification_cache[cache_key]
                if time_diff < 1000:
                    logging.debug(f"Notificación duplicada ignorada para {relay_name}")
                    return
            
            self._notification_cache[cache_key] = current_time
            
            panel_name = "Panel"
            try:
                panel_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
                panel_doc = panel_ref.get()
                if panel_doc.exists:
                    panel_data = panel_doc.to_dict()
                    panel_name = panel_data.get('name', 'Panel')
            except:
                pass
            
            relay_display_name = self._get_relay_display_name(relay_name, relay_data)
            
            message_text = f"El {relay_display_name} del panel \"{panel_name}\" ha cambiado de {old_status} a {new_status}"
            
            notification_id = f"relay_{client_id}_{panel_id}_{relay_name}_{int(current_time)}"
            
            notification_doc = {
                'type': 'relay',
                'relay': relay_name,
                'panel_id': panel_id,
                'panel_name': panel_name,
                'client_id': client_id,
                'state': new_status,
                'old_status': old_status,
                'message': message_text,
                'date_time': datetime.now(pytz.timezone('America/Lima')).strftime('%d/%m/%Y, %H:%M'),
                'lastUpdate': firestore.SERVER_TIMESTAMP,
                'documentName': notification_id,
                'isRead': False,
                'readByAdmin': False,
                'timestamp': int(current_time)
            }
            
            notifications_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/notifications')
            notifications_ref.document(notification_id).set(notification_doc)
            
            self.notification_handler.send_fcm_notifications(client_id, {
                'type': 'relay',
                'relay': relay_name,
                'panel_id': panel_id,
                'panel_name': panel_name,
                'state': new_status,
                'old_status': old_status,
                'message': message_text
            }, 'relay')
            
            logging.info(f"Notificación rápida enviada para {relay_display_name}: {old_status} -> {new_status}")
            
        except Exception as e:
            logging.error(f"Error en notificación rápida: {e}")

    def _get_relay_display_name(self, relay_id: str, relay_data: Dict[str, Any]) -> str:
        custom_name = relay_data.get('customName', '').strip()
        
        if custom_name:
            return f"relay {custom_name}"
        else:
            return relay_id

    def _update_relay_state(self, client_id: str, panel_id: str, payload: Dict[str, Any]):
        try:
            relay_name = payload['relay']
            new_state = payload['status']
            
            panel_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
            relay_ref = panel_ref.collection('relays').document(relay_name)
            
            relay_snap = relay_ref.get()
            old_data = relay_snap.to_dict() if relay_snap.exists else {'status': None}
            old_status = old_data.get('status')
            
            if old_status != new_state:
                new_data = {
                    'status': new_state,
                    'date_time': datetime.now(pytz.timezone('America/Lima')).strftime('%d/%m/%Y, %H:%M'),
                    'lastUpdate': firestore.SERVER_TIMESTAMP,
                    'source': 'mqtt'
                }
                
                if 'contact_type' in payload:
                    new_data['contactType'] = payload['contact_type']
                
                complete_relay_data = old_data.copy()
                complete_relay_data.update(new_data)
                
                relay_display_name = self._get_relay_display_name(relay_name, complete_relay_data)
                
                logging.info(f"{relay_display_name}: {old_status} -> {new_state}")
                
                self._send_relay_notification_fast(client_id, panel_id, relay_name, old_status, new_state, complete_relay_data)
                
                try:
                    relay_ref.set(new_data, merge=True)
                    doc_path = relay_ref.path
                    self._relay_states[doc_path] = complete_relay_data
                except Exception as e:
                    logging.error(f"Error actualizando BD para {relay_display_name}: {e}")
                    
        except Exception as e:
            logging.error(f"Error en _update_relay_state: {e}", exc_info=True)

    def test_mqtt_connection(self):
        try:
            if not self.mqtt_client or not self.mqtt_client.connected:
                logging.error("Cliente MQTT no conectado")
                return False
                
            test_message = {
                'test': True,
                'timestamp': int(time.time() * 1000),
                'message': 'Test de configuración'
            }
            
            result = self.mqtt_client.client.publish(
                'test/config',
                json.dumps(test_message),
                qos=2
            )
            
            if result.rc == 0:
                logging.info("Prueba MQTT exitosa")
                return True
            else:
                logging.error(f"Error en prueba MQTT: {result.rc}")
                return False
                
        except Exception as e:
            logging.error(f"Error en prueba MQTT: {e}")
            return False

    def cleanup(self):
        if hasattr(self, 'event_reminder'):
            try:
                self.event_reminder.stop()
                logging.info("Verificador de recordatorios detenido correctamente")
            except Exception as e:
                logging.error(f"Error deteniendo verificador de recordatorios: {e}")
                
        for watch in self._watch_references:
            try:
                watch.unsubscribe()
            except Exception as e:
                logging.error(f"Error al limpiar observador: {e}")
        self._watch_references.clear()
        
        if hasattr(self, 'mqtt_client'):
            try:
                self.mqtt_client.cleanup()
            except Exception as e:
                logging.error(f"Error limpiando cliente MQTT: {e}")