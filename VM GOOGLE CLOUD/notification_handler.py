from google.cloud import firestore
import logging
from typing import Dict, Any
from firebase_admin import messaging
import firebase_admin
from datetime import datetime
import pytz
import time

class NotificationHandler:
    def __init__(self, db: firestore.Client):
        self.db = db

    def get_account_name(self, account_id: str, role: str = None) -> str:
        try:
            if not account_id:
                return 'Usuario desconocido'

            if role == 'admin' or not role:
                admin_ref = self.db.document(f'hdd-monitor/accounts/admins/{account_id}')
                admin_doc = admin_ref.get()
                if admin_doc.exists:
                    return admin_doc.to_dict().get('name', 'Admin')

            clients_ref = self.db.collection('hdd-monitor/accounts/clients')
            for client in clients_ref.stream():
                user_ref = client.reference.collection('users').document(account_id)
                user_doc = user_ref.get()
                if user_doc.exists:
                    return user_doc.to_dict().get('name', 'Usuario')

            return 'Usuario desconocido'
            
        except Exception as e:
            logging.error(f"Error obteniendo nombre de cuenta: {e}")
            return 'Usuario desconocido'

    def process_event_update(self, event_ref: firestore.DocumentReference, old_data: Dict[str, Any], new_data: Dict[str, Any], is_initial_load: bool = False):
        try:
            if is_initial_load:
                logging.debug(f"Saltando notificación para evento en carga inicial: {event_ref.path}")
                return
                
            path_parts = event_ref.path.split('/')
            client_id = path_parts[3]
            event_id = path_parts[-1]

            update_type = None
            should_notify = False

            if not old_data and new_data:
                update_type = 'CREATE'
                should_notify = True
            elif not new_data and old_data:
                update_type = 'DELETE'
                should_notify = True
            elif old_data and new_data:
                if old_data.get('status') != new_data.get('status'):
                    should_notify = True
                    if new_data.get('status') == 'ACEPTADO':
                        update_type = 'ACCEPT'
                    elif new_data.get('status') == 'FINALIZADO':
                        update_type = 'FINISH'
                    elif old_data.get('status') == 'FINALIZADO' and new_data.get('status') == 'ACEPTADO':
                        update_type = 'REOPEN'
                    else:
                        update_type = 'STATUS_CHANGE'
                elif (not old_data.get('acceptedByAccountId') and new_data.get('acceptedByAccountId')):
                    should_notify = True
                    update_type = 'ACCEPT'
                elif (old_data.get('acceptedByAccountId') != new_data.get('acceptedByAccountId') and 
                    new_data.get('acceptedByAccountId')):
                    should_notify = True
                    update_type = 'ACCEPT'
                elif (not old_data.get('finishedByAccountId') and new_data.get('finishedByAccountId')):
                    should_notify = True
                    update_type = 'FINISH'
                elif (old_data.get('finishedByAccountId') != new_data.get('finishedByAccountId') and 
                    new_data.get('finishedByAccountId')):
                    should_notify = True
                    update_type = 'FINISH'
                elif old_data.get('date_time') != new_data.get('date_time'):
                    should_notify = True
                    update_type = 'RESCHEDULE'
                elif (old_data.get('title') != new_data.get('title') or 
                    old_data.get('text') != new_data.get('text')):
                    should_notify = True
                    update_type = 'EDIT'
                elif old_data.get('type') != new_data.get('type'):
                    should_notify = True
                    update_type = 'TYPE_CHANGE'

            if should_notify and update_type:
                logging.info(f"Procesando notificación de tipo: {update_type}")
                client_doc = self.db.document(f'hdd-monitor/accounts/clients/{client_id}').get()
                client_data = client_doc.to_dict() or {}
                client_name = client_data.get('name', '')

                notification_data = new_data if new_data else old_data
                
                message = self.get_event_message(update_type, notification_data)
                
                notification_payload = {
                    'event_id': event_id,
                    'event_type': notification_data.get('type', ''),
                    'type': 'event',
                    'title': notification_data.get('title', ''),
                    'status': notification_data.get('status', ''),
                    'panel_id': notification_data.get('panelDocName', ''),
                    'panel_name': notification_data.get('panelName', ''),
                    'action': update_type,
                    'client_name': client_name,
                    'message': message
                }
                
                try:
                    notification_id = f"notification_{client_id}_{int(time.time() * 1000)}"
                    notification_doc = notification_payload.copy()
                    notification_doc.update({
                        'date_time': datetime.now(pytz.timezone('America/Bogota')).strftime('%d/%m/%Y, %H:%M'),
                        'lastUpdate': firestore.SERVER_TIMESTAMP,
                        'documentName': notification_id,
                        'isRead': False,
                        'readByAdmin': False,
                        'timestamp': int(time.time() * 1000)
                    })
                    
                    notifications_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/notifications')
                    notifications_ref.document(notification_id).set(notification_doc)
                    logging.info(f"Documento de notificación creado con ID: {notification_id}")
                    
                except Exception as e:
                    logging.error(f"Error guardando notificación en Firestore: {e}")
                
                self.send_fcm_notifications(client_id, notification_payload, 'event')

        except Exception as e:
            logging.error(f"Error en process_event_update: {e}", exc_info=True)
            raise

    def get_event_message(self, update_type: str, event_data: Dict[str, Any]) -> str:
        panel_name = event_data.get('panelName')
        panel_doc_name = event_data.get('panelDocName', '')
        client_doc_name = event_data.get('clientDocName', '')
        
        if not panel_name and panel_doc_name and client_doc_name:
            try:
                panel_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_doc_name}/panels/{panel_doc_name}')
                panel_doc = panel_ref.get()
                if panel_doc.exists:
                    panel_data = panel_doc.to_dict()
                    panel_name = panel_data.get('name')
                    
                    if panel_name:
                        event_data['panelName'] = panel_name
                        logging.info(f"Nombre de panel obtenido para {panel_doc_name}: {panel_name}")
            except Exception as e:
                logging.error(f"Error obteniendo nombre del panel: {e}")
        
        if not panel_name:
            panel_name = panel_doc_name
        
        panel_text = f' para el panel "{panel_name}"' if panel_name else ''
        title_text = f'"{event_data.get("title", "")}"'

        created_by = self.get_account_name(
            event_data.get('createdByAccountId', ''), 
            event_data.get('createdByAccountRole', 'user')
        )
        accepted_by = self.get_account_name(
            event_data.get('acceptedByAccountId', ''),
            'admin'
        )
        finished_by = self.get_account_name(
            event_data.get('finishedByAccountId', ''),
            'admin'
        )

        messages = {
            'CREATE': f"{created_by} ha creado un nuevo evento {event_data.get('type')}: {title_text}{panel_text}",
            'ACCEPT': f"{accepted_by} ha aceptado el evento {title_text}{panel_text}",
            'FINISH': f"{finished_by} ha finalizado el evento {title_text}{panel_text}",
            'REOPEN': f"{accepted_by} ha reabierto el evento {title_text}{panel_text}",
            'STATUS_CHANGE': f"El evento {title_text} ha cambiado a estado {event_data.get('status')}{panel_text}",
            'EDIT': f"Se ha actualizado la información del evento {title_text}{panel_text}",
            'RESCHEDULE': f"Se ha reprogramado el evento {title_text} para {event_data.get('date_time')}{panel_text}",
            'TYPE_CHANGE': f"Se ha cambiado el tipo de evento {title_text} a {event_data.get('type')}{panel_text}",
            'DELETE': f"Se ha eliminado el evento {title_text}{panel_text}"
        }
        
        return messages.get(update_type, '')

    def process_relay_update(self, relay_ref: firestore.DocumentReference, old_data: Dict[str, Any], new_data: Dict[str, Any], update_id=None):
        try:
            if old_data.get('status') != new_data.get('status'):
                path_parts = relay_ref.path.split('/')
                client_id = path_parts[3]
                panel_id = path_parts[5]
                relay_id = path_parts[7]

                if relay_id.lower() == "sistema" and (
                    old_data.get('status') in ['ONLINE', 'OFFLINE'] or 
                    new_data.get('status') in ['ONLINE', 'OFFLINE']):
                    logging.info(f"Ignorando notificación de relay Sistema para cambio ONLINE/OFFLINE")
                    return

                complete_relay_data = {}
                try:
                    relay_doc = relay_ref.get()
                    if relay_doc.exists:
                        complete_relay_data = relay_doc.to_dict()
                    else:
                        complete_relay_data = new_data.copy() if new_data else {}
                except Exception as e:
                    logging.error(f"Error obteniendo datos completos del relay: {e}")
                    complete_relay_data = new_data.copy() if new_data else {}

                relay_display_name = self._get_relay_display_name(relay_id, complete_relay_data)

                window_time = int(time.time() * 1000 / 5000)
                cache_key = f"{client_id}_{panel_id}_{relay_id}_{old_data.get('status')}_{new_data.get('status')}_{window_time}"
                
                logging_extra = f" (update_id: {update_id})" if update_id else ""
                
                current_time = time.time() * 1000
                
                if not hasattr(self, '_notification_cache'):
                    self._notification_cache = {}
                    
                debounce_window = 1000
                if cache_key in self._notification_cache:
                    last_time = self._notification_cache[cache_key]
                    if current_time - last_time < debounce_window:
                        logging.info(f"PREVENCIÓN DUPLICADO: Ignorando notificación para {relay_display_name}: {old_data.get('status')} → {new_data.get('status')}{logging_extra}")
                        return
                        
                self._notification_cache[cache_key] = current_time

                panel_doc = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}').get()
                panel_data = panel_doc.to_dict() or {}
                panel_name = panel_data.get('name', '')

                client_doc = self.db.document(f'hdd-monitor/accounts/clients/{client_id}').get()
                client_data = client_doc.to_dict() or {}
                client_name = client_data.get('name', '')

                notification_id = f"relay_{client_id}_{panel_id}_{relay_id}_{int(time.time() * 1000)}"
                
                message_text = f"El {relay_display_name} del panel \"{panel_name}\" ha cambiado de {old_data.get('status')} a {new_data.get('status')}"
                
                notification_doc = {
                    'type': 'relay',
                    'relay': relay_id,
                    'panel_id': panel_id,
                    'panel_name': panel_name,
                    'client_id': client_id,
                    'state': new_data.get('status'),
                    'old_status': old_data.get('status'),
                    'message': message_text,
                    'date_time': datetime.now(pytz.timezone('America/Bogota')).strftime('%d/%m/%Y, %H:%M'),
                    'lastUpdate': firestore.SERVER_TIMESTAMP,
                    'documentName': notification_id,
                    'isRead': False,
                    'readByAdmin': False,
                    'timestamp': int(time.time() * 1000)
                }
                
                notifications_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/notifications')
                notifications_ref.document(notification_id).set(notification_doc)
                
                # ✅ CAMBIO CRÍTICO: Pasar el relay_display_name en lugar del relay_id raw
                self.send_fcm_notifications(client_id, {
                    'type': 'relay',
                    'relay': relay_display_name,  # ✅ USAR EL DISPLAY NAME
                    'relay_id': relay_id,         # ✅ AGREGAR EL ID RAW POR SI LO NECESITA LA APP
                    'panel_id': panel_id,
                    'panel_name': panel_name,
                    'state': new_data.get('status'),
                    'old_status': old_data.get('status'),
                    'message': message_text
                }, 'relay')
                    
        except Exception as e:
            logging.error(f"Error en process_relay_update: {e}", exc_info=True)

    def send_online_notification(self, esp32_id: str):
        try:
            esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            esp32_doc = esp32_ref.get()
            
            if not esp32_doc.exists:
                logging.error(f"ESP32 {esp32_id} no encontrado")
                return

            esp32_data = esp32_doc.to_dict()
            client_id = esp32_data.get('client_id')
            panel_id = esp32_data.get('panel_id')

            if not client_id or not panel_id:
                logging.error(f"ESP32 {esp32_id} no tiene cliente o panel asignado")
                return

            panel_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
            panel_doc = panel_ref.get()
            panel_data = panel_doc.to_dict() or {}
            panel_name = panel_data.get('name', 'Panel sin nombre')

            client_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}')
            client_doc = client_ref.get()
            client_data = client_doc.to_dict() or {}
            client_name = client_data.get('name', 'Cliente sin nombre')

            admins_ref = self.db.collection('hdd-monitor/accounts/admins')
            admins_snap = admins_ref.get()

            notification = messaging.Notification(
                title=f"Panel Nuevamente ONLINE",
                body=f"El panel \"{panel_name}\" del cliente {client_name} está nuevamente ONLINE"
            )

            android_config = messaging.AndroidConfig(
                priority='high',
                notification=messaging.AndroidNotification(
                    channel_id='relay_status',
                    priority='high',
                    sound='default',
                    visibility='public'
                )
            )

            message_data = {
                'type': 'status',
                'clientDocName': str(client_id),
                'panelDocName': str(panel_id),
                'status': 'ONLINE',
                'timestamp': str(int(time.time() * 1000))
            }

            users_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/users')
            users_snap = users_ref.get()
            
            batch = self.db.batch()
            
            for user_doc in users_snap:
                user_data = user_doc.to_dict()
                if token := user_data.get('fcmToken'):
                    try:
                        message = messaging.Message(
                            notification=notification,
                            data={k: str(v) if v is not None else '' for k, v in message_data.items()},
                            token=token,
                            android=android_config
                        )
                        response = messaging.send(message)
                        logging.info(f"Notificación ONLINE enviada a usuario {user_doc.id}. Response: {response}")
                    except messaging.UnregisteredError:
                        logging.warning(f"Token FCM no registrado para usuario {user_doc.id}")
                        batch.update(user_doc.reference, {'fcmToken': None})
                    except Exception as e:
                        logging.error(f"Error enviando FCM a usuario {user_doc.id}: {str(e)}")
            
            for admin_doc in admins_snap:
                admin_data = admin_doc.to_dict()
                if token := admin_data.get('fcmToken'):
                    try:
                        message = messaging.Message(
                            notification=notification,
                            data={k: str(v) if v is not None else '' for k, v in message_data.items()},
                            token=token,
                            android=android_config
                        )
                        response = messaging.send(message)
                        logging.info(f"Notificación ONLINE enviada a admin {admin_doc.id}. Response: {response}")
                    except messaging.UnregisteredError:
                        logging.warning(f"Token FCM no registrado para admin {admin_doc.id}")
                        batch.update(admin_doc.reference, {'fcmToken': None})
                    except Exception as e:
                        logging.error(f"Error enviando FCM a admin {admin_doc.id}: {str(e)}")

            batch.commit()

            try:
                notification_id = f"notif_ONL{esp32_id[-6:]}_{client_id}"
                notification_data = {
                    'date_time': datetime.now(pytz.timezone('America/Bogota')).strftime('%d/%m/%Y, %H:%M'),
                    'message': f'El panel "{panel_name}" está nuevamente ONLINE',
                    'panel_name': panel_name,
                    'lastUpdate': datetime.now(pytz.UTC),
                    'documentName': notification_id,
                    'isRead': False,
                    'readByAdmin': False,
                    'timestamp': int(time.time() * 1000)
                }
                
                notifications_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/notifications')
                notifications_ref.document(notification_id).set(notification_data)
                logging.info(f"Notificación ONLINE guardada en Firestore: {notification_id}")
                
            except Exception as e:
                logging.error(f"Error guardando notificación en Firestore: {e}")

        except Exception as e:
            logging.error(f"Error en send_online_notification: {e}", exc_info=True)

    def send_offline_notification(self, esp32_id: str):
        try:
            esp32_ref = self.db.document(f'hdd-monitor/esp32/registered/{esp32_id}')
            esp32_doc = esp32_ref.get()
            
            if not esp32_doc.exists:
                logging.error(f"ESP32 {esp32_id} no encontrado")
                return

            esp32_data = esp32_doc.to_dict()
            client_id = esp32_data.get('client_id')
            panel_id = esp32_data.get('panel_id')

            if not client_id or not panel_id:
                logging.error(f"ESP32 {esp32_id} no tiene cliente o panel asignado")
                return

            panel_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
            panel_doc = panel_ref.get()
            panel_data = panel_doc.to_dict() or {}
            panel_name = panel_data.get('name', 'Panel sin nombre')

            client_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}')
            client_doc = client_ref.get()
            client_data = client_doc.to_dict() or {}
            client_name = client_data.get('name', 'Cliente sin nombre')

            users_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/users')
            users_snap = users_ref.get()
            
            admins_ref = self.db.collection('hdd-monitor/accounts/admins')
            admins_snap = admins_ref.get()

            notification = messaging.Notification(
                title=f"Alerta: Panel OFFLINE",
                body=f"El panel \"{panel_name}\" del cliente {client_name} está OFFLINE"
            )

            android_config = messaging.AndroidConfig(
                priority='high',
                notification=messaging.AndroidNotification(
                    channel_id='relay_status',
                    priority='high',
                    sound='default',
                    visibility='public'
                )
            )

            message_data = {
                'type': 'status',
                'clientDocName': str(client_id),
                'panelDocName': str(panel_id),
                'status': 'OFFLINE',
                'timestamp': str(int(time.time() * 1000))
            }

            batch = self.db.batch()
            
            for user_doc in users_snap:
                user_data = user_doc.to_dict()
                if token := user_data.get('fcmToken'):
                    try:
                        message = messaging.Message(
                            notification=notification,
                            data={k: str(v) if v is not None else '' for k, v in message_data.items()},
                            token=token,
                            android=android_config
                        )
                        response = messaging.send(message)
                        logging.info(f"Notificación OFFLINE enviada a usuario {user_doc.id}. Response: {response}")
                    except messaging.UnregisteredError:
                        logging.warning(f"Token FCM no registrado para usuario {user_doc.id}")
                        batch.update(user_doc.reference, {'fcmToken': None})
                    except Exception as e:
                        logging.error(f"Error enviando FCM a usuario {user_doc.id}: {str(e)}")
            
            for admin_doc in admins_snap:
                admin_data = admin_doc.to_dict()
                if token := admin_data.get('fcmToken'):
                    try:
                        message = messaging.Message(
                            notification=notification,
                            data={k: str(v) if v is not None else '' for k, v in message_data.items()},
                            token=token,
                            android=android_config
                        )
                        response = messaging.send(message)
                        logging.info(f"Notificación OFFLINE enviada a admin {admin_doc.id}. Response: {response}")
                    except messaging.UnregisteredError:
                        logging.warning(f"Token FCM no registrado para admin {admin_doc.id}")
                        batch.update(admin_doc.reference, {'fcmToken': None})
                    except Exception as e:
                        logging.error(f"Error enviando FCM a admin {admin_doc.id}: {str(e)}")

            batch.commit()

            try:
                notification_id = f"notif_OFFL{esp32_id[-6:]}_{client_id}"
                notification_data = {
                    'date_time': datetime.now(pytz.timezone('America/Bogota')).strftime('%d/%m/%Y, %H:%M'),
                    'message': f'El panel "{panel_name}" está OFFLINE',
                    'panel_name': panel_name,
                    'lastUpdate': datetime.now(pytz.UTC),
                    'documentName': notification_id,
                    'isRead': False,
                    'readByAdmin': False,
                    'timestamp': int(time.time() * 1000)
                }
                
                notifications_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/notifications')
                notifications_ref.document(notification_id).set(notification_data)
                logging.info(f"Notificación OFFLINE guardada en Firestore: {notification_id}")
                
            except Exception as e:
                logging.error(f"Error guardando notificación en Firestore: {e}")

        except Exception as e:
            logging.error(f"Error en send_offline_notification: {e}", exc_info=True)

    def send_fcm_notifications(self, client_id: str, notification_data: Dict[str, Any], notification_type: str):
        try:
            users_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/users')
            users_snap = users_ref.get()
            
            admins_ref = self.db.collection('hdd-monitor/accounts/admins')
            admins_snap = admins_ref.get()

            client_doc = self.db.document(f'hdd-monitor/accounts/clients/{client_id}').get()
            client_data = client_doc.to_dict() or {}
            client_name = client_data.get('name', '')

            if notification_type == 'relay':
                notification = messaging.Notification(  # ← AQUÍ DEBE ESTAR DEFINIDA
                    title=f"{client_name} - Cambio de Estado",
                    body=notification_data.get('message', '')
                )
                base_data = {
                    'clientDocName': str(client_id),
                    'relayName': str(notification_data.get('relay', '')),
                    'oldStatus': str(notification_data.get('old_status', '')),
                    'newStatus': str(notification_data.get('state', '')),
                    'message': str(notification_data.get('message', '')),
                    'type': 'relay',
                    'panelDocName': str(notification_data.get('panel_id', '')),
                    'timestamp': str(int(time.time() * 1000))
                }
            else:
                event_type = notification_data.get('event_type', '') 
                notification = messaging.Notification(  # ← Y AQUÍ TAMBIÉN
                    title=f"Evento {event_type}",
                    body=notification_data.get('message', '')
                )
                base_data = {
                    'clientDocName': str(client_id),
                    'eventId': str(notification_data.get('event_id', '')),
                    'eventType': str(event_type),
                    'title': str(notification_data.get('title', '')),
                    'eventTitle': str(notification_data.get('title', '')),
                    'message': str(notification_data.get('message', '')),
                    'status': str(notification_data.get('status', '')),
                    'action': str(notification_data.get('action', '')),
                    'type': 'event',
                    'panelDocName': str(notification_data.get('panel_id', '')),
                    'timestamp': str(int(time.time() * 1000))
                }

            android_config = messaging.AndroidConfig(
                priority='high',
                notification=messaging.AndroidNotification(
                    channel_id='event_notifications' if notification_type != 'relay' else 'relay_status',
                    priority='high',
                    sound='default',
                    visibility='public'
                )
            )

            message_data = {k: str(v) if v is not None else '' for k, v in base_data.items()}

            batch = self.db.batch()
            
            users_sent = 0
            for user_doc in users_snap:
                user_data = user_doc.to_dict()
                if token := user_data.get('fcmToken'):
                    try:
                        message = messaging.Message(
                            notification=notification,
                            data=message_data,
                            token=token,
                            android=android_config
                        )
                        response = messaging.send(message)
                        users_sent += 1
                        logging.info(f"Notificación enviada a usuario {user_doc.id}. Response: {response}")
                    except messaging.UnregisteredError as e:
                        logging.warning(f"Token FCM no registrado para usuario {user_doc.id}: {e}")
                        batch.update(user_doc.reference, {'fcmToken': None})
                    except Exception as e:
                        logging.error(f"Error enviando FCM a usuario {user_doc.id}: {str(e)}")

            admins_sent = 0
            for admin_doc in admins_snap:
                admin_data = admin_doc.to_dict()
                if token := admin_data.get('fcmToken'):
                    try:
                        message = messaging.Message(
                            notification=notification,
                            data=message_data,
                            token=token,
                            android=android_config
                        )
                        response = messaging.send(message)
                        admins_sent += 1
                        logging.info(f"Notificación enviada a admin {admin_doc.id}. Response: {response}")
                    except messaging.UnregisteredError as e:
                        logging.warning(f"Token FCM no registrado para admin {admin_doc.id}: {e}")
                        batch.update(admin_doc.reference, {'fcmToken': None})
                    except Exception as e:
                        logging.error(f"Error enviando FCM a admin {admin_doc.id}: {str(e)}")

            batch.commit()
            
            logging.info(f"Notificación procesada: enviada a {users_sent} usuarios y {admins_sent} administradores")

            self.cleanup_notifications(client_id)

        except Exception as e:
            logging.error(f"Error en send_fcm_notifications: {e}", exc_info=True)

    def cleanup_notifications(self, client_id: str):
        try:
            notifications_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/notifications')
            snapshot = notifications_ref.order_by('date_time', direction=firestore.Query.DESCENDING).get()

            if len(snapshot) > 20:
                batch = self.db.batch()
                docs_to_delete = snapshot[20:]
                for doc in docs_to_delete:
                    batch.delete(doc.reference)
                batch.commit()
                logging.info(f"Limpiadas {len(docs_to_delete)} notificaciones antiguas del cliente {client_id}")
        except Exception as e:
            logging.error(f"Error en cleanup_notifications: {e}", exc_info=True)

    def _cleanup_notification_cache(self):
        if hasattr(self, '_notification_cache'):
            current_time = time.time() * 1000
            self._notification_cache = {
                key: timestamp for key, timestamp in self._notification_cache.items()
                if current_time - timestamp < 60000
            }

    def _get_relay_display_name(self, relay_id: str, relay_data: Dict[str, Any]) -> str:
        if relay_data and isinstance(relay_data, dict):
            custom_name = relay_data.get('customName', '').strip()
            if custom_name:
                return f"relay {custom_name}"
        
        return relay_id