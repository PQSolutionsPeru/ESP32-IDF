"""
Health Monitoring System Integration
Integrates ESP32 health monitoring with email alerting
"""

import logging
from typing import Optional
from .esp32_health_monitor import ESP32HealthMonitor, HealthAlert
from .email_alerter import EmailAlerter, EmailConfig

logger = logging.getLogger(__name__)


class HealthMonitoringSystem:
    """Complete health monitoring system integrating MQTT monitoring and email alerts"""

    def __init__(self, email_config: Optional[EmailConfig] = None, mqtt_broker: str = "localhost"):
        self.health_monitor = ESP32HealthMonitor(mqtt_broker=mqtt_broker)
        self.email_alerter = EmailAlerter(email_config) if email_config else None
        self.emailed_alerts = set()
        logger.info("Health monitoring system initialized")

    def start(self):
        self.health_monitor.start()
        self.health_monitor.register_alert_callback(self._on_alert_received)
        logger.info("Health monitoring system started")

    def stop(self):
        self.health_monitor.stop()
        logger.info("Health monitoring system stopped")

    def _on_alert_received(self, alert: HealthAlert):
        logger.info(f"Processing alert from {alert.esp32_id}: {alert.issue_type}")
        if alert.status in ['CRITICAL', 'FATAL']:
            if self.email_alerter:
                self._send_email_alert(alert)
            else:
                logger.warning("Email alerter not configured - alert not sent via email")

    def _send_email_alert(self, alert: HealthAlert):
        try:
            alert_id = f"{alert.esp32_id}_{alert.timestamp.isoformat()}_{alert.issue_type}"
            if alert_id in self.emailed_alerts:
                logger.debug(f"Alert {alert_id} already emailed, skipping")
                return
            success = self.email_alerter.send_alert_email(alert, attach_log=True)
            if success:
                self.emailed_alerts.add(alert_id)
                if len(self.emailed_alerts) > 1000:
                    self.emailed_alerts = set(list(self.emailed_alerts)[-1000:])
                logger.info(f"Email alert sent for {alert.esp32_id}")
            else:
                logger.error(f"Failed to send email alert for {alert.esp32_id}")
        except Exception as e:
            logger.error(f"Error sending email alert: {e}", exc_info=True)

    def get_health_monitor(self) -> ESP32HealthMonitor:
        return self.health_monitor

    def get_email_alerter(self) -> Optional[EmailAlerter]:
        return self.email_alerter
