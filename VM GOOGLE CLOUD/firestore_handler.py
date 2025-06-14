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

class EventReminderChecker:
    def __init__(self, db: firestore.Client, notification_handler: NotificationHandler):
        """Inicializa el verificador de recordatorios de eventos"""
        self.db = db
        self.notification_handler = notification_handler
        self.peru_timezone = pytz.timezone('America/Lima')
        self.date_formatter = "%d-%m-%Y %H:%M"  # Formato usado en la BD: "14-03-2025 14:30"
        self.reminder_minutes = 60  # Notificar 1 hora antes
        self.check_interval = 60  # Revisar cada 60 segundos
        self.processed_events = set()  # Conjunto para evitar notificaciones duplicadas
        self.maintenance_counter = 0
        self.maintenance_interval = 100  # Limpiar lista de eventos procesados cada 100 ciclos
        self.running = True  # Control para detener el hilo cuando sea necesario

    def get_current_time(self) -> datetime:
        """Obtiene la hora actual en la zona horaria de Perú"""
        return datetime.now(self.peru_timezone)

    def parse_event_datetime(self, date_time_str: str) -> datetime:
        """Convierte el string de fecha/hora a un objeto datetime"""
        try:
            # Primero intentar analizar sin timezone
            dt = datetime.strptime(date_time_str, self.date_formatter)
            # Luego añadir timezone de Perú
            return self.peru_timezone.localize(dt)
        except Exception as e:
            logging.error(f"Error analizando fecha de evento '{date_time_str}': {e}")
            # Devolver una fecha pasada para que este evento sea ignorado
            return self.get_current_time() - timedelta(days=1)

    def should_notify(self, event_datetime: datetime, current_time: datetime) -> bool:
        """Determina si es hora de enviar notificación para este evento"""
        # Calcular el tiempo del recordatorio (1 hora antes)
        reminder_time = event_datetime - timedelta(minutes=self.reminder_minutes)
        
        # Determinar si el tiempo actual está dentro de una ventana de 2 minutos del recordatorio
        time_diff = (current_time - reminder_time).total_seconds()
        
        # Notificar si estamos dentro de una ventana de 2 minutos después del tiempo de recordatorio
        return 0 <= time_diff <= 120  # Ventana de 2 minutos

    def check_upcoming_events(self):
        """Verifica eventos próximos y envía notificaciones"""
        try:
            current_time = self.get_current_time()
            logging.info(f"Verificando eventos próximos a las {current_time.strftime('%d/%m/%Y, %H:%M:%S')}")
            
            # Obtener todos los clientes
            clients_ref = self.db.collection('hdd-monitor/accounts/clients')
            clients = clients_ref.stream()
            
            for client in clients:
                client_id = client.id
                try:
                    # Buscar eventos programados para este cliente
                    events_ref = clients_ref.document(client_id).collection('events')
                    events = events_ref.where('status', '==', 'PROGRAMADO').stream()
                    
                    for event in events:
                        try:
                            event_data = event.to_dict()
                            event_id = event.id
                            
                            # Identificador único para este evento
                            event_key = f"{client_id}_{event_id}"
                            
                            # Verificar si este evento ya fue procesado
                            if event_key in self.processed_events:
                                continue
                            
                            # Obtener y parsear fecha/hora del evento
                            event_datetime_str = event_data.get('date_time')
                            if not event_datetime_str:
                                continue
                                
                            event_datetime = self.parse_event_datetime(event_datetime_str)
                            
                            # Si es tiempo de notificar
                            if self.should_notify(event_datetime, current_time):
                                logging.info(f"¡Enviando recordatorio para evento '{event_data.get('title')}' programado para {event_datetime_str}!")
                                
                                self.send_event_reminder(client_id, event_id, event_data)
                                self.processed_events.add(event_key)
                                
                        except Exception as e:
                            logging.error(f"Error procesando evento {event.id}: {e}")
                            
                except Exception as e:
                    logging.error(f"Error obteniendo eventos para cliente {client_id}: {e}")
                    
            # Mantenimiento de la lista de eventos procesados
            self.maintenance_counter += 1
            if self.maintenance_counter >= self.maintenance_interval:
                self.maintenance_counter = 0
                self.clean_processed_events()
                
        except Exception as e:
            logging.error(f"Error verificando eventos próximos: {e}")

    def clean_processed_events(self):
        """Limpia eventos procesados para evitar crecimiento excesivo de memoria"""
        try:
            # Obtener eventos antiguos
            current_time = self.get_current_time()
            old_events = set()
            
            # Verificar cada evento guardado en processed_events
            for event_key in self.processed_events:
                try:
                    client_id, event_id = event_key.split('_', 1)
                    event_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/events/{event_id}')
                    event_doc = event_ref.get()
                    
                    if not event_doc.exists:
                        # Evento eliminado, añadirlo a la lista de limpieza
                        old_events.add(event_key)
                        continue
                        
                    event_data = event_doc.to_dict()
                    event_datetime_str = event_data.get('date_time')
                    if not event_datetime_str:
                        continue
                        
                    event_datetime = self.parse_event_datetime(event_datetime_str)
                    
                    # Si el evento ya pasó por más de 12 horas
                    if current_time > (event_datetime + timedelta(hours=12)):
                        old_events.add(event_key)
                        
                except Exception as e:
                    logging.error(f"Error verificando evento en limpieza: {e}")
                    
            # Eliminar eventos antiguos del conjunto
            if old_events:
                logging.info(f"Limpiando {len(old_events)} eventos procesados antiguos")
                self.processed_events -= old_events
                
            logging.info(f"Total eventos en memoria después de limpieza: {len(self.processed_events)}")
            
        except Exception as e:
            logging.error(f"Error durante limpieza de eventos procesados: {e}")

    def send_event_reminder(self, client_id: str, event_id: str, event_data: Dict[str, Any]):
        """Envía notificación de recordatorio para un evento"""
        try:
            # Preparar datos para la notificación
            event_title = event_data.get('title', 'Evento')
            event_type = event_data.get('type', 'Evento')
            event_datetime = event_data.get('date_time', '')
            panel_name = event_data.get('panelName')
            panel_id = event_data.get('panelDocName')
            
            # Formatear mensaje
            message = f"Recordatorio: El evento \"{event_title}\" "
            if panel_name:
                message += f"para el panel \"{panel_name}\" "
            message += f"está programado para {event_datetime} (en aproximadamente 1 hora)"
            
            # Generar ID único para la notificación - MODIFICADO: usar prefijo notification_ en lugar de reminder_
            timestamp = int(time.time() * 1000)
            notification_id = f"notification_{client_id}_{event_id}_{timestamp}"
            
            # Buscar información del cliente para incluir en notificación
            client_name = ""
            try:
                client_doc = self.db.document(f'hdd-monitor/accounts/clients/{client_id}').get()
                if client_doc.exists:
                    client_data = client_doc.to_dict()
                    client_name = client_data.get('name', '')
            except Exception as e:
                logging.error(f"Error obteniendo información del cliente: {e}")
            
            # Crear datos de notificación
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
            
            # Guardar la notificación en Firestore
            notifications_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/notifications')
            notifications_ref.document(notification_id).set(notification_data)
            logging.info(f"Notificación de recordatorio creada con ID: {notification_id}")
            
            # Enviar notificación FCM
            self.notification_handler.send_fcm_notifications(client_id, notification_data, "event")
            logging.info(f"Notificación FCM enviada para evento {event_id}")
            
        except Exception as e:
            logging.error(f"Error enviando recordatorio para evento {event_id}: {e}")

    def watch_relay_configurations(self):
        try:
            clients_ref = self.db.collection('hdd-monitor/accounts/clients')
            for client in clients_ref.stream():
                panels_ref = clients_ref.document(client.id).collection('panels')
                for panel in panels_ref.stream():
                    panel_data = panel.to_dict()
                    esp32_id = panel_data.get('esp32_id')
                    if not esp32_id:
                        continue
                        
                    relays_ref = panels_ref.document(panel.id).collection('relays')
                    
                    def create_config_handler(client_id, panel_id, esp32_id):
                        def on_relay_config_change(doc_snapshot, changes, read_time):
                            for change in changes:
                                if change.type.name == 'MODIFIED':
                                    doc = change.document
                                    new_data = doc.to_dict()
                                    old_data = self._relay_states.get(doc.reference.path, {})
                                    
                                    if (old_data.get('isActive') != new_data.get('isActive') or
                                        old_data.get('customName') != new_data.get('customName') or
                                        old_data.get('contactType') != new_data.get('contactType')):
                                        
                                        command = {
                                            'command': 'update_config',
                                            'relay_id': doc.id,
                                            'is_active': new_data.get('isActive', True),
                                            'contact_type': new_data.get('contactType', 'NO'),
                                            'custom_name': new_data.get('customName', '')
                                        }
                                        
                                        topic = f"clients/{client_id}/panels/{panel_id}/relay_config"
                                        self.mqtt_client.client.publish(
                                            topic,
                                            json.dumps(command),
                                            qos=2
                                        )
                                        logging.info(f"Configuración de relay enviada al ESP32: {command}")
                                    
                                    self._relay_states[doc.reference.path] = new_data
                                    
                        return on_relay_config_change
                    
                    watch = relays_ref.on_snapshot(create_config_handler(client.id, panel.id, esp32_id))
                    self._watch_references.append(watch)
                    
        except Exception as e:
            logging.error(f"Error iniciando observador de configuraciones: {e}")

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

        # Iniciar observadores (removido OTA)
        try:
            self.watch_events()
            self.watch_relay_states()
        except Exception as e:
            logging.error(f"Error iniciando observadores: {e}", exc_info=True)
            raise

        # Iniciar verificador de recordatorios de eventos
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

            # Manejar diferentes tipos de mensajes (removido OTA)
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

    def _on_relay_snapshot(self, doc_snapshot, changes, read_time):
        """Maneja cambios en los relays con prevención de duplicados"""
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
                    
                    # Verificar si el estado realmente cambió
                    if old_data.get('status') != new_data.get('status'):
                        # Verificar si este cambio se originó desde MQTT
                        # Si la fuente es 'mqtt', ya se notificó en _update_relay_state
                        if new_data.get('source') == 'mqtt':
                            logging.info(f"Cambio en BD originado por MQTT - Omitiendo notificación duplicada para {doc.id}")
                        else:
                            logging.info(f"Cambio detectado en relay: {doc.id}")
                            logging.info(f"Estado anterior: {old_data.get('status')}")
                            logging.info(f"Nuevo estado: {new_data.get('status')}")
                            
                            # Notificar solo si el cambio NO vino del MQTT
                            self.notification_handler.process_relay_update(doc.reference, old_data, new_data)
                        
                    # Actualizar caché con el nuevo estado independientemente del origen
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
        """Actualiza el estado de un relay y envía notificaciones"""
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
                # Crear identificador único para esta actualización
                update_id = f"mqtt_{client_id}_{panel_id}_{relay_name}_{int(time.time() * 1000)}"
                
                # Actualizar estado
                new_data = {
                    'status': new_state,
                    'date_time': datetime.now(pytz.timezone('America/Lima')).strftime('%d/%m/%Y, %H:%M'),
                    'lastUpdate': firestore.SERVER_TIMESTAMP,
                    'source': 'mqtt',  # Marcar fuente como MQTT para que _on_relay_snapshot lo ignore
                    'updateId': update_id  # Identificador único de actualización
                }
                
                # Logear el cambio para debugging
                logging.info(f"Cambio de estado en relay {relay_name}:")
                logging.info(f"Estado anterior en BD: {old_data.get('status')}")
                logging.info(f"Nuevo estado del ESP32: {new_state}")
                
                # *** IMPORTANTE: Primero enviar notificación, luego actualizar BD ***
                try:
                    logging.info(f"Enviando notificación directa para cambio de relay {relay_name}")
                    self.notification_handler.process_relay_update(
                        relay_ref, 
                        old_data, 
                        new_data, 
                        update_id=update_id
                    )
                except Exception as e:
                    logging.error(f"Error enviando notificación directa: {e}", exc_info=True)
                
                # Actualizar en BD
                try:
                    relay_ref.set(new_data, merge=True)
                    logging.info(f"Estado actualizado para relay {relay_name}")
                except Exception as e:
                    logging.error(f"Error actualizando BD: {e}", exc_info=True)
                    
        except Exception as e:
            logging.error(f"Error en _update_relay_state: {e}", exc_info=True)

    def cleanup(self):
        """Limpia los observadores al cerrar"""
        # Detener el verificador de recordatorios si está activo
        if hasattr(self, 'event_reminder'):
            try:
                self.event_reminder.stop()
                logging.info("Verificador de recordatorios detenido correctamente")
            except Exception as e:
                logging.error(f"Error deteniendo verificador de recordatorios: {e}")
                
        # Limpiar observadores
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