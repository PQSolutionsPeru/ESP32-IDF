"""
ESP32 Health Monitoring Module
Monitors ESP32 device health through MQTT alerts and heartbeats
"""

import logging
import paho.mqtt.client as mqtt
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Callable
from dataclasses import dataclass, asdict
from collections import defaultdict
import json
import threading

logger = logging.getLogger(__name__)


@dataclass
class HealthAlert:
    """Health alert from ESP32 device"""
    esp32_id: str
    status: str
    issue_type: str
    description: str
    uptime_ms: int
    free_heap: int
    min_free_heap: int
    boot_count: int
    timestamp: datetime
    server_received_at: datetime


@dataclass
class ESP32HealthStatus:
    """Current health status of an ESP32 device"""
    esp32_id: str
    is_healthy: bool
    last_alert: Optional[HealthAlert]
    last_heartbeat: datetime
    total_alerts: int
    critical_alerts_24h: int
    consecutive_failures: int
    uptime_ms: int


class ESP32HealthMonitor:
    """
    Monitors ESP32 device health through MQTT alerts and heartbeats
    """

    def __init__(self, mqtt_broker: str = "localhost", mqtt_port: int = 1883):
        """
        Initialize health monitor

        Args:
            mqtt_broker: MQTT broker hostname
            mqtt_port: MQTT broker port
        """
        self.mqtt_broker = mqtt_broker
        self.mqtt_port = mqtt_port
        self.mqtt_client: Optional[mqtt.Client] = None

        # Alert storage
        self.alerts_history: Dict[str, List[HealthAlert]] = defaultdict(list)
        self.device_status: Dict[str, ESP32HealthStatus] = {}

        # Heartbeat tracking
        self.heartbeat_interval = timedelta(seconds=30)  # Expected heartbeat every 30s
        self.heartbeat_timeout = timedelta(minutes=2)  # Consider dead after 2min

        # Callbacks
        self.alert_callbacks: List[Callable[[HealthAlert], None]] = []

        # Threading
        self.lock = threading.Lock()
        self.running = False

        logger.info("ESP32 Health Monitor initialized")

    def start(self):
        """Start the health monitoring service"""
        if self.running:
            logger.warning("Health monitor already running")
            return

        try:
            # Initialize MQTT client
            self.mqtt_client = mqtt.Client(client_id="health_monitor")
            self.mqtt_client.on_connect = self._on_mqtt_connect
            self.mqtt_client.on_message = self._on_mqtt_message
            self.mqtt_client.on_disconnect = self._on_mqtt_disconnect

            # Connect to MQTT broker
            self.mqtt_client.connect(self.mqtt_broker, self.mqtt_port, 60)
            self.mqtt_client.loop_start()

            self.running = True
            logger.info(f"Health monitor started, connected to MQTT broker {self.mqtt_broker}:{self.mqtt_port}")

        except Exception as e:
            logger.error(f"Failed to start health monitor: {e}")
            raise

    def stop(self):
        """Stop the health monitoring service"""
        if not self.running:
            return

        self.running = False

        if self.mqtt_client:
            self.mqtt_client.loop_stop()
            self.mqtt_client.disconnect()

        logger.info("Health monitor stopped")

    def _on_mqtt_connect(self, client, userdata, flags, rc):
        """MQTT connection callback"""
        if rc == 0:
            logger.info("Connected to MQTT broker")
            # Subscribe to all ESP32 alerts
            client.subscribe("hdd-monitor/alerts/+")
            # Subscribe to LWT (Last Will Testament) for dead device detection
            client.subscribe("system/status/+")
            logger.info("Subscribed to hdd-monitor/alerts/+ and system/status/+ (LWT)")
        else:
            logger.error(f"MQTT connection failed with code {rc}")

    def _on_mqtt_disconnect(self, client, userdata, rc):
        """MQTT disconnection callback"""
        if rc != 0:
            logger.warning(f"Unexpected MQTT disconnection (rc={rc}), will auto-reconnect")
        else:
            logger.info("Disconnected from MQTT broker")

    def _on_mqtt_message(self, client, userdata, msg):
        """MQTT message callback"""
        try:
            topic = msg.topic
            payload = msg.payload.decode('utf-8')

            if topic.startswith("hdd-monitor/alerts/"):
                self._handle_alert_message(topic, payload)
            elif topic.startswith("system/status/"):
                self._handle_lwt_message(topic, payload)

        except Exception as e:
            logger.error(f"Error processing MQTT message from {msg.topic}: {e}")

    def _handle_alert_message(self, topic: str, payload: str):
        """Handle incoming alert message"""
        try:
            # Parse topic to get ESP32 ID
            esp32_id = topic.split('/')[-1]

            # Parse JSON payload
            data = json.loads(payload)

            # Create HealthAlert object
            alert = HealthAlert(
                esp32_id=data.get('esp32_id', esp32_id),
                status=data.get('status', 'UNKNOWN'),
                issue_type=data.get('issue_type', 'UNKNOWN'),
                description=data.get('description', ''),
                uptime_ms=data.get('uptime_ms', 0),
                free_heap=data.get('free_heap', 0),
                min_free_heap=data.get('min_free_heap', 0),
                boot_count=data.get('boot_count', 0),
                timestamp=datetime.fromtimestamp(data.get('timestamp', 0)) if data.get('timestamp') else datetime.utcnow(),
                server_received_at=datetime.utcnow()
            )

            logger.warning(f"Received alert from {esp32_id}: {alert.issue_type} - {alert.description}")

            # Store alert
            with self.lock:
                self.alerts_history[esp32_id].append(alert)

                # Keep only last 100 alerts per device
                if len(self.alerts_history[esp32_id]) > 100:
                    self.alerts_history[esp32_id] = self.alerts_history[esp32_id][-100:]

                # Update device status
                self._update_device_status(esp32_id, alert)

            # Trigger callbacks
            for callback in self.alert_callbacks:
                try:
                    callback(alert)
                except Exception as e:
                    logger.error(f"Error in alert callback: {e}")

        except json.JSONDecodeError as e:
            logger.error(f"Invalid JSON in alert message: {e}")
        except Exception as e:
            logger.error(f"Error handling alert message: {e}")

    def _handle_lwt_message(self, topic: str, payload: str):
        """Handle incoming LWT (Last Will Testament) message"""
        try:
            esp32_id = topic.split('/')[-1]
            data = json.loads(payload)
            status = data.get('status', 'UNKNOWN')

            if status == 'OFFLINE':
                # Device died unexpectedly - create critical alert
                logger.error(f"ESP32 {esp32_id} went OFFLINE unexpectedly (LWT triggered)")

                # Create alert for unexpected disconnect
                alert = HealthAlert(
                    esp32_id=esp32_id,
                    status='FATAL',
                    issue_type='UNEXPECTED_DISCONNECT',
                    description=f'Device went offline unexpectedly - LWT triggered',
                    uptime_ms=0,
                    free_heap=0,
                    min_free_heap=0,
                    boot_count=0,
                    timestamp=datetime.utcnow(),
                    server_received_at=datetime.utcnow()
                )

                # Store alert
                with self.lock:
                    self.alerts_history[esp32_id].append(alert)
                    if len(self.alerts_history[esp32_id]) > 100:
                        self.alerts_history[esp32_id] = self.alerts_history[esp32_id][-100:]
                    self._update_device_status(esp32_id, alert)

                # Trigger callbacks (will send email)
                for callback in self.alert_callbacks:
                    try:
                        callback(alert)
                    except Exception as e:
                        logger.error(f"Error in alert callback: {e}")

            elif status == 'ONLINE':
                # Device came back online
                logger.info(f"ESP32 {esp32_id} is ONLINE")
                with self.lock:
                    if esp32_id in self.device_status:
                        self.device_status[esp32_id].last_heartbeat = datetime.utcnow()

        except json.JSONDecodeError as e:
            logger.error(f"Invalid JSON in LWT message: {e}")
        except Exception as e:
            logger.error(f"Error handling LWT: {e}")

    def _update_device_status(self, esp32_id: str, alert: HealthAlert):
        """Update device health status based on alert"""
        if esp32_id not in self.device_status:
            self.device_status[esp32_id] = ESP32HealthStatus(
                esp32_id=esp32_id,
                is_healthy=True,
                last_alert=None,
                last_heartbeat=datetime.utcnow(),
                total_alerts=0,
                critical_alerts_24h=0,
                consecutive_failures=0,
                uptime_ms=0
            )

        status = self.device_status[esp32_id]
        status.last_alert = alert
        status.last_heartbeat = datetime.utcnow()
        status.uptime_ms = alert.uptime_ms
        status.total_alerts += 1

        # Count critical alerts in last 24h
        cutoff = datetime.utcnow() - timedelta(hours=24)
        status.critical_alerts_24h = sum(
            1 for a in self.alerts_history[esp32_id]
            if a.server_received_at >= cutoff and a.status in ['CRITICAL', 'FATAL']
        )

        # Update consecutive failures
        if alert.status in ['CRITICAL', 'FATAL']:
            status.consecutive_failures += 1
            status.is_healthy = False
        else:
            status.consecutive_failures = 0
            status.is_healthy = True

    def register_alert_callback(self, callback: Callable[[HealthAlert], None]):
        """
        Register a callback to be called when an alert is received

        Args:
            callback: Function that takes a HealthAlert as parameter
        """
        self.alert_callbacks.append(callback)
        logger.info(f"Registered alert callback: {callback.__name__}")

    def get_device_status(self, esp32_id: str) -> Optional[ESP32HealthStatus]:
        """
        Get current health status for a device

        Args:
            esp32_id: Device ID

        Returns:
            ESP32HealthStatus or None if device not found
        """
        with self.lock:
            return self.device_status.get(esp32_id)

    def get_all_device_status(self) -> List[ESP32HealthStatus]:
        """
        Get health status for all devices

        Returns:
            List of ESP32HealthStatus objects
        """
        with self.lock:
            return list(self.device_status.values())

    def get_device_alerts(self, esp32_id: str, limit: int = 50) -> List[HealthAlert]:
        """
        Get alert history for a device

        Args:
            esp32_id: Device ID
            limit: Maximum number of alerts to return

        Returns:
            List of HealthAlert objects (most recent first)
        """
        with self.lock:
            alerts = self.alerts_history.get(esp32_id, [])
            return list(reversed(alerts[-limit:]))

    def get_unhealthy_devices(self) -> List[ESP32HealthStatus]:
        """
        Get list of devices with health issues

        Returns:
            List of unhealthy ESP32HealthStatus objects
        """
        with self.lock:
            return [
                status for status in self.device_status.values()
                if not status.is_healthy or status.critical_alerts_24h > 0
            ]

    def check_missing_heartbeats(self) -> List[str]:
        """
        Check for devices that haven't sent heartbeat recently

        Returns:
            List of ESP32 IDs with missing heartbeats
        """
        missing = []
        cutoff = datetime.utcnow() - self.heartbeat_timeout

        with self.lock:
            for esp32_id, status in self.device_status.items():
                if status.last_heartbeat < cutoff:
                    missing.append(esp32_id)
                    logger.warning(f"Missing heartbeat from {esp32_id} (last seen: {status.last_heartbeat})")

        return missing

    def get_statistics(self) -> Dict:
        """
        Get overall health statistics

        Returns:
            Dictionary with statistics
        """
        with self.lock:
            total_devices = len(self.device_status)
            healthy_devices = sum(1 for s in self.device_status.values() if s.is_healthy)
            unhealthy_devices = total_devices - healthy_devices

            total_alerts_24h = sum(
                len([a for a in alerts if a.server_received_at >= datetime.utcnow() - timedelta(hours=24)])
                for alerts in self.alerts_history.values()
            )

            return {
                'total_devices': total_devices,
                'healthy_devices': healthy_devices,
                'unhealthy_devices': unhealthy_devices,
                'total_alerts_24h': total_alerts_24h,
                'monitored_devices': list(self.device_status.keys())
            }
