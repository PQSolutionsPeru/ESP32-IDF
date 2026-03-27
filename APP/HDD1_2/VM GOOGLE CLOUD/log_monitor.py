"""
ESP32 Log Upload Monitor
Verifica que cada dispositivo ESP32 suba sus logs diariamente.
Si un dispositivo lleva mas de 26 horas sin subir logs, envia alerta por email.

Ejecutar: python3 /home/pqsolutionsperu/log_monitor.py
Programado via systemd timer: diariamente a las 08:00 UTC (03:00 Lima)
"""

import json
import logging
import os
import smtplib
import sys
from datetime import datetime, timedelta, timezone
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from pathlib import Path

try:
    import firebase_admin
    from firebase_admin import credentials, firestore as fb_firestore
    FIREBASE_AVAILABLE = True
except ImportError:
    FIREBASE_AVAILABLE = False

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

LOG_BASE_DIR = Path("/home/pqsolutions/esp32_log")
EMAIL_CONFIG_PATH = Path("/home/pqsolutions/mqtt-manager/config.email.json")
FIREBASE_CREDENTIALS = Path("/home/pqsolutionsperu/vm-service-key.json")
MAX_LOG_AGE_HOURS = 26
MONITOR_LOG = "/var/log/hdd_monitor_log_check.log"


def get_test_device_ids() -> set:
    """Return set of device IDs marked as test_device=True in Firestore."""
    if not FIREBASE_AVAILABLE:
        return set()
    if not FIREBASE_CREDENTIALS.exists():
        return set()
    try:
        if not firebase_admin._apps:
            cred = credentials.Certificate(str(FIREBASE_CREDENTIALS))
            firebase_admin.initialize_app(cred)
        db = fb_firestore.client()
        docs = db.collection('hdd-monitor').document('esp32').collection('registered').stream()
        return {doc.id for doc in docs if doc.to_dict().get('test_device', False)}
    except Exception as e:
        logger.warning(f"Could not fetch test devices from Firestore: {e}")
        return set()


def log_to_file(message: str):
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
    try:
        with open(MONITOR_LOG, "a") as f:
            f.write(f"[{timestamp}] {message}\n")
    except Exception:
        pass


def load_email_config() -> dict:
    if not EMAIL_CONFIG_PATH.exists():
        raise FileNotFoundError(f"Email config not found: {EMAIL_CONFIG_PATH}")
    with open(EMAIL_CONFIG_PATH) as f:
        return json.load(f)


def get_newest_log_time(device_dir: Path):
    log_files = list(device_dir.glob("log_*.txt"))
    if not log_files:
        return None
    newest = max(log_files, key=lambda p: p.stat().st_mtime)
    return datetime.fromtimestamp(newest.stat().st_mtime, tz=timezone.utc), newest.name


def build_email_body(missing_devices: list, check_time: datetime) -> str:
    rows = ""
    for device_id, newest_time, filename, hours_ago in missing_devices:
        if newest_time:
            last_log_str = newest_time.strftime("%Y-%m-%d %H:%M UTC")
            last_file_str = filename
            elapsed_str = f"{hours_ago:.1f} horas"
            color = "#fd7e14" if hours_ago < 48 else "#dc3545"
        else:
            last_log_str = "Nunca"
            last_file_str = "-"
            elapsed_str = "Sin registros"
            color = "#dc3545"

        rows += f"""
        <tr>
            <td style="padding:10px;border-bottom:1px solid #dee2e6;font-family:monospace;">{device_id}</td>
            <td style="padding:10px;border-bottom:1px solid #dee2e6;">{last_log_str}</td>
            <td style="padding:10px;border-bottom:1px solid #dee2e6;font-family:monospace;font-size:12px;color:#6c757d;">{last_file_str}</td>
            <td style="padding:10px;border-bottom:1px solid #dee2e6;color:{color};font-weight:bold;">{elapsed_str}</td>
        </tr>"""

    lima_time = check_time.astimezone(timezone(timedelta(hours=-5))).strftime("%Y-%m-%d %H:%M hora Lima")

    return f"""
    <html>
    <body style="font-family:Arial,sans-serif;color:#333;margin:0;padding:0;">
    <div style="max-width:650px;margin:0 auto;padding:20px;">

        <div style="background:#e67e22;color:white;padding:20px;border-radius:6px;">
            <h2 style="margin:0;font-size:20px;">&#9888;&#65039; Logs no recibidos - HDD Monitor</h2>
            <p style="margin:8px 0 0 0;opacity:.9;">
                {len(missing_devices)} dispositivo(s) llevan mas de {MAX_LOG_AGE_HOURS} horas sin subir logs
            </p>
        </div>

        <div style="margin-top:20px;">
            <table style="width:100%;border-collapse:collapse;">
                <thead>
                    <tr style="background:#f8f9fa;">
                        <th style="text-align:left;padding:10px;border-bottom:2px solid #dee2e6;">Dispositivo</th>
                        <th style="text-align:left;padding:10px;border-bottom:2px solid #dee2e6;">Ultimo log recibido</th>
                        <th style="text-align:left;padding:10px;border-bottom:2px solid #dee2e6;">Archivo</th>
                        <th style="text-align:left;padding:10px;border-bottom:2px solid #dee2e6;">Tiempo sin logs</th>
                    </tr>
                </thead>
                <tbody>
                    {rows}
                </tbody>
            </table>
        </div>

        <div style="margin-top:20px;padding:15px;background:#fff3cd;border-radius:6px;border-left:4px solid #ffc107;">
            <strong>Comportamiento esperado:</strong> cada ESP32 sube sus logs diariamente
            a medianoche hora Lima (05:00 UTC). Si llevan mas de {MAX_LOG_AGE_HOURS} horas
            sin subir, puede indicar un problema de conectividad, reinicio no exitoso,
            o falla en el sistema de logs del dispositivo.
        </div>

        <p style="margin-top:20px;color:#6c757d;font-size:12px;">
            Verificacion realizada: {lima_time}<br>
            Este es un aviso automatico del sistema HDD Monitor - PQ Solutions
        </p>
    </div>
    </body>
    </html>
    """


