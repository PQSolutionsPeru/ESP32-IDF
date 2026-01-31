#!/usr/bin/env python3
import os
import time
import smtplib
from email.mime.text import MIMEText
import redis

HEARTBEAT_TIMEOUT = 120  # 2 minutos
ADMIN_EMAIL = "admin@pqsolutions.com"

def check_main_service():
    result = os.popen('systemctl is-active vm_monitor_main.service').read().strip()
    return result == 'active'

def check_mqtt_heartbeat():
    try:
        r = redis.Redis(host='localhost', decode_responses=True)
        last_hb = r.get('server:last_heartbeat')
        if not last_hb:
            return False
        return (time.time() - float(last_hb)) < HEARTBEAT_TIMEOUT
    except:
        return False

def send_alert(subject: str, body: str):
    msg = MIMEText(body)
    msg['Subject'] = f"[HDD-MONITOR CRITICAL] {subject}"
    msg['From'] = os.environ.get('SMTP_USER')
    msg['To'] = ADMIN_EMAIL

    try:
        with smtplib.SMTP('smtp.gmail.com', 587) as server:
            server.starttls()
            server.login(os.environ.get('SMTP_USER'), os.environ.get('SMTP_PASS'))
            server.send_message(msg)
    except Exception as e:
        print(f"Alert email failed: {e}")

def main():
    consecutive_failures = 0

    while True:
        time.sleep(30)

        service_ok = check_main_service()
        mqtt_ok = check_mqtt_heartbeat()

        if not service_ok or not mqtt_ok:
            consecutive_failures += 1

            if consecutive_failures >= 3:
                send_alert(
                    "Service Down - Restarting",
                    f"Service: {service_ok}, MQTT: {mqtt_ok}\nAttempting restart..."
                )

                os.system('sudo systemctl restart vm_monitor_main.service')
                consecutive_failures = 0
                time.sleep(10)

                if not check_main_service():
                    send_alert("CRITICAL: Restart Failed", "Manual intervention required!")
        else:
            consecutive_failures = 0

if __name__ == '__main__':
    main()
