"""
Firestore Real-time Listener Module
Listens to Firestore changes and emits events via SocketIO
"""

import logging
from threading import Thread
from google.cloud import firestore
from google.cloud.firestore_v1._helpers import DatetimeWithNanoseconds
from datetime import datetime
from typing import Optional, Any, Dict

logger = logging.getLogger(__name__)


class FirestoreRealtimeListener:
    """Manages real-time Firestore listeners and emits changes via SocketIO"""

    def __init__(self, db: firestore.Client, socketio=None):
        """
        Initialize Firestore real-time listener

        Args:
            db: Firestore client instance
            socketio: Flask-SocketIO instance
        """
        self.db = db
        self.socketio = socketio
        self.listeners = []
        self.running = False
        logger.info("Firestore real-time listener initialized")

    def start(self):
        """Start all Firestore listeners"""
        if self.running:
            logger.warning("Listeners already running")
            return

        self.running = True
        logger.info("Starting Firestore real-time listeners...")

        # Start all listeners (now using threading mode instead of gevent)
        self._start_esp32_listener()
        self._start_panels_listener()
        self._start_relays_listener()
        self._start_events_listener()

        logger.info("All Firestore real-time listeners started successfully")

    def stop(self):
        """Stop all Firestore listeners"""
        self.running = False
        for listener in self.listeners:
            listener.unsubscribe()
        self.listeners.clear()
        logger.info("All Firestore listeners stopped")

    def _serialize_data(self, data: Any) -> Any:
        """Convert Firestore types to JSON-serializable types"""
        if isinstance(data, dict):
            return {key: self._serialize_data(value) for key, value in data.items()}
        elif isinstance(data, list):
            return [self._serialize_data(item) for item in data]
        elif isinstance(data, DatetimeWithNanoseconds):
            return data.isoformat()
        elif isinstance(data, datetime):
            return data.isoformat()
        else:
            return data

    def _emit(self, event: str, data: dict):
        """Emit event via SocketIO if available"""
        if self.socketio:
            # Serialize data to make it JSON-compatible
            serialized_data = self._serialize_data(data)
            self.socketio.emit(event, serialized_data, namespace='/')
            logger.debug(f"Emitted event: {event}")

    def _start_esp32_listener(self):
        """Listen to ESP32 devices collection"""
        def on_snapshot(col_snapshot, changes, read_time):
            for change in changes:
                doc_data = change.document.to_dict()
                doc_data['id'] = change.document.id

                # Normalize status to lowercase
                if 'status' in doc_data:
                    doc_data['status'] = doc_data['status'].lower()

                if change.type.name == 'ADDED':
                    logger.info(f"ESP32 added: {doc_data['id']}")
                    self._emit('esp32_added', doc_data)

                elif change.type.name == 'MODIFIED':
                    logger.info(f"ESP32 modified: {doc_data['id']} - status: {doc_data.get('status')}")
                    self._emit('esp32_updated', doc_data)

                    # Check if status changed to offline
                    if doc_data.get('status') == 'offline':
                        self._emit('esp32_offline', {
                            'esp32_id': doc_data['id'],
                            'timestamp': datetime.utcnow().isoformat()
                        })

                elif change.type.name == 'REMOVED':
                    logger.info(f"ESP32 removed: {doc_data['id']}")
                    self._emit('esp32_removed', {'id': doc_data['id']})

                # Always emit metrics update
                self._emit_metrics_update()

        try:
            query = self.db.collection('hdd-monitor').document('esp32').collection('registered')
            listener = query.on_snapshot(on_snapshot)
            self.listeners.append(listener)
            logger.info("ESP32 devices listener attached")
        except Exception as e:
            logger.error(f"Error starting ESP32 listener: {e}")

    def _start_panels_listener(self):
        """Listen to all panels across all clients using collection_group"""
        logger.info("[Panels] Starting panels listener...")
        def on_snapshot(col_snapshot, changes, read_time):
            for change in changes:
                doc_data = change.document.to_dict()
                doc_data['id'] = change.document.id

                # Extract client_id from document path
                path_parts = change.document.reference.path.split('/')
                if 'clients' in path_parts:
                    client_idx = path_parts.index('clients')
                    if client_idx + 1 < len(path_parts):
                        doc_data['client_id'] = path_parts[client_idx + 1]

                if change.type.name in ['ADDED', 'MODIFIED']:
                    logger.info(f"Panel updated: {doc_data['id']}")
                    self._emit('panel_updated', doc_data)
                    self._emit_metrics_update()

        try:
            # Use collection_group to listen to all panels at once
            logger.info("[Panels] Setting up collection_group listener...")
            panels_query = self.db.collection_group('panels')
            listener = panels_query.on_snapshot(on_snapshot)
            self.listeners.append(listener)
            logger.info("Panels listener attached for all clients (collection_group)")
        except Exception as e:
            logger.error(f"Error starting panels listener: {e}", exc_info=True)

    def _start_relays_listener(self):
        """Listen to relay status changes using collection_group"""
        logger.info("[Relays] Starting relays listener...")
        def on_snapshot(col_snapshot, changes, read_time):
            for change in changes:
                doc_data = change.document.to_dict()
                doc_data['relay_id'] = change.document.id

                # Extract panel and client info from path
                path_parts = change.document.reference.path.split('/')
                if 'panels' in path_parts and 'clients' in path_parts:
                    panels_idx = path_parts.index('panels')
                    clients_idx = path_parts.index('clients')
                    if panels_idx + 1 < len(path_parts) and clients_idx + 1 < len(path_parts):
                        doc_data['panel_id'] = path_parts[panels_idx + 1]
                        doc_data['client_id'] = path_parts[clients_idx + 1]

                if change.type.name == 'MODIFIED':
                    old_status = getattr(change, 'before', {}).get('status')
                    new_status = doc_data.get('status')

                    if old_status != new_status:
                        logger.info(f"Relay status changed: {doc_data.get('relay_id')} from {old_status} to {new_status}")
                        self._emit('relay_status_changed', doc_data)

                        # Emit panel update to recalculate status
                        if 'panel_id' in doc_data and 'client_id' in doc_data:
                            self._emit('panel_needs_refresh', {
                                'panel_id': doc_data['panel_id'],
                                'client_id': doc_data['client_id']
                            })

                        self._emit_metrics_update()

        try:
            # Use collection_group to listen to all relays at once
            logger.info("[Relays] Setting up collection_group listener...")
            relays_query = self.db.collection_group('relays')
            listener = relays_query.on_snapshot(on_snapshot)
            self.listeners.append(listener)
            logger.info("Relays listener attached for all panels (collection_group)")
        except Exception as e:
            logger.error(f"Error starting relays listener: {e}", exc_info=True)

    def _start_events_listener(self):
        """Listen to events changes using collection_group"""
        logger.info("[Events] Starting events listener...")
        def on_snapshot(col_snapshot, changes, read_time):
            for change in changes:
                doc_data = change.document.to_dict()
                doc_data['id'] = change.document.id

                # Extract client_id from path
                path_parts = change.document.reference.path.split('/')
                if 'clients' in path_parts:
                    client_idx = path_parts.index('clients')
                    if client_idx + 1 < len(path_parts):
                        doc_data['client_id'] = path_parts[client_idx + 1]

                if change.type.name == 'ADDED':
                    logger.info(f"Event added: {doc_data.get('title', doc_data['id'])}")
                    self._emit('event_added', doc_data)

                elif change.type.name == 'MODIFIED':
                    logger.info(f"Event modified: {doc_data.get('title', doc_data['id'])}")
                    self._emit('event_updated', doc_data)

                elif change.type.name == 'REMOVED':
                    logger.info(f"Event removed: {doc_data['id']}")
                    self._emit('event_removed', {'id': doc_data['id']})

                self._emit_metrics_update()

        try:
            # Use collection_group to listen to all events at once
            logger.info("[Events] Setting up collection_group listener...")
            events_query = self.db.collection_group('events')
            listener = events_query.on_snapshot(on_snapshot)
            self.listeners.append(listener)
            logger.info("Events listener attached for all clients (collection_group)")
        except Exception as e:
            logger.error(f"Error starting events listener: {e}", exc_info=True)

    def _emit_metrics_update(self):
        """Emit a signal to refresh dashboard metrics"""
        self._emit('metrics_refresh_needed', {
            'timestamp': datetime.utcnow().isoformat()
        })
