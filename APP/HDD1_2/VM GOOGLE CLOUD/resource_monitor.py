#!/usr/bin/env python3
"""
Resource Monitor for HDD-Monitor VM
====================================

Monitors system resources (disk, RAM, CPU) and sends email alerts when
thresholds are exceeded. Provides redundant monitoring alongside Netdata.

Features:
- Disk space monitoring (80% warning, 90% critical)
- RAM utilization monitoring (85% warning, 95% critical)
- CPU load monitoring (90% warning, 95% critical)
- Email alerts via Gmail SMTP
- Rate limiting to prevent alert spam
- Healthchecks.io integration for external monitoring
- Detailed logging

Author: PQ Solutions
System: HDD-Monitor VM (hddm.pqsolutionsperu.com)
Critical: NFPA 72/UL 864 fire alarm monitoring system
"""

import os
import time
import smtplib
import psutil
import logging
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart
from datetime import datetime, timedelta
from typing import List, Dict, Optional

# External monitoring (optional)
try:
    import requests
    REQUESTS_AVAILABLE = True
except ImportError:
    REQUESTS_AVAILABLE = False
    logging.warning("requests module not available - Healthchecks.io integration disabled")

# ============================================
# CONFIGURATION
# ============================================

# Logging configuration
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger(__name__)

# Email configuration (from environment variables)
ADMIN_EMAIL = os.environ.get('ADMIN_EMAIL', 'pqsolutionsperu@gmail.com')
SMTP_USER = os.environ.get('SMTP_USER', 'pqsolutionsperu@gmail.com')
SMTP_PASS = os.environ.get('SMTP_PASS', '')
SMTP_SERVER = os.environ.get('SMTP_SERVER', 'smtp.gmail.com')
SMTP_PORT = int(os.environ.get('SMTP_PORT', '587'))

# Healthchecks.io URL (optional)
HEALTHCHECKS_URL = os.environ.get('HEALTHCHECKS_URL', None)

# Resource thresholds
DISK_WARN = 80
DISK_CRIT = 90
RAM_WARN = 85
RAM_CRIT = 95
CPU_WARN = 90
CPU_CRIT = 95

# Alert rate limiting (prevent spam)
ALERT_COOLDOWN = timedelta(hours=1)
alert_history: Dict[str, datetime] = {}

# Check interval
CHECK_INTERVAL = 300  # 5 minutes

# ============================================
# RESOURCE MONITORING
# ============================================

def check_disk() -> Optional[Dict]:
    """Check disk space usage."""
    try:
        disk = psutil.disk_usage('/')
        disk_pct = disk.percent

        if disk_pct >= DISK_CRIT:
            return {
                'type': 'CRITICAL',
                'resource': 'DISK',
                'value': disk_pct,
                'message': f'Disk at {disk_pct}% ({disk.used / (1024**3):.1f}GB used of {disk.total / (1024**3):.1f}GB)',
                'recommendation': 'Run cleanup scripts, check /home/pqsolutions/esp32_log/, clear old backups'
            }
        elif disk_pct >= DISK_WARN:
            return {
                'type': 'WARNING',
                'resource': 'DISK',
                'value': disk_pct,
                'message': f'Disk at {disk_pct}% ({disk.used / (1024**3):.1f}GB used of {disk.total / (1024**3):.1f}GB)',
                'recommendation': 'Schedule cleanup soon, monitor growth'
            }

        return None
    except Exception as e:
        logger.error(f"Error checking disk: {e}")
        return None


def check_memory() -> Optional[Dict]:
    """Check RAM usage."""
    try:
        ram = psutil.virtual_memory()
        ram_pct = ram.percent

        if ram_pct >= RAM_CRIT:
            return {
                'type': 'CRITICAL',
                'resource': 'RAM',
                'value': ram_pct,
                'message': f'Memory at {ram_pct}% ({ram.used / (1024**3):.2f}GB used of {ram.total / (1024**3):.2f}GB)',
                'recommendation': 'Restart services, check for memory leaks, consider VM upgrade'
            }
        elif ram_pct >= RAM_WARN:
            return {
                'type': 'WARNING',
                'resource': 'RAM',
                'value': ram_pct,
                'message': f'Memory at {ram_pct}% ({ram.used / (1024**3):.2f}GB used of {ram.total / (1024**3):.2f}GB)',
                'recommendation': 'Monitor memory usage, check for unusual processes'
            }

        return None
    except Exception as e:
        logger.error(f"Error checking memory: {e}")
        return None


