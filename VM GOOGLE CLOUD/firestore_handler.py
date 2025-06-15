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
        """Inicializa el verificador de recordatorios de eventos"""
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
        """Obtiene la hora actual en la zona horaria de Perú"""
        return datetime.now(self.peru_timezone)

    def parse_event_datetime(self, date_time_str: str) -> datetime:
        """Convierte el string de fecha/hora a un objeto datetime"""
        try:
            dt = datetime.strptime(date_time_str, self.date_formatter)
            return self.peru_timezone.localize(dt)
        except Exception as e:
            logging.error(f"Error analizando fecha de evento '{date_time_str}': {e}")
            return self.get_current_time() - timedelta(days=1)

    def should_notify(self, event_datetime: datetime, current_time: datetime) -> bool:
        """Determina si es hora de enviar notificación para este evento"""
        reminder_time = event_datetime - timedelta(minutes=self.reminder_minutes)
        time_diff = (current_time - reminder_time).total_seconds()
        return 0 <= time_diff <= 120

    def check_upcoming_events(self):
        """Verifica eventos próximos y envía notificaciones"""
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
        """Limpia eventos procesados para evitar crecimiento excesivo de memoria"""
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
        """Envía notificación de recordatorio para un evento"""
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
        """Ejecuta el verificador de recordatorios en bucle continuo"""
        logging.info("Iniciando verificador de recordatorios de eventos")
        
        try:
            while self.running:
                self.check_upcoming_events()
                time.sleep(self.check_interval)
                
        except Exception as e:
            logging.error(f"Error en el bucle del verificador de recordatorios: {e}")

    def stop(self):
        """Detiene el verificador de recordatorios"""
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

        try:
            logging.info("📡 Iniciando observador de eventos...")
            self.watch_events()
            logging.info("Observador de eventos iniciado correctamente")
            
            logging.info("📡 Iniciando observador de estados de relay...")
            self.watch_relay_states()
            logging.info("Observador de estados de relay iniciado correctamente")
            
            logging.info("📡 Iniciando observador de configuraciones de relay...")
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

    def handle_mqtt_message(self, msg):
        """Maneja los mensajes MQTT recibidos"""
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
        """Observa cambios en estados de relays en todos los paneles"""
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
        logging.info("🔧 Entrando a watch_relay_configurations...")
        try:
            self._relay_configs = {}
            logging.info("🔧 Obteniendo clientes...")
            
            clients_ref = self.db.collection('hdd-monitor/accounts/clients')
            clients_snapshot = clients_ref.get()
            
            for client_doc in clients_snapshot:
                client_id = client_doc.id
                panels_ref = clients_ref.document(client_id).collection('panels')
                panels_snapshot = panels_ref.get()
                
                for panel_doc in panels_snapshot:
                    panel_id = panel_doc.id
                    panel_data = panel_doc.to_dict()
                    esp32_id = panel_data.get('esp32_id')
                    
                    if not esp32_id:
                        continue
                        
                    relays_ref = panels_ref.document(panel_id).collection('relays')
                    
                    relay_count = 0
                    for relay_doc in relays_ref.get():
                        relay_count += 1
                        self._relay_configs[relay_doc.reference.path] = relay_doc.to_dict()
                    
                    logging.info(f"🔧 Panel {panel_id} tiene {relay_count} relays")
                    
                    def create_config_handler(client_id, panel_id, esp32_id):
                        def on_relay_config_change(doc_snapshot, changes, read_time):
                            logging.info(f"📡 Cambio detectado en relays del panel {panel_id}")
                            for change in changes:
                                logging.info(f"📡 Tipo de cambio: {change.type.name} para relay {change.document.id}")
                                if change.type.name == 'MODIFIED':
                                    doc = change.document
                                    new_data = doc.to_dict()
                                    old_data = self._relay_configs.get(doc.reference.path, {})
                                    
                                    config_changed = (
                                        old_data.get('isActive') != new_data.get('isActive') or
                                        old_data.get('customName') != new_data.get('customName') or
                                        old_data.get('contactType') != new_data.get('contactType')
                                    )
                                    
                                    if config_changed:
                                        logging.info(f"Cambio detectado en configuración de relay {doc.id}:")
                                        logging.info(f"  isActive: {old_data.get('isActive')} → {new_data.get('isActive')}")
                                        logging.info(f"  customName: '{old_data.get('customName')}' → '{new_data.get('customName')}'")
                                        logging.info(f"  contactType: {old_data.get('contactType')} → {new_data.get('contactType')}")
                                        
                                        command = {
                                            'command': 'update_config',
                                            'relay_id': doc.id,
                                            'is_active': new_data.get('isActive', True),
                                            'contact_type': new_data.get('contactType', 'NO'),
                                            'timestamp': int(time.time() * 1000)
                                        }
                                        
                                        custom_name = new_data.get('customName')
                                        if custom_name and custom_name.strip():
                                            command['custom_name'] = custom_name.strip()
                                        
                                        topic = f"clients/{client_id}/panels/{panel_id}/relay_config"
                                        
                                        try:
                                            result = self.mqtt_client.client.publish(
                                                topic,
                                                json.dumps(command),
                                                qos=2
                                            )
                                            
                                            if result.rc == 0:
                                                logging.info(f"Configuración de relay enviada al ESP32:")
                                                logging.info(f"   Tópico: {topic}")
                                                logging.info(f"   Comando: {command}")
                                            else:
                                                logging.error(f"Error enviando configuración MQTT, código: {result.rc}")
                                                
                                        except Exception as e:
                                            logging.error(f"Error enviando configuración de relay: {e}")
                                    
                                    self._relay_configs[doc.reference.path] = new_data
                                    
                                elif change.type.name == 'ADDED':
                                    doc = change.document
                                    new_data = doc.to_dict()
                                    self._relay_configs[doc.reference.path] = new_data
                                    logging.info(f"Nuevo relay añadido al caché: {doc.id}")
                                    
                        return on_relay_config_change
                    
                    try:
                        watch = relays_ref.on_snapshot(create_config_handler(client_id, panel_id, esp32_id))
                        self._watch_references.append(watch)
                        logging.info(f"Observador de configuración iniciado para panel {panel_id} (ESP32: {esp32_id})")
                    except Exception as e:
                        logging.error(f"Error iniciando observador para panel {panel_id}: {e}")
                        
        except Exception as e:
            logging.error(f"Error iniciando observador de configuraciones: {e}", exc_info=True)

    def _on_relay_snapshot(self, doc_snapshot, changes, read_time):
        """Maneja cambios en los relays con prevención de duplicados"""
        for change in changes:
            try:
                if change.type.name == 'MODIFIED':
                    doc = change.document
                    new_data = doc.to_dict()
                    doc_path = doc.reference.path
                    
                    old_data = self._relay_states.get(doc_path)
                    
                    if old_data is None:
                        logging.error(f"Error crítico: Estado no encontrado en caché para relay {doc.id}")
                        return
                    
                    if old_data.get('status') != new_data.get('status'):
                        if new_data.get('source') == 'mqtt':
                            logging.info(f"Cambio en BD originado por MQTT - Omitiendo notificación duplicada para {doc.id}")
                        else:
                            logging.info(f"Cambio detectado en relay: {doc.id}")
                            logging.info(f"Estado anterior: {old_data.get('status')}")
                            logging.info(f"Nuevo estado: {new_data.get('status')}")
                            
                            self.notification_handler.process_relay_update(doc.reference, old_data, new_data)
                        
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
            
            if 'relay' in payload and 'status' in payload:
                self._update_relay_state(client_id, panel_id, payload)
                
        except Exception as e:
            logging.error(f"Error en handle_panel_message: {e}", exc_info=True)

    def _update_relay_state(self, client_id: str, panel_id: str, payload: Dict[str, Any]):
        """Actualiza el estado de un relay y envía notificaciones"""
        try:
            relay_name = payload['relay']
            new_state = payload['status']
            
            panel_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
            relay_ref = panel_ref.collection('relays').document(relay_name)
            
            panel_snap = panel_ref.get()
            if not panel_snap.exists:
                logging.error(f"Panel no encontrado: {panel_id}")
                return
                    
            relay_snap = relay_ref.get()
            old_data = relay_snap.to_dict() if relay_snap.exists else {'status': None}
            
            if old_data.get('status') != new_state:
                new_data = {
                    'status': new_state,
                    'date_time': datetime.now(pytz.timezone('America/Lima')).strftime('%d/%m/%Y, %H:%M'),
                    'lastUpdate': firestore.SERVER_TIMESTAMP
                }
                
                if 'contact_type' in payload:
                    new_data['contactType'] = payload['contact_type']
                
                logging.info(f"Cambio de estado en relay {relay_name}:")
                logging.info(f"Estado anterior en BD: {old_data.get('status')}")
                logging.info(f"Nuevo estado del ESP32: {new_state}")
                
                try:
                    logging.info(f"Enviando notificación directa para cambio de relay {relay_name}")
                    self.notification_handler.process_relay_update(relay_ref, old_data, new_data)
                except Exception as e:
                    logging.error(f"Error enviando notificación directa: {e}", exc_info=True)
                
                try:
                    relay_ref.set(new_data, merge=True)
                    logging.info(f"Estado actualizado para relay {relay_name}")
                except Exception as e:
                    logging.error(f"Error actualizando BD: {e}", exc_info=True)
                    
        except Exception as e:
            logging.error(f"Error en _update_relay_state: {e}", exc_info=True)

    def cleanup(self):
        """Limpia los observadores al cerrar"""
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