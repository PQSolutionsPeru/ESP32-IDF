"""
Firestore Client Module
Provides data access methods for Firebase Firestore with caching support
"""

import firebase_admin
from firebase_admin import credentials, firestore
from datetime import datetime, timedelta
import logging
from typing import Dict, List, Any, Optional
from .cache import SimpleCache, cached

logger = logging.getLogger(__name__)


class FirestoreClient:
    """Singleton Firestore client with caching capabilities"""

    _instance = None
    _initialized = False

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self, service_account_path: str = None):
        """
        Initialize Firestore client

        Args:
            service_account_path: Path to service account JSON file
        """
        if self._initialized:
            return

        try:
            if service_account_path:
                cred = credentials.Certificate(service_account_path)
                firebase_admin.initialize_app(cred)
            else:
                # Try to initialize with default credentials
                firebase_admin.initialize_app()

            self.db = firestore.client()
            self.cache = SimpleCache(max_size=100)
            self._initialized = True
            logger.info("Firestore client initialized successfully")
        except Exception as e:
            logger.error(f"Failed to initialize Firestore client: {e}")
            raise

    @cached(ttl=30)
    def get_all_esp32_devices(self) -> List[Dict[str, Any]]:
        """
        Get all ESP32 devices

        Returns:
            List of device dictionaries with status information
        """
        try:
            devices = []
            devices_ref = self.db.collection('hdd-monitor').document('esp32').collection('registered')
            docs = devices_ref.stream()

            for doc in docs:
                data = doc.to_dict()
                data['id'] = doc.id

                # Use existing status from document (normalize to lowercase)
                if 'status' in data:
                    # Normalize status to lowercase for consistency
                    data['status'] = data['status'].lower()
                else:
                    # Determine status from lastUpdate if not present
                    last_update = data.get('lastUpdate')
                    if last_update:
                        if isinstance(last_update, datetime):
                            time_diff = datetime.utcnow() - last_update
                        else:
                            # Handle timestamp
                            time_diff = datetime.utcnow() - last_update.replace(tzinfo=None)

                        if time_diff < timedelta(minutes=5):
                            data['status'] = 'online'
                        elif time_diff < timedelta(hours=1):
                            data['status'] = 'offline'
                        else:
                            data['status'] = 'inactive'
                    else:
                        data['status'] = 'awaiting_config'

                # Map fields to expected names
                data['mac_address'] = data.get('MAC', 'N/A')
                data['ip_address'] = data.get('IP', 'N/A')
                data['last_seen'] = data.get('lastUpdate')
                data['firmware_version'] = data.get('firmwareVersion', 'N/A')
                data['test_device'] = data.get('test_device', False)

                devices.append(data)

            logger.info(f"Retrieved {len(devices)} ESP32 devices")
            return devices
        except Exception as e:
            logger.error(f"Error getting ESP32 devices: {e}")
            return []

    @cached(ttl=60)
    def get_all_clients(self) -> List[Dict[str, Any]]:
        """
        Get all clients

        Returns:
            List of client dictionaries
        """
        try:
            clients = []
            clients_ref = self.db.collection('hdd-monitor').document('accounts').collection('clients')
            docs = clients_ref.stream()

            for doc in docs:
                data = doc.to_dict()
                data['id'] = doc.id
                clients.append(data)

            logger.info(f"Retrieved {len(clients)} clients")
            return clients
        except Exception as e:
            logger.error(f"Error getting clients: {e}")
            return []

    @cached(ttl=30)
    def get_client_panels(self, client_id: str) -> List[Dict[str, Any]]:
        """
        Get all panels for a specific client

        Args:
            client_id: Client document ID

        Returns:
            List of panel dictionaries
        """
        try:
            panels = []
            panels_ref = self.db.collection('hdd-monitor').document('accounts').collection('clients').document(client_id).collection('panels')
            docs = panels_ref.stream()

            for doc in docs:
                data = doc.to_dict()
                data['id'] = doc.id
                data['client_id'] = client_id

                # Determine panel status based on ACTIVE relays only
                relays = self.get_panel_relays(client_id, doc.id)
                active_relays = [r for r in relays if r.get('isActive', False)]
                total_active_relays = len(active_relays)
                ok_relays = sum(1 for r in active_relays if r.get('status') == 'OK')

                if total_active_relays == 0:
                    data['panel_status'] = 'no_relays'
                elif ok_relays == total_active_relays:
                    data['panel_status'] = 'ok'
                elif ok_relays > 0:
                    data['panel_status'] = 'partial'
                else:
                    data['panel_status'] = 'disconnected'

                data['relay_count'] = total_active_relays
                data['total_relays'] = len(relays)  # Keep total for reference
                panels.append(data)

            logger.info(f"Retrieved {len(panels)} panels for client {client_id}")
            return panels
        except Exception as e:
            logger.error(f"Error getting panels for client {client_id}: {e}")
            return []

    @cached(ttl=10)
    def get_panel_relays(self, client_id: str, panel_id: str) -> List[Dict[str, Any]]:
        """
        Get all relays for a specific panel

        Args:
            client_id: Client document ID
            panel_id: Panel document ID

        Returns:
            List of relay dictionaries (should be 6 relays)
        """
        try:
            relays = []
            relays_ref = (self.db.collection('hdd-monitor')
                         .document('accounts')
                         .collection('clients')
                         .document(client_id)
                         .collection('panels')
                         .document(panel_id)
                         .collection('relays'))
            docs = relays_ref.stream()

            for doc in docs:
                data = doc.to_dict()
                data['id'] = doc.id
                data['relay_number'] = int(doc.id.replace('relay_', ''))
                relays.append(data)

            # Sort by relay number
            relays.sort(key=lambda x: x.get('relay_number', 0))

            logger.debug(f"Retrieved {len(relays)} relays for panel {panel_id}")
            return relays
        except Exception as e:
            logger.error(f"Error getting relays for panel {panel_id}: {e}")
            return []

    @cached(ttl=60)
    def get_client_events(self, client_id: str, status: Optional[str] = None, limit: int = 100) -> List[Dict[str, Any]]:
        """
        Get events for a specific client

        Args:
            client_id: Client document ID
            status: Filter by status (PROGRAMADO, ACEPTADO, FINALIZADO)
            limit: Maximum number of events to return

        Returns:
            List of event dictionaries
        """
        try:
            events = []
            events_ref = self.db.collection('hdd-monitor').document('accounts').collection('clients').document(client_id).collection('events')

            # Apply status filter if provided
            if status:
                query = events_ref.where('status', '==', status)
            else:
                query = events_ref

            # Try to order by date_time, fall back to no ordering if field doesn't exist
            try:
                query = query.order_by('lastUpdate', direction=firestore.Query.DESCENDING).limit(limit)
            except:
                query = query.limit(limit)

            docs = query.stream()

            for doc in docs:
                data = doc.to_dict()
                data['id'] = doc.id
                data['client_id'] = client_id
                # Map date_time to both fields for compatibility
                data['fecha_inicio'] = data.get('date_time', data.get('lastUpdate'))
                data['fecha_fin'] = data.get('date_time', data.get('lastUpdate'))
                # Map title/titulo for compatibility
                data['titulo'] = data.get('title', data.get('titulo', 'No Title'))
                data['panel_name'] = data.get('panelName', data.get('panel_name'))
                events.append(data)

            logger.info(f"Retrieved {len(events)} events for client {client_id}")
            return events
        except Exception as e:
            logger.error(f"Error getting events for client {client_id}: {e}")
            return []

    @cached(ttl=30)
    def get_dashboard_metrics(self) -> Dict[str, Any]:
        """
        Get aggregated metrics for dashboard overview

        Returns:
            Dictionary with metric values
        """
        try:
            # Get ESP32 devices online count
            devices = self.get_all_esp32_devices()
            esp32_online = sum(1 for d in devices if d.get('status') == 'online')
            esp32_total = len(devices)

            # Get all clients and calculate panel status
            clients = self.get_all_clients()
            total_panels = 0
            panels_ok = 0

            for client in clients:
                panels = self.get_client_panels(client['id'])
                total_panels += len(panels)
                panels_ok += sum(1 for p in panels if p.get('panel_status') == 'ok')

            # Get today's events count
            today = datetime.utcnow().replace(hour=0, minute=0, second=0, microsecond=0)
            events_today = 0

            for client in clients:  # Check all clients
                events = self.get_client_events(client['id'], limit=20)
                for event in events:
                    # Try to parse date_time string (format: "30-01-2026 16:30")
                    date_str = event.get('date_time', '')
                    if date_str and isinstance(date_str, str):
                        try:
                            event_date = datetime.strptime(date_str.split()[0], '%d-%m-%Y')
                            if event_date.date() == datetime.utcnow().date():
                                events_today += 1
                        except:
                            pass

            # MQTT status (placeholder - can be enhanced)
            mqtt_status = 'online'

            metrics = {
                'esp32_online': esp32_online,
                'esp32_total': esp32_total,
                'panels_ok': panels_ok,
                'panels_total': total_panels,
                'events_today': events_today,
                'mqtt_status': mqtt_status,
                'last_updated': datetime.utcnow().isoformat()
            }

            logger.info(f"Dashboard metrics: {metrics}")
            return metrics
        except Exception as e:
            logger.error(f"Error getting dashboard metrics: {e}")
            return {
                'esp32_online': 0,
                'esp32_total': 0,
                'panels_ok': 0,
                'panels_total': 0,
                'events_today': 0,
                'mqtt_status': 'unknown',
                'last_updated': datetime.utcnow().isoformat()
            }

    def get_device_by_id(self, device_id: str) -> Optional[Dict[str, Any]]:
        """
        Get a specific ESP32 device by ID

        Args:
            device_id: Device document ID

        Returns:
            Device dictionary or None if not found
        """
        try:
            doc = self.db.collection('hdd-monitor').document('esp32').collection('registered').document(device_id).get()
            if doc.exists:
                data = doc.to_dict()
                data['id'] = doc.id
                # Map fields to expected names
                data['mac_address'] = data.get('MAC', 'N/A')
                data['ip_address'] = data.get('IP', 'N/A')
                data['last_seen'] = data.get('lastUpdate')
                data['firmware_version'] = data.get('firmwareVersion', 'N/A')
                data['test_device'] = data.get('test_device', False)
                if 'status' in data and isinstance(data['status'], str):
                    data['status'] = data['status'].lower()
                return data
            return None
        except Exception as e:
            logger.error(f"Error getting device {device_id}: {e}")
            return None

    def delete_device(self, device_id: str) -> bool:
        """Delete an ESP32 device document from Firestore."""
        try:
            self.db.collection('hdd-monitor').document('esp32').collection('registered').document(device_id).delete()
            self.cache.clear()
            logger.info(f"Device {device_id} deleted from Firestore")
            return True
        except Exception as e:
            logger.error(f"Error deleting device {device_id}: {e}")
            return False

    def set_test_device(self, device_id: str, is_test: bool) -> bool:
        """
        Mark or unmark an ESP32 device as a test device.
        Devices marked as test are excluded from log upload monitoring alerts.

        Args:
            device_id: Device document ID
            is_test: True to mark as test device, False to unmark

        Returns:
            True on success, False on failure
        """
        try:
            doc_ref = self.db.collection('hdd-monitor').document('esp32').collection('registered').document(device_id)
            doc_ref.update({'test_device': is_test})
            self.cache.clear()
            logger.info(f"Device {device_id} test_device set to {is_test}")
            return True
        except Exception as e:
            logger.error(f"Error setting test_device for {device_id}: {e}")
            return False

    def clear_cache(self):
        """Clear all cached data"""
        self.cache.clear()
        logger.info("Cache cleared")