def check_cpu() -> Optional[Dict]:
    """Check CPU usage (5-minute average)."""
    try:
        # Get 5-second sample
        cpu_pct = psutil.cpu_percent(interval=5)

        if cpu_pct >= CPU_CRIT:
            return {
                'type': 'CRITICAL',
                'resource': 'CPU',
                'value': cpu_pct,
                'message': f'CPU at {cpu_pct}% average',
                'recommendation': 'Check running processes with top/htop, verify no runaway processes'
            }
        elif cpu_pct >= CPU_WARN:
            return {
                'type': 'WARNING',
                'resource': 'CPU',
                'value': cpu_pct,
                'message': f'CPU at {cpu_pct}% average',
                'recommendation': 'Monitor CPU usage, check for high-load processes'
            }

        return None
    except Exception as e:
        logger.error(f"Error checking CPU: {e}")
        return None


def check_resources() -> List[Dict]:
    """Check all system resources."""
    issues = []

    # Check disk
    disk_issue = check_disk()
    if disk_issue:
        issues.append(disk_issue)

    # Check memory
    mem_issue = check_memory()
    if mem_issue:
        issues.append(mem_issue)

    # Check CPU
    cpu_issue = check_cpu()
    if cpu_issue:
        issues.append(cpu_issue)

    return issues


# ============================================
# ALERTING
# ============================================

def should_send_alert(issue: Dict) -> bool:
    """Check if alert should be sent (rate limiting)."""
    key = f"{issue['type']}_{issue['resource']}"
    now = datetime.now()

    if key in alert_history:
        last_sent = alert_history[key]
        if now - last_sent < ALERT_COOLDOWN:
            logger.info(f"Alert {key} in cooldown (last sent {(now - last_sent).seconds // 60} minutes ago)")
            return False

    alert_history[key] = now
    return True


def send_email_alert(issues: List[Dict]) -> bool:
    """Send email alert for detected issues."""
    if not issues:
        return False

    # Filter issues based on rate limiting
    issues_to_send = [i for i in issues if should_send_alert(i)]
    if not issues_to_send:
        return False

    # Check SMTP credentials
    if not SMTP_PASS:
        logger.error("SMTP_PASS not configured - cannot send email")
        return False

    # Determine severity
    has_critical = any(i['type'] == 'CRITICAL' for i in issues_to_send)
    severity = 'CRITICAL' if has_critical else 'WARNING'
    subject = f"[HDD-Monitor VM] {severity}: Resource Alert"

    # Build email body
    body = "HDD-Monitor VM Resource Alert\n"
    body += "=" * 70 + "\n\n"
    body += f"Severity: {severity}\n"
    body += f"Timestamp: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n"
    body += f"Server: hddm.pqsolutionsperu.com\n\n"
    body += "DETECTED ISSUES:\n"
    body += "-" * 70 + "\n\n"

    for issue in issues_to_send:
        body += f"[{issue['type']}] {issue['resource']}\n"
        body += f"  Status: {issue['message']}\n"
        body += f"  Action: {issue['recommendation']}\n\n"

    body += "-" * 70 + "\n\n"
    body += "QUICK ACTIONS:\n\n"

    if any(i['resource'] == 'DISK' for i in issues_to_send):
        body += "Disk Cleanup:\n"
        body += "  sudo systemctl start cleanup-esp32-logs.service\n"
        body += "  sudo journalctl --vacuum-time=7d\n"
        body += "  du -sh /home/pqsolutions/esp32_log/*\n\n"

    if any(i['resource'] == 'RAM' for i in issues_to_send):
        body += "Memory Management:\n"
        body += "  free -h\n"
        body += "  ps aux --sort=-%mem | head -n 10\n"
        body += "  sudo systemctl restart hdd-monitor\n\n"

    if any(i['resource'] == 'CPU' for i in issues_to_send):
        body += "CPU Investigation:\n"
        body += "  top -b -n 1 | head -n 20\n"
        body += "  ps aux --sort=-%cpu | head -n 10\n\n"

    body += "MONITORING:\n"
    body += "  Dashboard: https://hddm.pqsolutionsperu.com/netdata/\n"
    body += "  Logs: sudo journalctl -u resource-monitor -f\n\n"
    body += "This is an automated alert from the HDD-Monitor resource monitoring system.\n"
    body += "System criticality: NFPA 72 fire alarm monitoring - lives depend on uptime.\n"

    try:
        # Create message
        msg = MIMEText(body, 'plain')
        msg['Subject'] = subject
        msg['From'] = f"HDD-Monitor Alerts <{SMTP_USER}>"
        msg['To'] = ADMIN_EMAIL
        msg['X-Priority'] = '1' if has_critical else '3'

        # Send via Gmail SMTP
        with smtplib.SMTP(SMTP_SERVER, SMTP_PORT, timeout=30) as server:
            server.starttls()
            server.login(SMTP_USER, SMTP_PASS)
            server.send_message(msg)

        logger.info(f"Email alert sent: {subject}")
        return True

    except Exception as e:
        logger.error(f"Failed to send email alert: {e}")
        return False


