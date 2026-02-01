"""
Email Alert System for ESP32 Health Monitoring
Sends email notifications with log file attachments when problems are detected
"""

import logging
import smtplib
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from email.mime.base import MIMEBase
from email import encoders
from datetime import datetime, timedelta
from pathlib import Path
from typing import List, Optional, Dict
from dataclasses import dataclass
import threading

from .esp32_health_monitor import HealthAlert, ESP32HealthStatus

logger = logging.getLogger(__name__)


@dataclass
class EmailConfig:
    """Email configuration"""
    smtp_server: str
    smtp_port: int
    smtp_user: str
    smtp_password: str
    from_email: str
    to_emails: List[str]
    use_tls: bool = True


class EmailAlerter:
    """
    Sends email alerts for ESP32 health issues with log file attachments
    """

    def __init__(self, config: EmailConfig, log_base_path: str = "/home/pqsolutions/esp32_log"):
        """
        Initialize email alerter

        Args:
            config: Email configuration
            log_base_path: Base path where ESP32 logs are stored
        """
        self.config = config
        self.log_base_path = Path(log_base_path)

        # Rate limiting: Track last email sent per device
        self.last_email_sent: Dict[str, datetime] = {}
        self.min_email_interval = timedelta(hours=1)  # Max 1 email per device per hour

        # Thread safety
        self.lock = threading.Lock()

        logger.info(f"Email alerter initialized (SMTP: {config.smtp_server}:{config.smtp_port})")

    def send_alert_email(self, alert: HealthAlert, attach_log: bool = True) -> bool:
        """
        Send email alert for health issue

        Args:
            alert: Health alert to send
            attach_log: Whether to attach the latest log file

        Returns:
            True if email sent successfully
        """
        try:
            # Check rate limiting
            if not self._should_send_email(alert.esp32_id):
                logger.info(f"Skipping email for {alert.esp32_id} due to rate limiting")
                return False

            # Create email message
            msg = MIMEMultipart()
            msg['From'] = self.config.from_email
            msg['To'] = ', '.join(self.config.to_emails)
            msg['Subject'] = f"🚨 ESP32 Alert: {alert.esp32_id} - {alert.issue_type}"

            # Create email body
            body = self._create_email_body(alert)
            msg.attach(MIMEText(body, 'html'))

            # Attach log file if requested
            if attach_log:
                log_file = self._find_latest_log(alert.esp32_id)
                if log_file and log_file.exists():
                    self._attach_log_file(msg, log_file)
                else:
                    logger.warning(f"Log file not found for {alert.esp32_id}")

            # Send email
            self._send_email(msg)

            # Update rate limiting
            with self.lock:
                self.last_email_sent[alert.esp32_id] = datetime.utcnow()

            logger.info(f"Email alert sent for {alert.esp32_id}")
            return True

        except Exception as e:
            logger.error(f"Failed to send email alert: {e}", exc_info=True)
            return False

    def send_summary_email(self, unhealthy_devices: List[ESP32HealthStatus]) -> bool:
        """
        Send summary email for multiple unhealthy devices

        Args:
            unhealthy_devices: List of unhealthy device statuses

        Returns:
            True if email sent successfully
        """
        try:
            if not unhealthy_devices:
                return True

            # Create email message
            msg = MIMEMultipart()
            msg['From'] = self.config.from_email
            msg['To'] = ', '.join(self.config.to_emails)
            msg['Subject'] = f"📊 ESP32 Health Summary - {len(unhealthy_devices)} Issues Detected"

            # Create summary body
            body = self._create_summary_body(unhealthy_devices)
            msg.attach(MIMEText(body, 'html'))

            # Send email
            self._send_email(msg)

            logger.info(f"Summary email sent for {len(unhealthy_devices)} devices")
            return True

        except Exception as e:
            logger.error(f"Failed to send summary email: {e}", exc_info=True)
            return False

    def _should_send_email(self, esp32_id: str) -> bool:
        """Check if email should be sent based on rate limiting"""
        with self.lock:
            last_sent = self.last_email_sent.get(esp32_id)
            if last_sent is None:
                return True

            time_since_last = datetime.utcnow() - last_sent
            return time_since_last >= self.min_email_interval

    def _create_email_body(self, alert: HealthAlert) -> str:
        """Create HTML email body for alert"""
        severity_color = {
            'OK': '#28a745',
            'WARNING': '#ffc107',
            'CRITICAL': '#fd7e14',
            'FATAL': '#dc3545'
        }.get(alert.status, '#6c757d')

        uptime_hours = alert.uptime_ms / (1000 * 60 * 60)
        free_heap_kb = alert.free_heap / 1024
        min_heap_kb = alert.min_free_heap / 1024

        body = f"""
        <html>
        <head>
            <style>
                body {{ font-family: Arial, sans-serif; line-height: 1.6; color: #333; }}
                .container {{ max-width: 600px; margin: 0 auto; padding: 20px; }}
                .header {{ background-color: {severity_color}; color: white; padding: 20px; border-radius: 5px; }}
                .content {{ background-color: #f8f9fa; padding: 20px; margin-top: 20px; border-radius: 5px; }}
                .metric {{ display: inline-block; margin: 10px 20px 10px 0; }}
                .metric-label {{ font-size: 12px; color: #6c757d; text-transform: uppercase; }}
                .metric-value {{ font-size: 24px; font-weight: bold; color: #212529; }}
                .footer {{ margin-top: 20px; padding-top: 20px; border-top: 1px solid #dee2e6; color: #6c757d; font-size: 12px; }}
            </style>
        </head>
        <body>
            <div class="container">
                <div class="header">
                    <h1 style="margin: 0;">ESP32 Alert: {alert.status}</h1>
                    <p style="margin: 10px 0 0 0; opacity: 0.9;">Device {alert.esp32_id}</p>
                </div>

                <div class="content">
                    <h2 style="margin-top: 0; color: {severity_color};">{alert.issue_type.replace('_', ' ').title()}</h2>
                    <p style="font-size: 16px;"><strong>{alert.description}</strong></p>

                    <hr style="border: none; border-top: 1px solid #dee2e6; margin: 20px 0;">

                    <h3>Device Metrics</h3>
                    <div class="metric">
                        <div class="metric-label">Uptime</div>
                        <div class="metric-value">{uptime_hours:.1f}h</div>
                    </div>
                    <div class="metric">
                        <div class="metric-label">Free Heap</div>
                        <div class="metric-value">{free_heap_kb:.1f} KB</div>
                    </div>
                    <div class="metric">
                        <div class="metric-label">Min Heap</div>
                        <div class="metric-value">{min_heap_kb:.1f} KB</div>
                    </div>
                    <div class="metric">
                        <div class="metric-label">Boot Count</div>
                        <div class="metric-value">{alert.boot_count}</div>
                    </div>

                    <hr style="border: none; border-top: 1px solid #dee2e6; margin: 20px 0;">

                    <p><strong>Alert Time:</strong> {alert.timestamp.strftime('%Y-%m-%d %H:%M:%S UTC')}<br>
                    <strong>Server Received:</strong> {alert.server_received_at.strftime('%Y-%m-%d %H:%M:%S UTC')}</p>
                </div>

                <div class="footer">
                    <p>This is an automated alert from the HDD Monitor ESP32 Health System.<br>
                    Log file attached if available.</p>
                </div>
            </div>
        </body>
        </html>
        """
        return body

    def _create_summary_body(self, unhealthy_devices: List[ESP32HealthStatus]) -> str:
        """Create HTML email body for summary"""
        device_rows = ""
        for device in unhealthy_devices:
            last_issue = device.last_alert.issue_type if device.last_alert else "Unknown"
            device_rows += f"""
                <tr>
                    <td style="padding: 10px; border-bottom: 1px solid #dee2e6;">{device.esp32_id}</td>
                    <td style="padding: 10px; border-bottom: 1px solid #dee2e6;">{last_issue}</td>
                    <td style="padding: 10px; border-bottom: 1px solid #dee2e6;">{device.critical_alerts_24h}</td>
                    <td style="padding: 10px; border-bottom: 1px solid #dee2e6;">{device.consecutive_failures}</td>
                </tr>
            """

        body = f"""
        <html>
        <head>
            <style>
                body {{ font-family: Arial, sans-serif; line-height: 1.6; color: #333; }}
                .container {{ max-width: 700px; margin: 0 auto; padding: 20px; }}
                .header {{ background-color: #fd7e14; color: white; padding: 20px; border-radius: 5px; }}
                table {{ width: 100%; border-collapse: collapse; margin-top: 20px; }}
                th {{ background-color: #f8f9fa; padding: 10px; text-align: left; border-bottom: 2px solid #dee2e6; }}
            </style>
        </head>
        <body>
            <div class="container">
                <div class="header">
                    <h1 style="margin: 0;">ESP32 Health Summary</h1>
                    <p style="margin: 10px 0 0 0; opacity: 0.9;">{len(unhealthy_devices)} devices require attention</p>
                </div>

                <table>
                    <thead>
                        <tr>
                            <th>Device ID</th>
                            <th>Last Issue</th>
                            <th>Alerts (24h)</th>
                            <th>Consecutive Failures</th>
                        </tr>
                    </thead>
                    <tbody>
                        {device_rows}
                    </tbody>
                </table>

                <p style="margin-top: 20px; color: #6c757d; font-size: 14px;">
                    Generated at {datetime.utcnow().strftime('%Y-%m-%d %H:%M:%S UTC')}
                </p>
            </div>
        </body>
        </html>
        """
        return body

    def _find_latest_log(self, esp32_id: str) -> Optional[Path]:
        """Find the latest log file for a device"""
        device_log_dir = self.log_base_path / esp32_id

        if not device_log_dir.exists():
            logger.warning(f"Log directory not found: {device_log_dir}")
            return None

        # Find all log files
        log_files = list(device_log_dir.glob("log_*.txt"))

        if not log_files:
            logger.warning(f"No log files found in {device_log_dir}")
            return None

        # Return the most recent log file
        latest_log = max(log_files, key=lambda p: p.stat().st_mtime)
        logger.info(f"Found latest log: {latest_log}")
        return latest_log

    def _attach_log_file(self, msg: MIMEMultipart, log_file: Path):
        """Attach log file to email message"""
        try:
            with open(log_file, 'rb') as f:
                part = MIMEBase('application', 'octet-stream')
                part.set_payload(f.read())

            encoders.encode_base64(part)
            part.add_header(
                'Content-Disposition',
                f'attachment; filename={log_file.name}'
            )

            msg.attach(part)
            logger.debug(f"Attached log file: {log_file.name}")

        except Exception as e:
            logger.error(f"Failed to attach log file {log_file}: {e}")

    def _send_email(self, msg: MIMEMultipart):
        """Send email via SMTP"""
        try:
            server = smtplib.SMTP(self.config.smtp_server, self.config.smtp_port)

            if self.config.use_tls:
                server.starttls()

            server.login(self.config.smtp_user, self.config.smtp_password)
            server.send_message(msg)
            server.quit()

            logger.debug("Email sent successfully via SMTP")

        except Exception as e:
            logger.error(f"SMTP error: {e}")
            raise
