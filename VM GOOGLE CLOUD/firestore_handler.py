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
        self._events_initial_snapshots = set()
        self._notification_cache = {}

        try:
            logging.info("Iniciando observador de eventos...")
            self.watch_events()
            logging.info("Observador de eventos iniciado correctamente")
            
            logging.info("Iniciando observador de estados de relay...")
            self.watch_relay_states()
            logging.info("Observador de estados de relay iniciado correctamente")
            
            logging.info("Iniciando observador de configuraciones de relay...")
            self.watch_relay_configurations()
            logging.info("Observador de configuraciones de relay iniciado correctamente")
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

        def wait_and_test():
            try:
                max_attempts = 10
                attempt = 0
                while attempt < max_attempts:
                    if hasattr(self, 'mqtt_client') and self.mqtt_client and self.mqtt_client.connected:
                        logging.info(f"MQTT conectado después de {attempt} intentos, ejecutando pruebas...")
                        time.sleep(1)
                        self.test_mqtt_connection()
                        time.sleep(1)
                        self.test_relay_config_manually()
                        break
                    else:
                        attempt += 1
                        logging.info(f"Esperando conexión MQTT, intento {attempt}/{max_attempts}")
                        time.sleep(2)
                
                if attempt >= max_attempts:
                    logging.error("Timeout esperando conexión MQTT para pruebas")
            except Exception as e:
                logging.error(f"Error en verificaciones iniciales: {e}")

        test_thread = threading.Thread(target=wait_and_test, daemon=True)
        test_thread.start()

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
            clients_ref = self.db.collection('hdd-monitor/accounts/clients')
            clients = clients_ref.stream()

            for client in clients:
                def create_snapshot_handler(client_id):
                    snapshot_key = f"events_{client_id}"
                    initial_snapshot_processed = False
                    last_snapshot = {}
                    
                    def on_snapshot(doc_snapshot, changes, read_time):
                        nonlocal initial_snapshot_processed, last_snapshot
                        
                        if not initial_snapshot_processed:
                            initial_snapshot_processed = True
                            for doc in doc_snapshot:
                                last_snapshot[doc.id] = doc.to_dict()
                            logging.info(f"Carga inicial de eventos para cliente {client_id}")
                            return

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

                        current_snapshot = {doc.id: doc.to_dict() for doc in doc_snapshot}
                        last_snapshot.update(current_snapshot)
                        
                    return on_snapshot

                events_ref = clients_ref.document(client.id).collection('events')
                watch = events_ref.on_snapshot(create_snapshot_handler(client.id))
                self._watch_references.append(watch)
                logging.info(f"Observador de eventos iniciado para cliente {client.id}")

        except Exception as e:
            logging.error(f"Error iniciando observadores de eventos: {e}", exc_info=True)

    def watch_relay_states(self):
        try:
            clients_ref = self.db.collection('hdd-monitor/accounts/clients')
            clients = clients_ref.stream()

            for client in clients:
                panels_ref = clients_ref.document(client.id).collection('panels')
                panels = panels_ref.stream()

                for panel in panels:
                    relays_ref = panels_ref.document(panel.id).collection('relays')
                    for relay_doc in relays_ref.stream():
                        self._relay_states[relay_doc.reference.path] = relay_doc.to_dict()

                    watch = relays_ref.on_snapshot(self._on_relay_snapshot)
                    self._watch_references.append(watch)
                    logging.info(f"Observador de relays iniciado para panel {panel.id} del cliente {client.id}")

        except Exception as e:
            logging.error(f"Error iniciando observadores de relays: {e}", exc_info=True)

    def watch_relay_configurations(self):
        logging.info("Iniciando observador de configuraciones de relay")
        try:
            self._relay_configs = {}
            
            clients_ref = self.db.collection('hdd-monitor/accounts/clients')
            clients_snapshot = clients_ref.get()
            
            logging.info(f"Total de clientes encontrados: {len(clients_snapshot)}")
            
            for client_doc in clients_snapshot:
                client_id = client_doc.id
                logging.info(f"Procesando cliente: {client_id}")
                
                panels_ref = clients_ref.document(client_id).collection('panels')
                panels_snapshot = panels_ref.get()
                
                logging.info(f"Paneles encontrados para {client_id}: {len(panels_snapshot)}")
                
                for panel_doc in panels_snapshot:
                    panel_id = panel_doc.id
                    panel_data = panel_doc.to_dict()
                    
                    logging.info(f"Procesando panel: {panel_id}")
                    logging.info(f"Panel data keys: {list(panel_data.keys()) if panel_data else 'None'}")
                    
                    esp32_id = panel_data.get('esp32_id') if panel_data else None
                    
                    logging.info(f"ESP32 ID encontrado: {esp32_id}")
                    
                    if not esp32_id:
                        logging.warning(f"Panel {panel_id} no tiene ESP32 asignado, omitiendo")
                        continue
                    
                    relays_ref = panels_ref.document(panel_id).collection('relays')
                    relays_snapshot = relays_ref.get()
                    
                    logging.info(f"Relays encontrados en panel {panel_id}: {len(relays_snapshot)}")
                    
                    relay_count = 0
                    for relay_doc in relays_snapshot:
                        relay_count += 1
                        relay_data = relay_doc.to_dict()
                        relay_path = relay_doc.reference.path
                        self._relay_configs[relay_path] = relay_data
                        
                        logging.info(f"    Relay {relay_count}: {relay_doc.id}")
                        logging.info(f"      isActive: {relay_data.get('isActive')}")
                        logging.info(f"      customName: '{relay_data.get('customName')}'")
                        logging.info(f"      contactType: {relay_data.get('contactType')}")
                        logging.info(f"      status: {relay_data.get('status')}")
                    
                    logging.info(f"Total relays cargados para panel {panel_id}: {relay_count}")
                    
                    if relay_count == 0:
                        logging.warning(f"No se encontraron relays para panel {panel_id}")
                        continue
                    
                    def create_config_handler(client_id, panel_id, esp32_id):
                        initial_load_done = False
                        last_snapshot_data = {}
                        
                        def on_relay_config_change(doc_snapshot, changes, read_time):
                            nonlocal initial_load_done, last_snapshot_data
                            
                            if not initial_load_done:
                                initial_load_done = True
                                logging.info(f"Snapshot inicial para panel {panel_id}: {len(doc_snapshot)} documentos")
                                for doc in doc_snapshot:
                                    doc_data = doc.to_dict()
                                    last_snapshot_data[doc.id] = doc_data
                                    self._relay_configs[doc.reference.path] = doc_data
                                    logging.info(f"  Cargado en snapshot: {doc.id} - isActive: {doc_data.get('isActive')}")
                                logging.info(f"Carga inicial completada para panel {panel_id}")
                                return
                            
                            if len(changes) > 0:
                                logging.info(f"CAMBIOS DETECTADOS en panel {panel_id}: {len(changes)} cambios")
                                
                                for change in changes:
                                    try:
                                        doc = change.document
                                        relay_id = doc.id
                                        change_type = change.type.name
                                        
                                        logging.info(f"  Procesando cambio '{change_type}' para relay {relay_id}")
                                        
                                        if change_type == 'MODIFIED':
                                            new_data = doc.to_dict()
                                            old_data = last_snapshot_data.get(relay_id, {})
                                            
                                            logging.info(f"    Datos anteriores: isActive={old_data.get('isActive')}, customName='{old_data.get('customName')}', contactType={old_data.get('contactType')}")
                                            logging.info(f"    Datos nuevos: isActive={new_data.get('isActive')}, customName='{new_data.get('customName')}', contactType={new_data.get('contactType')}")
                                            
                                            config_fields_changed = []
                                            
                                            if old_data.get('isActive') != new_data.get('isActive'):
                                                config_fields_changed.append(f"isActive: {old_data.get('isActive')} → {new_data.get('isActive')}")
                                            
                                            if old_data.get('customName') != new_data.get('customName'):
                                                config_fields_changed.append(f"customName: '{old_data.get('customName')}' → '{new_data.get('customName')}'")
                                            
                                            if old_data.get('contactType') != new_data.get('contactType'):
                                                config_fields_changed.append(f"contactType: {old_data.get('contactType')} → {new_data.get('contactType')}")
                                            
                                            if config_fields_changed:
                                                logging.info(f"    CONFIGURACION CAMBIADA para relay {relay_id}:")
                                                for change_detail in config_fields_changed:
                                                    logging.info(f"      {change_detail}")
                                                
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
                                                    command_json = json.dumps(command)
                                                    logging.info(f"    ENVIANDO comando MQTT:")
                                                    logging.info(f"      Topico: {topic}")
                                                    logging.info(f"      Comando: {command_json}")
                                                    
                                                    if not self.mqtt_client:
                                                        logging.error(f"      self.mqtt_client es None")
                                                    elif not self.mqtt_client.connected:
                                                        logging.error(f"      Cliente MQTT no conectado (connected={self.mqtt_client.connected})")
                                                    else:
                                                        logging.info(f"      Cliente MQTT OK, publicando...")
                                                        result = self.mqtt_client.client.publish(
                                                            topic,
                                                            command_json,
                                                            qos=2
                                                        )
                                                        
                                                        logging.info(f"      Resultado publish: rc={result.rc}")
                                                        
                                                        if result.rc == mqtt.MQTT_ERR_SUCCESS:
                                                            logging.info(f"      CONFIGURACION ENVIADA EXITOSAMENTE al ESP32 {esp32_id}")
                                                        else:
                                                            logging.error(f"      Error enviando MQTT, código: {result.rc}")
                                                
                                                except Exception as e:
                                                    logging.error(f"      Excepción enviando configuración: {e}", exc_info=True)
                                            
                                            else:
                                                logging.info(f"    Cambio en relay {relay_id} pero no es de configuración")
                                            
                                            last_snapshot_data[relay_id] = new_data
                                            self._relay_configs[doc.reference.path] = new_data
                                        
                                        elif change_type == 'ADDED':
                                            new_data = doc.to_dict()
                                            last_snapshot_data[relay_id] = new_data
                                            self._relay_configs[doc.reference.path] = new_data
                                            logging.info(f"  Nuevo relay añadido: {relay_id}")
                                        
                                        elif change_type == 'REMOVED':
                                            if relay_id in last_snapshot_data:
                                                del last_snapshot_data[relay_id]
                                            if doc.reference.path in self._relay_configs:
                                                del self._relay_configs[doc.reference.path]
                                            logging.info(f"  Relay eliminado: {relay_id}")
                                    
                                    except Exception as e:
                                        logging.error(f"  Error procesando cambio de relay {doc.id}: {e}", exc_info=True)
                            else:
                                logging.debug(f"Sin cambios detectados en panel {panel_id}")
                        
                        return on_relay_config_change
                    
                    try:
                        config_handler = create_config_handler(client_id, panel_id, esp32_id)
                        watch = relays_ref.on_snapshot(config_handler)
                        self._watch_references.append(watch)
                        logging.info(f"OBSERVADOR INICIADO para panel {panel_id} (ESP32: {esp32_id})")
                    
                    except Exception as e:
                        logging.error(f"Error iniciando observador para panel {panel_id}: {e}", exc_info=True)
            
            logging.info(f"Total observadores creados: {len(self._watch_references)}")
            logging.info(f"Total configuraciones en caché: {len(self._relay_configs)}")
            
            for path, config in self._relay_configs.items():
                logging.info(f"  Cache: {path} -> isActive: {config.get('isActive')}")
                            
        except Exception as e:
            logging.error(f"ERROR CRITICO iniciando observador de configuraciones: {e}", exc_info=True)
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

    def _send_relay_notification_fast(self, client_id: str, panel_id: str, relay_name: str, old_status: str, new_status: str):
        try:
            current_time = time.time() * 1000
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
            
            message_text = f"El relay {relay_name} del panel \"{panel_name}\" ha cambiado de {old_status} a {new_status}"
            
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
            
            logging.info(f"Notificación rápida enviada para {relay_name}: {old_status} -> {new_status}")
            
        except Exception as e:
            logging.error(f"Error en notificación rápida: {e}")

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
                
                logging.info(f"Relay {relay_name}: {old_status} -> {new_state}")
                
                self._send_relay_notification_fast(client_id, panel_id, relay_name, old_status, new_state)
                
                try:
                    relay_ref.set(new_data, merge=True)
                    doc_path = relay_ref.path
                    self._relay_states[doc_path] = new_data
                except Exception as e:
                    logging.error(f"Error actualizando BD para {relay_name}: {e}")
                    
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

    def test_relay_config_manually(self):
        try:
            logging.info("Prueba manual de configuraciones de relay")
            
            if not self.mqtt_client:
                logging.error("self.mqtt_client es None")
                return False
            
            if not self.mqtt_client.connected:
                logging.error(f"Cliente MQTT no conectado (connected={self.mqtt_client.connected})")
                return False
            
            logging.info("Cliente MQTT conectado correctamente")
            
            clients_ref = self.db.collection('hdd-monitor/accounts/clients')
            clients_snapshot = clients_ref.get()
            
            logging.info(f"Total clientes encontrados: {len(clients_snapshot)}")
            
            for client_doc in clients_snapshot:
                client_id = client_doc.id
                logging.info(f"Cliente: {client_id}")
                
                panels_ref = client_doc.reference.collection('panels')
                panels_snapshot = panels_ref.get()
                
                logging.info(f"Paneles en {client_id}: {len(panels_snapshot)}")
                
                for panel_doc in panels_snapshot:
                    panel_id = panel_doc.id
                    panel_data = panel_doc.to_dict()
                    esp32_id = panel_data.get('esp32_id') if panel_data else None
                    
                    logging.info(f"Panel: {panel_id}")
                    logging.info(f"ESP32 ID: {esp32_id}")
                    logging.info(f"Panel data: {panel_data}")
                    
                    if not esp32_id:
                        logging.warning(f"Panel {panel_id} no tiene ESP32, saltando")
                        continue
                    
                    relays_ref = panel_doc.reference.collection('relays')
                    relays_snapshot = relays_ref.get()
                    
                    logging.info(f"Relays en panel {panel_id}: {len(relays_snapshot)}")
                    
                    relay_count = 0
                    active_relay_count = 0
                    
                    for relay_doc in relays_snapshot:
                        relay_count += 1
                        relay_data = relay_doc.to_dict()
                        relay_id = relay_doc.id
                        
                        is_active = relay_data.get('isActive', False)
                        custom_name = relay_data.get('customName', '')
                        contact_type = relay_data.get('contactType', 'NO')
                        status = relay_data.get('status', 'UNKNOWN')
                        
                        logging.info(f"    Relay {relay_count}: {relay_id}")
                        logging.info(f"      isActive: {is_active}")
                        logging.info(f"      customName: '{custom_name}'")
                        logging.info(f"      contactType: {contact_type}")
                        logging.info(f"      status: {status}")
                        
                        if is_active:
                            active_relay_count += 1
                            
                            command = {
                                'command': 'update_config',
                                'relay_id': relay_id,
                                'is_active': is_active,
                                'contact_type': contact_type,
                                'timestamp': int(time.time() * 1000)
                            }
                            
                            if custom_name and custom_name.strip():
                                command['custom_name'] = custom_name.strip()
                            
                            topic = f"clients/{client_id}/panels/{panel_id}/relay_config"
                            command_json = json.dumps(command)
                            
                            logging.info(f"      ENVIANDO configuración de prueba:")
                            logging.info(f"        Tópico: {topic}")
                            logging.info(f"        Comando: {command_json}")
                            
                            try:
                                result = self.mqtt_client.client.publish(
                                    topic,
                                    command_json,
                                    qos=2
                                )
                                
                                logging.info(f"        Resultado: rc={result.rc}")
                                
                                if result.rc == 0:
                                    logging.info(f"        ENVIADO EXITOSAMENTE")
                                else:
                                    logging.error(f"        ERROR MQTT código: {result.rc}")
                            
                            except Exception as e:
                                logging.error(f"        EXCEPCION: {e}")
                        else:
                            logging.info(f"      Relay inactivo, no enviando configuración")
                    
                    logging.info(f"Resumen panel {panel_id}: {relay_count} relays totales, {active_relay_count} activos")
            
            logging.info("Fin de prueba manual")
            return True
            
        except Exception as e:
            logging.error(f"Error en prueba manual: {e}", exc_info=True)
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