def send_alert_email(config: dict, missing_devices: list, check_time: datetime):
    subject = (
        f"[HDD Monitor] {len(missing_devices)} dispositivo(s) sin logs "
        f"({check_time.strftime('%d/%m/%Y')})"
    )

    msg = MIMEMultipart()
    msg["From"] = config["from_email"]
    msg["To"] = ", ".join(config["to_emails"])
    msg["Subject"] = subject
    msg.attach(MIMEText(build_email_body(missing_devices, check_time), "html"))

    server = smtplib.SMTP(config["smtp_server"], config["smtp_port"])
    if config.get("use_tls", True):
        server.starttls()
    server.login(config["smtp_user"], config["smtp_password"])
    server.send_message(msg)
    server.quit()


def check_logs():
    now = datetime.now(tz=timezone.utc)
    threshold = now - timedelta(hours=MAX_LOG_AGE_HOURS)

    log_to_file("=" * 60)
    log_to_file(f"Iniciando verificacion de logs")

    test_devices = get_test_device_ids()
    if test_devices:
        log_to_file(f"Dispositivos en modo pruebas (excluidos): {', '.join(sorted(test_devices))}")

    if not LOG_BASE_DIR.exists():
        msg = f"ERROR: Directorio de logs no encontrado: {LOG_BASE_DIR}"
        logger.error(msg)
        log_to_file(msg)
        return

    device_dirs = sorted(
        [d for d in LOG_BASE_DIR.iterdir() if d.is_dir()]
    )

    if not device_dirs:
        log_to_file("No se encontraron dispositivos en el directorio de logs")
        return

    missing_devices = []

    for device_dir in device_dirs:
        device_id = device_dir.name

        if device_id in test_devices:
            log_to_file(f"  [{device_id}] OMITIDO - marcado como dispositivo de pruebas")
            continue
        result = get_newest_log_time(device_dir)

        if result is None:
            missing_devices.append((device_id, None, None, None))
            msg = f"  [{device_id}] SIN LOGS - nunca ha subido archivos"
            logger.warning(msg)
            log_to_file(msg)
        else:
            newest_time, filename = result
            hours_ago = (now - newest_time).total_seconds() / 3600

            if newest_time < threshold:
                missing_devices.append((device_id, newest_time, filename, hours_ago))
                msg = f"  [{device_id}] ATRASADO - ultimo log: {filename} ({hours_ago:.1f}h atras)"
                logger.warning(msg)
                log_to_file(msg)
            else:
                msg = f"  [{device_id}] OK - ultimo log: {filename} ({hours_ago:.1f}h atras)"
                logger.info(msg)
                log_to_file(msg)

    if missing_devices:
        log_to_file(f"Enviando alerta para {len(missing_devices)} dispositivo(s)...")
        try:
            config = load_email_config()
            send_alert_email(config, missing_devices, now)
            msg = f"Email de alerta enviado a: {', '.join(config['to_emails'])}"
            logger.info(msg)
            log_to_file(msg)
        except Exception as e:
            msg = f"ERROR al enviar email: {e}"
            logger.error(msg)
            log_to_file(msg)
            sys.exit(1)
    else:
        monitored = len(device_dirs) - len(test_devices)
        msg = f"Todos los dispositivos monitoreados ({monitored}) estan subiendo logs correctamente"
        logger.info(msg)
        log_to_file(msg)

    log_to_file("Verificacion completada")


if __name__ == "__main__":
    check_logs()
