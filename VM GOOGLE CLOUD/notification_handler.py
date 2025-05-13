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
        """Obtiene el nombre de la cuenta basado en su ID y rol"""
        try:
            if not account_id:
                return 'Usuario desconocido'

            # Buscar primero en admins si el rol es admin o no se especifica
            if role == 'admin' or not role:
                admin_ref = self.db.document(f'hdd-monitor/accounts/admins/{account_id}')
                admin_doc = admin_ref.get()
                if admin_doc.exists:
                    return admin_doc.to_dict().get('name', 'Admin')

            # Si no es admin o no se encontró, buscar en usuarios de todos los clientes
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

    def process_event_update(self, event_ref: firestore.DocumentReference, old_data: Dict[str, Any], new_data: Dict[str, Any]):
        try:
            path_parts = event_ref.path.split('/')
            client_id = path_parts[3]
            event_id = path_parts[-1]

            # Determinar tipo de actualización
            update_type = None
            should_notify = False

            if not old_data and new_data:
                update_type = 'CREATE'
                should_notify = True
            elif not new_data and old_data:
                update_type = 'DELETE'
                should_notify = True
            elif old_data and new_data:
                # Verificar cambios específicos
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
                # Verificar cambios en campos relacionados con aceptación/finalización
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
                # Obtener información necesaria para la notificación
                client_doc = self.db.document(f'hdd-monitor/accounts/clients/{client_id}').get()
                client_data = client_doc.to_dict() or {}
                client_name = client_data.get('name', '')

                # Usar los datos apropiados según el tipo de actualización
                notification_data = new_data if new_data else old_data
                
                # Mensaje para la notificación
                message = self.get_event_message(update_type, notification_data)
                
                # Crear payload para notificación - CORREGIDO: ahora incluye 'event_type' en lugar de sobreescribir 'type'
                notification_payload = {
                    'event_id': event_id,
                    'event_type': notification_data.get('type', ''),  # CAMBIO: Ahora 'event_type' contiene GARANTIA, CAPACITACION, etc.
                    'type': 'event',  # AÑADIDO: Campo 'type' explícitamente como 'event'
                    'title': notification_data.get('title', ''),
                    'status': notification_data.get('status', ''),
                    'panel_id': notification_data.get('panelDocName', ''),
                    'panel_name': notification_data.get('panelName', ''),
                    'action': update_type,
                    'client_name': client_name,
                    'message': message
                }
                
                # AÑADIDO: Crear documento de notificación ANTES de enviar FCM
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
                    
                    # Guardar en Firestore ANTES de enviar FCM
                    notifications_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/notifications')
                    notifications_ref.document(notification_id).set(notification_doc)
                    logging.info(f"Documento de notificación creado con ID: {notification_id}")
                    
                except Exception as e:
                    logging.error(f"Error guardando notificación en Firestore: {e}")
                
                # Después de guardar, enviar FCM
                self.send_fcm_notifications(client_id, notification_payload, 'event')

        except Exception as e:
            logging.error(f"Error en process_event_update: {e}", exc_info=True)
            raise

    def get_event_message(self, update_type: str, event_data: Dict[str, Any]) -> str:
        """Genera el mensaje de notificación según el tipo de actualización"""
        # Intentar obtener el nombre del panel primero desde panelName
        panel_name = event_data.get('panelName')
        panel_doc_name = event_data.get('panelDocName', '')
        client_doc_name = event_data.get('clientDocName', '')
        
        # Si solo tenemos el ID del panel pero no su nombre, buscar en Firestore
        if not panel_name and panel_doc_name and client_doc_name:
            try:
                panel_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_doc_name}/panels/{panel_doc_name}')
                panel_doc = panel_ref.get()
                if panel_doc.exists:
                    panel_data = panel_doc.to_dict()
                    panel_name = panel_data.get('name')
                    
                    # Actualizar event_data con el nombre encontrado para uso futuro
                    if panel_name:
                        event_data['panelName'] = panel_name
                        logging.info(f"Nombre de panel obtenido para {panel_doc_name}: {panel_name}")
            except Exception as e:
                logging.error(f"Error obteniendo nombre del panel: {e}")
        
        # Si aún no tenemos el nombre, usar el ID como último recurso
        if not panel_name:
            panel_name = panel_doc_name
        
        panel_text = f' para el panel "{panel_name}"' if panel_name else ''
        title_text = f'"{event_data.get("title", "")}"'

        # Obtener nombres de cuentas relevantes
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
        """Procesa actualizaciones de estado de relays y envía notificaciones"""
        try:
            # Solo procesar si hay un cambio real de estado
            if old_data.get('status') != new_data.get('status'):
                path_parts = relay_ref.path.split('/')
                client_id = path_parts[3]
                panel_id = path_parts[5]
                relay_id = path_parts[7]

                # NUEVA CONDICIÓN: Ignorar notificaciones del relay Sistema si están relacionadas con ONLINE/OFFLINE
                if relay_id.lower() == "sistema" and (
                    old_data.get('status') in ['ONLINE', 'OFFLINE'] or 
                    new_data.get('status') in ['ONLINE', 'OFFLINE']):
                    logging.info(f"Ignorando notificación de relay Sistema para cambio ONLINE/OFFLINE")
                    return

                # Crear clave única para esta combinación específica de cambio
                # Usar relay_id, estados viejo y nuevo, y un timestamp redondeado a ventanas de 5 segundos
                window_time = int(time.time() * 1000 / 5000)
                cache_key = f"{client_id}_{panel_id}_{relay_id}_{old_data.get('status')}_{new_data.get('status')}_{window_time}"
                
                # Si se proporcionó update_id, añadir información pero no como parte de la clave
                # para mantener la compatibilidad con eventos de distintas fuentes
                logging_extra = f" (update_id: {update_id})" if update_id else ""
                
                current_time = time.time() * 1000
                
                # Inicializar caché de notificaciones si no existe
                if not hasattr(self, '_notification_cache'):
                    self._notification_cache = {}
                    
                # Verificar si esta combinación específica fue notificada recientemente (1 segundo)
                debounce_window = 1000  # 1 segundo en milisegundos
                if cache_key in self._notification_cache:
                    last_time = self._notification_cache[cache_key]
                    if current_time - last_time < debounce_window:
                        logging.info(f"PREVENCIÓN DUPLICADO: Ignorando notificación para relay {relay_id}: {old_data.get('status')} → {new_data.get('status')}{logging_extra}")
                        logging.info(f"Tiempo desde última notificación: {current_time - last_time}ms (ventana: {debounce_window}ms)")
                        return
                        
                # Registrar esta notificación en el caché
                self._notification_cache[cache_key] = current_time
                logging.info(f"Registrando nueva notificación en caché: {cache_key}{logging_extra}")

                # Verificar notificaciones recientes similares en Firestore
                try:
                    notifications_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/notifications')
                    recent_query = notifications_ref.where('type', '==', 'relay') \
                                                .where('relay', '==', relay_id) \
                                                .order_by('timestamp', direction=firestore.Query.DESCENDING) \
                                                .limit(1)
                    
                    recent_docs = list(recent_query.stream())
                    
                    # Si hay notificación reciente (menos de 1 segundo) para este relay, ignorar
                    if recent_docs and len(recent_docs) > 0:
                        recent_doc = recent_docs[0]
                        recent_time = recent_doc.to_dict().get('timestamp', 0)
                        if current_time - recent_time < debounce_window:
                            logging.info(f"PREVENCIÓN DUPLICADO DB: Notificación reciente para {relay_id} hace {(current_time - recent_time)/1000:.1f}s")
                            return
                except Exception as e:
                    logging.error(f"Error verificando notificaciones recientes: {e}")

                # Obtener información del panel
                panel_doc = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}').get()
                panel_data = panel_doc.to_dict() or {}
                panel_name = panel_data.get('name', '')

                # Obtener cliente info
                client_doc = self.db.document(f'hdd-monitor/accounts/clients/{client_id}').get()
                client_data = client_doc.to_dict() or {}
                client_name = client_data.get('name', '')

                # Preparar notificación
                notification_id = f"relay_{client_id}_{panel_id}_{relay_id}_{int(time.time() * 1000)}"
                
                # Crear el mensaje para la notificación
                message_text = f"El relay {relay_id} del panel \"{panel_name}\" ha cambiado de {old_data.get('status')} a {new_data.get('status')}"
                logging.info(f"Enviando notificación: {message_text}")
                
                # Preparar mensaje de notificación
                notification = messaging.Notification(
                    title=f"{client_name} - Cambio de Estado",
                    body=message_text
                )

                # Configuración Android
                android_config = messaging.AndroidConfig(
                    priority='high',
                    notification=messaging.AndroidNotification(
                        channel_id='relay_status',
                        priority='high',
                        sound='default',
                        visibility='public'
                    )
                )

                # Datos para la notificación
                message_data = {
                    'type': 'relay',
                    'clientDocName': client_id,
                    'panelDocName': panel_id,
                    'relayName': relay_id,
                    'oldStatus': old_data.get('status', ''),
                    'newStatus': new_data.get('status', ''),
                    'timestamp': str(int(time.time() * 1000))
                }

                # Primero crear el documento de notificación para evitar duplicados
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
                
                # *** IMPORTANTE: Guardar la notificación ANTES de enviar FCM ***
                notifications_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/notifications')
                notifications_ref.document(notification_id).set(notification_doc)
                logging.info(f"Documento de notificación creado con ID: {notification_id}")
                
                # Enviar a usuarios del cliente
                users_sent = 0
                users_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/users')
                for user_doc in users_ref.stream():
                    if token := user_doc.to_dict().get('fcmToken'):
                        try:
                            fcm_message = messaging.Message(
                                notification=notification,
                                data=message_data,
                                token=token,
                                android=android_config
                            )
                            response = messaging.send(fcm_message)
                            users_sent += 1
                            logging.info(f"Notificación enviada a usuario {user_doc.id}. Response: {response}")
                        except messaging.UnregisteredError:
                            # Actualizar token inválido
                            users_ref.document(user_doc.id).update({'fcmToken': None})
                        except Exception as e:
                            logging.error(f"Error enviando FCM a usuario {user_doc.id}: {e}")

                # Enviar a administradores
                admins_sent = 0
                admins_ref = self.db.collection('hdd-monitor/accounts/admins')
                for admin_doc in admins_ref.stream():
                    if token := admin_doc.to_dict().get('fcmToken'):
                        try:
                            fcm_message = messaging.Message(
                                notification=notification,
                                data=message_data,
                                token=token,
                                android=android_config
                            )
                            response = messaging.send(fcm_message)
                            admins_sent += 1
                            logging.info(f"Notificación enviada a admin {admin_doc.id}. Response: {response}")
                        except messaging.UnregisteredError:
                            # Actualizar token inválido
                            admins_ref.document(admin_doc.id).update({'fcmToken': None})
                        except Exception as e:
                            logging.error(f"Error enviando FCM a admin {admin_doc.id}: {e}")
                
                logging.info(f"Notificación procesada: enviada a {users_sent} usuarios y {admins_sent} administradores")
                
                # Limpiar notificaciones antiguas
                self.cleanup_notifications(client_id)
                
                # Limpiar caché periódicamente (solo cada 100 llamadas para evitar sobrecarga)
                if not hasattr(self, '_cleanup_counter'):
                    self._cleanup_counter = 0
                self._cleanup_counter += 1
                if self._cleanup_counter > 100:
                    self._cleanup_counter = 0
                    self._cleanup_notification_cache()
                    
        except Exception as e:
            logging.error(f"Error en process_relay_update: {e}", exc_info=True)

    def send_online_notification(self, esp32_id: str):
        """Envía notificación de dispositivo ONLINE después de haber estado OFFLINE"""
        try:
            # Buscar información del ESP32
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

            # Obtener información del panel
            panel_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
            panel_doc = panel_ref.get()
            panel_data = panel_doc.to_dict() or {}
            panel_name = panel_data.get('name', 'Panel sin nombre')

            # Obtener información del cliente
            client_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}')
            client_doc = client_ref.get()
            client_data = client_doc.to_dict() or {}
            client_name = client_data.get('name', 'Cliente sin nombre')

            # Obtener todos los administradores
            admins_ref = self.db.collection('hdd-monitor/accounts/admins')
            admins_snap = admins_ref.get()

            # Preparar la notificación
            notification = messaging.Notification(
                title=f"Panel Nuevamente ONLINE",
                body=f"El panel \"{panel_name}\" del cliente {client_name} está nuevamente ONLINE"
            )

            # Configuración de Android
            android_config = messaging.AndroidConfig(
                priority='high',
                notification=messaging.AndroidNotification(
                    channel_id='relay_status',
                    priority='high',
                    sound='default',
                    visibility='public'
                )
            )

            # Datos adicionales para la notificación
            message_data = {
                'type': 'status',
                'clientDocName': str(client_id),
                'panelDocName': str(panel_id),
                'status': 'ONLINE',
                'timestamp': str(int(time.time() * 1000))
            }

            # Enviar a usuarios del cliente también
            users_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/users')
            users_snap = users_ref.get()
            
            # Enviar a todos los usuarios (no solo administradores)
            batch = self.db.batch()
            
            # Enviar a usuarios del cliente
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
            
            # Enviar a administradores
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

            # Confirmar actualizaciones de tokens inválidos
            batch.commit()

            # Guardar la notificación en Firestore
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
        """Envía notificación de dispositivo OFFLINE a todos los usuarios"""
        try:
            # Buscar información del ESP32
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

            # Obtener información del panel
            panel_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}/panels/{panel_id}')
            panel_doc = panel_ref.get()
            panel_data = panel_doc.to_dict() or {}
            panel_name = panel_data.get('name', 'Panel sin nombre')

            # Obtener información del cliente
            client_ref = self.db.document(f'hdd-monitor/accounts/clients/{client_id}')
            client_doc = client_ref.get()
            client_data = client_doc.to_dict() or {}
            client_name = client_data.get('name', 'Cliente sin nombre')

            # Obtener usuarios del cliente
            users_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/users')
            users_snap = users_ref.get()
            
            # Obtener todos los administradores
            admins_ref = self.db.collection('hdd-monitor/accounts/admins')
            admins_snap = admins_ref.get()

            # Preparar la notificación
            notification = messaging.Notification(
                title=f"Alerta: Panel OFFLINE",
                body=f"El panel \"{panel_name}\" del cliente {client_name} está OFFLINE"
            )

            # Configuración de Android
            android_config = messaging.AndroidConfig(
                priority='high',
                notification=messaging.AndroidNotification(
                    channel_id='relay_status',
                    priority='high',
                    sound='default',
                    visibility='public'
                )
            )

            # Datos adicionales para la notificación
            message_data = {
                'type': 'status',
                'clientDocName': str(client_id),
                'panelDocName': str(panel_id),
                'status': 'OFFLINE',
                'timestamp': str(int(time.time() * 1000))
            }

            # Enviar a todos los usuarios y administradores
            batch = self.db.batch()
            
            # Enviar a usuarios del cliente
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
            
            # Enviar a administradores
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

            # Confirmar actualizaciones de tokens inválidos
            batch.commit()

            # Guardar la notificación en Firestore
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
        """Envía notificaciones FCM a usuarios y administradores"""
        try:
            # Obtener tokens FCM de usuarios del cliente
            users_ref = self.db.collection(f'hdd-monitor/accounts/clients/{client_id}/users')
            users_snap = users_ref.get()
            
            # Obtener tokens FCM de administradores
            admins_ref = self.db.collection('hdd-monitor/accounts/admins')
            admins_snap = admins_ref.get()

            # Obtener cliente info
            client_doc = self.db.document(f'hdd-monitor/accounts/clients/{client_id}').get()
            client_data = client_doc.to_dict() or {}
            client_name = client_data.get('name', '')

            # Crear notificación según el tipo
            if notification_type == 'relay':
                notification = messaging.Notification(
                    title=f"{client_name} - Cambio de Estado",
                    body=notification_data.get('message', '')
                )
                base_data = {
                    'clientDocName': str(client_id),
                    'relayName': str(notification_data.get('relay', '')),
                    'oldStatus': str(notification_data.get('old_status', '')),
                    'newStatus': str(notification_data.get('state', '')),
                    'type': 'relay',
                    'panelDocName': str(notification_data.get('panel_id', '')),
                    'timestamp': str(int(time.time() * 1000))
                }
            else:
                # CORREGIDO: Usar event_type correctamente
                event_type = notification_data.get('event_type', '') 
                notification = messaging.Notification(
                    title=f"Evento {event_type}",
                    body=notification_data.get('message', '')
                )
                # CORREGIDO: Incluir el título y mensaje en los datos para la app
                base_data = {
                    'clientDocName': str(client_id),
                    'eventId': str(notification_data.get('event_id', '')),
                    'eventType': str(event_type),
                    'title': str(notification_data.get('title', '')),  # AÑADIDO: Incluir título
                    'eventTitle': str(notification_data.get('title', '')),  # AÑADIDO: Alternativa para título
                    'message': str(notification_data.get('message', '')),  # AÑADIDO: Incluir mensaje
                    'status': str(notification_data.get('status', '')),
                    'action': str(notification_data.get('action', '')),
                    'type': 'event',
                    'panelDocName': str(notification_data.get('panel_id', '')),
                    'timestamp': str(int(time.time() * 1000))
                }

            # Configuración de Android
            android_config = messaging.AndroidConfig(
                priority='high',
                notification=messaging.AndroidNotification(
                    channel_id='event_notifications' if notification_type != 'relay' else 'relay_status',
                    priority='high',
                    sound='default',
                    visibility='public'
                )
            )

            # Asegurar que todos los valores son strings
            message_data = {k: str(v) if v is not None else '' for k, v in base_data.items()}

            # Batch para actualizar tokens inválidos
            batch = self.db.batch()
            
            # Enviar a usuarios del cliente
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

            # Enviar a administradores
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

            # Confirmar actualizaciones de tokens inválidos
            batch.commit()
            
            logging.info(f"Notificación procesada: enviada a {users_sent} usuarios y {admins_sent} administradores")

            # Ya no es necesario crear documento aquí, pues se hace ANTES en process_event_update

            # Limpiar notificaciones antiguas
            self.cleanup_notifications(client_id)

        except Exception as e:
            logging.error(f"Error en send_fcm_notifications: {e}", exc_info=True)

    def cleanup_notifications(self, client_id: str):
        """Limpia notificaciones antiguas manteniendo solo las últimas 20"""
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
        """Limpia el caché de notificaciones antiguas"""
        if hasattr(self, '_notification_cache'):
            current_time = time.time() * 1000
            # Mantener solo entradas de los últimos 60 segundos
            self._notification_cache = {
                key: timestamp for key, timestamp in self._notification_cache.items()
                if current_time - timestamp < 60000
            }