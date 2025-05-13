import logging
from datetime import datetime
import pytz

# Configuración de logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)

# Configuración de zona horaria
TIMEZONE = pytz.timezone('America/Bogota')

# Configuración MQTT para el servicio principal
MQTT_CONFIG = {
    'BROKER': 'node02.myqtthub.com',
    'PORT': 8883,
    'CLIENT_ID': 'mqtt_firestore_handler',
    'USER': 'mqtt_firestore_handler',
    'PASSWORD': 'mqtt_firestore_handler',
    'KEEPALIVE': 60,
    'QOS': 2,
    'RECONNECT_DELAY_MIN': 1,
    'RECONNECT_DELAY_MAX': 60,
    'MAX_RETRIES': float('inf'),
    'TLS_CA_CERTS': 'combined_ca.crt'
}

# Configuración MQTT para el servicio de configuración ESP32
ESP32_CONFIG_MQTT = {
    'BROKER': 'node02.myqtthub.com',
    'PORT': 8883,
    'CLIENT_ID': 'esp32_config_manager',
    'USER': 'esp32_config_manager',
    'PASSWORD': 'esp32_config_manager',
    'KEEPALIVE': 60,
    'QOS': 2,
    'RECONNECT_DELAY_MIN': 1,
    'RECONNECT_DELAY_MAX': 60,
    'MAX_RETRIES': 5,
    'TLS_CA_CERTS': 'combined_ca.crt'
}

# Configuración Firestore
FIRESTORE_PROJECT = 'fir-hdd-monitor-d00de'

def format_date() -> str:
    """Formatea la fecha actual en español, GMT-5"""
    return datetime.now(TIMEZONE).strftime('%d/%m/%Y, %H:%M')