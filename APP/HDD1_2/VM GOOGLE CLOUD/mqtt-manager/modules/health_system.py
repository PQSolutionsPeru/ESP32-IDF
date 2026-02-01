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
    """
    Complete health monitoring system integrating MQTT monitoring and email alerts
    """

    def __init__(self, email_config: Optional[EmailConfig] = None, mqtt_broker: str = "localhost"):
        """
        Initialize health monitoring system

        Args:
            email_config: Email configuration for alerts (None to disable email)
            mqtt_broker: MQTT broker address
        """
        self.health_monitor = ESP32HealthMonitor(mqtt_broker=mqtt_broker)
        self.email_alerter = EmailAlerter(email_config) if email_config else None

        # Track which alerts have been emailed to avoid duplicates
        self.emailed_alerts = set()

        logger.info("Health monitoring system initialized")

    def start(self):
        """Start the health monitoring system"""
        # Start MQTT health monitor
        self.health_monitor.start()

        # Register alert callback
        self.health_monitor.register_alert_callback(self._on_alert_received)

        logger.info("Health monitoring system started")

    def stop(self):
        """Stop the health monitoring system"""
        self.health_monitor.stop()
        logger.info("Health monitoring system stopped")

    def _on_alert_received(self, alert: HealthAlert):
        """
        Callback when an alert is received from ESP32

        Args:
            alert: Health alert from device
        """
        logger.info(f"Processing alert from {alert.esp32_id}: {alert.issue_type}")

        # Only send email for CRITICAL and FATAL alerts
        if alert.status in ['CRITICAL', 'FATAL']:
            if self.email_alerter:
                self._send_email_alert(alert)
            else:
                logger.warning("Email alerter not configured - alert not sent via email")

    def _send_email_alert(self, alert: HealthAlert):
        """
        Send email alert for health issue

        Args:
            alert: Health alert to send
        """
        try:
            # Create unique alert ID
            alert_id = f"{alert.esp32_id}_{alert.timestamp.isoformat()}_{alert.issue_type}"

            # Check if already sent
            if alert_id in self.emailed_alerts:
                logger.debug(f"Alert {alert_id} already emailed, skipping")
                return

            # Send email
            success = self.email_alerter.send_alert_email(alert, attach_log=True)

            if success:
                # Mark as sent
                self.emailed_alerts.add(alert_id)

                # Clean old entries (keep last 1000)
                if len(self.emailed_alerts) > 1000:
                    self.emailed_alerts = set(list(self.emailed_alerts)[-1000:])

                logger.info(f"Email alert sent for {alert.esp32_id}")
            else:
                logger.error(f"Failed to send email alert for {alert.esp32_id}")

        except Exception as e:
            logger.error(f"Error sending email alert: {e}", exc_info=True)

    def get_health_monitor(self) -> ESP32HealthMonitor:
        """Get the health monitor instance"""
        return self.health_monitor

    def get_email_alerter(self) -> Optional[EmailAlerter]:
        """Get the email alerter instance"""
        return self.email_alerter
