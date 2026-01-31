import logging
from datetime import datetime
import pytz
import structlog
import os

# Configurar logging estructurado
structlog.configure(
    processors=[
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.add_log_level,
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
        structlog.processors.JSONRenderer()
    ]
)

# Configuración de logging básico (fallback)
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

# PostgreSQL para redundancia
PG_CONFIG = {
    'host': 'localhost',
    'database': 'hdd_monitor',
    'user': 'hdd_monitor_user',
    'password': os.environ.get('PG_PASSWORD', 'default_password')
}

# Redis para rate limiting
REDIS_CONFIG = {
    'host': 'localhost',
    'port': 6379,
    'decode_responses': True
}