# ============================================
# EXTERNAL MONITORING
# ============================================

def ping_healthchecks(success: bool = True) -> None:
    """Ping Healthchecks.io to confirm monitor is running."""
    if not HEALTHCHECKS_URL or not REQUESTS_AVAILABLE:
        return

    try:
        url = HEALTHCHECKS_URL if success else f"{HEALTHCHECKS_URL}/fail"
        response = requests.get(url, timeout=10)
        if response.status_code == 200:
            logger.debug("Healthchecks.io ping successful")
        else:
            logger.warning(f"Healthchecks.io returned status {response.status_code}")
    except Exception as e:
        logger.error(f"Failed to ping Healthchecks.io: {e}")


# ============================================
# MAIN LOOP
# ============================================

def main():
    """Main monitoring loop."""
    logger.info("=" * 70)
    logger.info("HDD-Monitor Resource Monitor Starting")
    logger.info("=" * 70)
    logger.info(f"Admin email: {ADMIN_EMAIL}")
    logger.info(f"Check interval: {CHECK_INTERVAL} seconds")
    logger.info(f"Thresholds: Disk {DISK_WARN}/{DISK_CRIT}%, RAM {RAM_WARN}/{RAM_CRIT}%, CPU {CPU_WARN}/{CPU_CRIT}%")
    logger.info(f"Healthchecks.io: {'Enabled' if HEALTHCHECKS_URL else 'Disabled'}")
    logger.info("=" * 70)

    iteration = 0

    while True:
        try:
            iteration += 1
            logger.info(f"Check #{iteration} at {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")

            # Check resources
            issues = check_resources()

            if issues:
                logger.warning(f"Detected {len(issues)} resource issue(s):")
                for issue in issues:
                    logger.warning(f"  [{issue['type']}] {issue['resource']}: {issue['message']}")

                # Send alerts
                send_email_alert(issues)
            else:
                logger.info("All resources within normal limits")

            # Ping external monitor
            ping_healthchecks(success=True)

            # Wait for next check
            time.sleep(CHECK_INTERVAL)

        except KeyboardInterrupt:
            logger.info("Received shutdown signal, exiting...")
            break

        except Exception as e:
            logger.error(f"Unexpected error in main loop: {e}", exc_info=True)
            ping_healthchecks(success=False)
            time.sleep(60)  # Wait 1 minute before retry

    logger.info("Resource Monitor stopped")


if __name__ == '__main__':
    main()
