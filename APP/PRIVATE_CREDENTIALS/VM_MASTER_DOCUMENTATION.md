# VM MASTER DOCUMENTATION - HDD Monitor Safety System
# Google Cloud VM - DOCUMENTACIÓN MAESTRA Y ÚNICA FUENTE DE VERDAD

**ADVERTENCIA DE SEGURIDAD**: Este documento contiene credenciales reales y está ubicado en PRIVATE_CREDENTIALS (fuera de Git).

---

## 📑 TABLA DE CONTENIDO

1. [Información General y Credenciales](#1-información-general-y-credenciales)
2. [Arquitectura Completa del Sistema](#2-arquitectura-completa-del-sistema)
3. [Sistema de Seguridad NFPA 72](#3-sistema-de-seguridad-nfpa-72)
4. [Deployment Completo Paso a Paso](#4-deployment-completo-paso-a-paso)
5. [Configuración de Servicios](#5-configuración-de-servicios)
6. [Código Fuente Python Completo](#6-código-fuente-python-completo)
7. [Base de Datos PostgreSQL](#7-base-de-datos-postgresql)
8. [Monitoreo y Troubleshooting](#8-monitoreo-y-troubleshooting)
9. [Security y Mejores Prácticas](#9-security-y-mejores-prácticas)
10. [Scripts Auxiliares](#10-scripts-auxiliares)
11. [Gestión de MQTT Manager Web UI](#11-gestión-de-mqtt-manager-web-ui)

---

## 1. INFORMACIÓN GENERAL Y CREDENCIALES

### 1.1 Datos de Conexión VM

```yaml
IP Estática: 34.63.146.196
Hostname: instanciavm-myqtthub
OS: Debian GNU/Linux 12 (Bookworm)
Kernel: 6.1.0-37-cloud-amd64
Tipo de Instancia: e2-small
Zona: us-central1-c
Proyecto GCP: fir-hdd-monitor-d00de

# Usuarios del sistema
Usuario SSH Principal: pqsolutionsperu
Usuario de Servicios/Apps: pqsolutions
Usuario MQTT Broker: mosquitto

# Directorios principales
Home SSH: /home/pqsolutionsperu
Home Servicios: /home/pqsolutions
Proyecto principal: /home/pqsolutions/hdd-monitor/
Entorno virtual: /home/pqsolutions/venv/
Logs ESP32: /home/pqsolutions/esp32_log/
```

### 1.2 URL SSH Browser de Google Cloud

```
https://ssh.cloud.google.com/v2/ssh/projects/fir-hdd-monitor-d00de/zones/us-central1-c/instances/instanciavm-myqtthub
```

### 1.3 Credenciales Críticas

#### PostgreSQL Database

```bash
Database: hdd_monitor
User: hdd_monitor_user
Password: nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z
Host: localhost
Port: 5432 (default)
```

**Variable de entorno (configurada en .bashrc del usuario pqsolutionsperu)**:
```bash
export PG_PASSWORD='nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z'
```

#### MQTT Manager Web UI (hddm.pqsolutionsperu.com)

```yaml
Secret Key (Flask): 0b9e46369d7de802127fd58ad3ffc0f5bf27bdf70c22bb7f

Admin Password Hash (scrypt): scrypt:32768:8:1$oKcrWOh9tbp3UlWR$619f70528fe20bbbfbb859d677e38cb851ab013a24a56bddcce7dc2ff139969487ddc6249758f409b5a497d5174a8ed726582c2c10853642c861411e0652f641

Usuario: pqsowner
Contraseña: (hasheada arriba con scrypt - configurar en variables de entorno)
```

**Configuración en systemd service** (`/etc/systemd/system/mqtt-manager.service`):
```ini
Environment="FLASK_SECRET_KEY=0b9e46369d7de802127fd58ad3ffc0f5bf27bdf70c22bb7f"
Environment="ADMIN_PASSWORD_HASH=scrypt:32768:8:1$oKcrWOh9tbp3UlWR$619f70528fe20bbbfbb859d677e38cb851ab013a24a56bddcce7dc2ff139969487ddc6249758f409b5a497d5174a8ed726582c2c10853642c861411e0652f641"
```

#### Usuarios MQTT Registrados

Ubicación: `/etc/mosquitto/passwd`

```
mqtt_firestore_handler:mqtt_firestore_handler
esp32_config_manager:esp32_config_manager
esp32_devices:esp32_devices
admin_hdd:admin_hdd
```

Usuarios dinámicos ESP32 (se crean automáticamente):
- Formato: `{ESP32_ID}:{ESP32_ID}`
- Ejemplo: `42A8ACA0:42A8ACA0`, `1694ACA8:1694ACA8`

#### Firebase/Firestore

```yaml
Proyecto: fir-hdd-monitor-d00de
Credenciales: /home/pqsolutions/credentials/
Service Account Key: firebase-service-account.json
```

### 1.4 Dominios Configurados

| Dominio | IP | Puerto | Servicio |
|---------|-----|--------|----------|
| `n8n.pqsolutionsperu.com` | 34.63.146.196 | - | N8N Automation |
| `hddm.pqsolutionsperu.com` | 34.63.146.196 | 80/443 | MQTT Manager Web UI |

### 1.5 Certificados SSL

**Let's Encrypt SSL Certificates** para `hddm.pqsolutionsperu.com`:

```bash
# Ubicación de certificados
/etc/letsencrypt/live/hddm.pqsolutionsperu.com/cert.pem
/etc/letsencrypt/live/hddm.pqsolutionsperu.com/privkey.pem
/etc/letsencrypt/live/hddm.pqsolutionsperu.com/chain.pem
/etc/letsencrypt/live/hddm.pqsolutionsperu.com/fullchain.pem

# Permisos
# Propietario: root:mosquitto
# privkey.pem: 640 (mosquitto necesita acceso de lectura)

# Renovación automática
sudo systemctl status certbot.timer
sudo certbot renew --dry-run  # Test de renovación
```

---

## 2. ARQUITECTURA COMPLETA DEL SISTEMA

### 2.1 Estructura de Directorios

```
/home/pqsolutions/
├── venv/                          # Entorno virtual principal (Python 3.11.2)
│   └── [USADO POR: hdd-monitor]
│
├── mqtt-manager-venv/             # Entorno virtual para MQTT Manager Web UI
│   └── [Dependencias: Flask, flask-cors, gunicorn]
│
├── hdd-monitor/                   # Proyecto principal de monitoreo
│   ├── main.py                    # Entry point + Redis heartbeat
│   ├── mqtt_client.py             # Cliente MQTT + validaciones
│   ├── firestore_handler.py       # Handler Firebase/Firestore + PostgreSQL
│   ├── notification_handler.py    # Handler notificaciones + rate limiting + FCM retry
│   ├── config.py                  # Configuración central + structlog
│   ├── rate_limiter.py            # Sistema rate limiting con Redis (NUEVO)
│   ├── nfpa_metrics.py            # Métricas NFPA 72 compliance (NUEVO)
│   ├── system_watchdog.py         # Watchdog externo (NUEVO)
│   ├── esp32_config_manager.py    # Gestor configuración ESP32
│   ├── log_server.py              # Servidor HTTP logs ESP32 (puerto 8080)
│   ├── log_cleanup.py             # Limpieza de logs antiguos
│   ├── monitor_mqtt_service.py    # Monitor de servicios
│   ├── requirements.txt           # Dependencias Python (actualizado)
│   ├── setup_postgresql.sql       # Schema base de datos (NUEVO)
│   ├── hdd-monitor-watchdog.service  # Servicio systemd watchdog (NUEVO)
│   ├── verify_installation.sh     # Script verificación (NUEVO)
│   ├── combined_ca.crt            # Certificado CA para MQTT SSL
│   └── logs/                      # Directorio de logs
│
├── mqtt-manager/                  # Gestor Web de usuarios MQTT
│   ├── app.py                     # API Flask para CRUD usuarios
│   ├── templates/
│   │   ├── index.html             # Dashboard principal
│   │   └── login.html             # Página de login
│   ├── static/
│   │   ├── css/style.css
│   │   └── js/app.js
│   └── [Puerto: 5000, Proxy: Nginx]
│
├── esp32_log/                     # Logs almacenados por ESP32 ID
│   ├── 1694ACA8/
│   └── 42A8ACA0/
│
├── credentials/                   # Credenciales Firebase, GCP, etc.
├── services/                      # Archivos de servicio systemd
└── ca-hdd-monitor.crt            # Certificado CA para MQTT SSL
```

### 2.2 Servicios Activos

#### Mosquitto MQTT Broker

```bash
Service: mosquitto.service
Status: Active (running)
Configuración: /etc/mosquitto/mosquitto.conf
Config HDD: /etc/mosquitto/conf.d/hdd-monitor.conf
Usuarios: /etc/mosquitto/passwd
Logs: /var/log/mosquitto/mosquitto.log
```

**Puertos**:
- `1883` - MQTT sin SSL
- `8883` - MQTT con SSL/TLS (Let's Encrypt)

**Configuración SSL** (`/etc/mosquitto/conf.d/hdd-monitor.conf`):
```conf
listener 8883 0.0.0.0
certfile /etc/letsencrypt/live/hddm.pqsolutionsperu.com/cert.pem
keyfile /etc/letsencrypt/live/hddm.pqsolutionsperu.com/privkey.pem
cafile /etc/letsencrypt/live/hddm.pqsolutionsperu.com/chain.pem
require_certificate false
allow_anonymous false
password_file /etc/mosquitto/passwd
log_type all
```

**ESP32 Connection**:
```c
// En ESP32, usar:
// Broker: mqtts://hddm.pqsolutionsperu.com:8883
// SSL: ESP-IDF Certificate Bundle (esp_crt_bundle_attach)
// Verificación de dominio habilitada
```

#### ESP32 Log Server

```bash
Proceso: python3 log_server.py
Puerto: 8080
Venv: /home/pqsolutions/venv
Service: logserver.service
```

**Función**: Recibe logs HTTP de ESP32 y los almacena en `/home/pqsolutions/esp32_log/{ESP32_ID}/`

#### Nginx Web Server

```bash
Service: nginx.service
Version: 1.22.1
Status: Active (running)
```

**Puertos**:
- `80` - HTTP (redirect a HTTPS)
- `443` - HTTPS (SSL con Let's Encrypt)

**Función**:
- Reverse proxy para MQTT Manager (Flask app)
- Terminación SSL/TLS
- Servidor de archivos estáticos

#### MQTT Manager Web UI

```bash
Service: mqtt-manager.service
Tipo: Flask + Gunicorn (2 workers)
Puerto: 5000 (interno)
Proxy: Nginx → https://hddm.pqsolutionsperu.com
Venv: /home/pqsolutions/mqtt-manager-venv
Status: Active (running)
```

**Función**:
- CRUD de usuarios MQTT en `/etc/mosquitto/passwd`
- Interfaz web para gestionar ESP32 devices
- Sistema de autenticación seguro (Flask sessions)
- Login con usuario `pqsowner`
- Password hashing con Werkzeug (scrypt)
- Sesiones con expiración de 24 horas

#### HDD Monitor Watchdog (Sistema de Seguridad)

```bash
Service: hdd-monitor-watchdog.service
Script: /home/pqsolutionsperu/system_watchdog.py
User: pqsolutionsperu
Status: Active (running)
Restart: always (RestartSec=10)
```

**Función**:
- Monitoreo externo del servicio principal cada 30 segundos
- Verifica estado systemd
- Verifica heartbeat Redis (timeout 2 minutos)
- Reinicia servicio tras 3 fallos consecutivos
- Envía alertas por email en caso de fallo

### 2.3 Firewall (UFW)

```bash
Status: active
```

| Puerto | Protocolo | Servicio |
|--------|-----------|----------|
| 22     | TCP       | SSH |
| 80     | TCP       | HTTP (Nginx) |
| 443    | TCP       | HTTPS (Nginx) |
| 1883   | TCP       | MQTT |
| 8883   | TCP       | MQTT SSL |
| 8080   | TCP       | ESP32 Log Server |

### 2.4 Flujo de Datos Completo

#### ESP32 → Firestore (Eventos de Relay)

```
ESP32 Device (detección cambio de relay)
  ↓ (MQTT SSL 8883 - mqtts://hddm.pqsolutionsperu.com)
Mosquitto Broker (autenticación con /etc/mosquitto/passwd)
  ↓ (Subscribe topic: esp32/{ESP32_ID}/panel_status)
mqtt_client.py (recibe mensaje, valida time_range)
  ↓ (procesa cambio de relay)
firestore_handler.py
  ├─ NFPA Metrics: record_relay_detected()
  ├─ Firestore: guarda evento
  ├─ PostgreSQL: persistencia redundante
  └─ notification_handler.py
      ├─ Rate Limiter: CRITICAL priority (SIN LÍMITE)
      ├─ FCM Retry: send_fcm_with_retry() (3 intentos)
      └─ NFPA Metrics: record_notification_sent()
  ↓ (Push notification)
Android App (recibe notificación <90s - NFPA 72 compliant)
```

#### ESP32 → Logs HTTP

```
ESP32 Device
  ↓ (HTTP POST 8080)
log_server.py
  ↓ (Save to disk)
/home/pqsolutions/esp32_log/{ESP32_ID}/YYYY-MM-DD.log
```

#### Gestión Web Usuarios MQTT

```
Admin Web Browser (navegador)
  ↓ (HTTPS 443)
Nginx Reverse Proxy (hddm.pqsolutionsperu.com)
  ↓ (HTTP 5000)
Flask API (mqtt-manager)
  ├─ Login: POST /api/auth/login
  ├─ Session validation: @login_required decorator
  └─ (Authenticated requests)
      ↓ (Execute)
    mosquitto_passwd CLI (actualiza archivo)
      ↓ (Update)
    /etc/mosquitto/passwd
      ↓ (SIGHUP reload)
    Mosquitto Broker (recarga usuarios sin downtime)
```

#### Sistema de Monitoreo Watchdog

```
External Watchdog (system_watchdog.py)
  ├─ Check systemd status: systemctl is-active vm_monitor_main.service
  ├─ Check Redis heartbeat: GET server:last_heartbeat
  └─ (Si fallo detectado 3 veces consecutivas)
      ├─ Send email alert: SMTP Gmail
      ├─ Restart service: sudo systemctl restart vm_monitor_main.service
      └─ (Si reinicio falla)
          └─ Send CRITICAL email: "Manual intervention required!"

Main Service (main.py)
  └─ Heartbeat thread (daemon)
      └─ Update Redis: SET server:last_heartbeat {timestamp}
          └─ Every 30 seconds
```

---

## 3. SISTEMA DE SEGURIDAD NFPA 72

El sistema HDD-Monitor es **certificable como sistema crítico de seguridad de vidas humanas**, cumpliendo con:

- **NFPA 72**: National Fire Alarm and Signaling Code (EE.UU.)
- **EN 54**: Sistemas de detección y alarma de incendios (Europa)
- **IEC 62443**: Ciberseguridad para sistemas industriales IoT

### 3.1 Rate Limiter con Redis

**Archivo**: `rate_limiter.py`

**Función**: Previene flooding de notificaciones con priorización de eventos críticos.

**Prioridades implementadas**:

```python
class EventPriority(Enum):
    CRITICAL = 0   # Relay events - SIN LÍMITE
    HIGH = 1       # ESP32 offline - 10/min
    MEDIUM = 2     # Connectivity - 2/10min
    LOW = 3        # Heartbeats - 1/30s
```

**Límites configurados**:
```python
self.limits = {
    EventPriority.CRITICAL: (float('inf'), 1),      # Infinito - NUNCA se limita
    EventPriority.HIGH: (10, 60),                   # 10 eventos por 60 segundos
    EventPriority.MEDIUM: (2, 600),                 # 2 eventos por 600 segundos (10 min)
    EventPriority.LOW: (1, 30)                      # 1 evento por 30 segundos
}
```

**Garantía crítica**: Eventos de relay (alarma/fuego) NUNCA se rate-limitan.

**Verificación**:
```bash
# Ver claves de rate limiting activas
redis-cli KEYS "ratelimit:*"

# Ejemplo de salida:
# ratelimit:MEDIUM:ESP32_42A8ACA0_wifi_disconnected
# ratelimit:MEDIUM:ESP32_1694ACA8_internet_lost
```

**Código completo**:

```python
import redis
import time
from enum import Enum
import logging

class EventPriority(Enum):
    CRITICAL = 0   # Relay events - SIN LÍMITE
    HIGH = 1       # ESP32 offline - 10/min
    MEDIUM = 2     # Connectivity - 2/10min
    LOW = 3        # Heartbeats - 1/30s

class RateLimiter:
    def __init__(self, redis_host='localhost', redis_port=6379):
        self.redis = redis.Redis(host=redis_host, port=redis_port, decode_responses=True)

        # (eventos_max, ventana_segundos)
        self.limits = {
            EventPriority.CRITICAL: (float('inf'), 1),
            EventPriority.HIGH: (10, 60),
            EventPriority.MEDIUM: (2, 600),
            EventPriority.LOW: (1, 30)
        }

    def check_and_increment(self, event_key: str, priority: EventPriority) -> bool:
        """Retorna True si puede procesar, False si excede límite."""
        if priority == EventPriority.CRITICAL:
            return True  # Relay events SIEMPRE pasan

        max_count, window_seconds = self.limits[priority]
        key = f"ratelimit:{priority.name}:{event_key}"

        current = self.redis.get(key)

        if current is None:
            self.redis.setex(key, window_seconds, 1)
            return True

        count = int(current)
        if count >= max_count:
            logging.warning(f"Rate limit exceeded: {event_key} ({priority.name})")
            return False

        self.redis.incr(key)
        return True
```

### 3.2 NFPA 72 Metrics Tracking

**Archivo**: `nfpa_metrics.py`

**Función**: Tracking de latencia de notificaciones para cumplimiento NFPA 72.

**Requisito NFPA 72**: Notificaciones de eventos críticos en <90 segundos.

**Métricas registradas**:
- Tiempo de detección de evento relay
- Tiempo de envío de notificación FCM
- Latencia total (debe ser <90,000 ms)
- Flag de compliance: `nfpa72_compliant: true/false`

**Logs estructurados (JSON)**:
```json
{
  "event": "nfpa72_notification_latency",
  "event_id": "panel_1_relay_1_1738000000000",
  "latency_ms": 2345,
  "nfpa72_compliant": true,
  "timestamp": "2026-01-30T15:23:45.678Z"
}
```

**Código completo**:

```python
import time
import structlog
from typing import Dict

logger = structlog.get_logger()

class NFPAMetricsCollector:
    def __init__(self):
        self.relay_event_timestamps = {}

    def record_relay_detected(self, event_id: str):
        self.relay_event_timestamps[event_id] = {
            'detected': time.time() * 1000,
            'notified': None
        }

    def record_notification_sent(self, event_id: str):
        if event_id in self.relay_event_timestamps:
            self.relay_event_timestamps[event_id]['notified'] = time.time() * 1000

            detected = self.relay_event_timestamps[event_id]['detected']
            notified = self.relay_event_timestamps[event_id]['notified']
            latency_ms = notified - detected

            compliant = latency_ms < 90000  # NFPA 72: <90s

            logger.info("nfpa72_notification_latency",
                       event_id=event_id,
                       latency_ms=latency_ms,
                       nfpa72_compliant=compliant)

            if not compliant:
                logger.error("nfpa72_violation",
                            event_id=event_id,
                            exceeded_by_ms=latency_ms - 90000)

            # Limpiar evento antiguo
            del self.relay_event_timestamps[event_id]
```

### 3.3 System Watchdog Externo

**Archivo**: `system_watchdog.py`

**Función**: Monitoreo externo del servicio principal con auto-reinicio.

**Operación**:
- Verifica estado systemd cada 30 segundos
- Verifica heartbeat Redis (timeout 2 minutos)
- Reinicia servicio tras 3 fallos consecutivos
- Envía alertas por email en caso de fallo

**Código completo**:

```python
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
```

### 3.4 Redis Heartbeat (main.py)

**Función**: Thread daemon que actualiza `server:last_heartbeat` cada 30s.

**Propósito**: Permite al watchdog detectar procesos colgados (systemd activo pero código frozen).

**Código (fragmento de main.py)**:

```python
import redis
import threading
import time

def heartbeat_thread():
    """Actualiza heartbeat en Redis cada 30s."""
    r = redis.Redis(host='localhost', decode_responses=True)
    while True:
        try:
            r.set('server:last_heartbeat', time.time())
            time.sleep(30)
        except Exception as e:
            logging.error(f"Heartbeat update failed: {e}")
            time.sleep(5)

if __name__ == '__main__':
    # Iniciar heartbeat thread
    hb_thread = threading.Thread(target=heartbeat_thread, daemon=True)
    hb_thread.start()

    # ... resto del código
```

**Verificación**:
```bash
redis-cli GET server:last_heartbeat
# Debe retornar timestamp reciente (Unix time en segundos)
# Ejemplo: 1738257845.3456
```

### 3.5 PostgreSQL Redundancia

**Archivos**: `setup_postgresql.sql`, `firestore_handler.py`

**Función**: Almacenamiento redundante de eventos críticos.

**Características**:
- Pool de conexiones (1-10 conexiones)
- Firestore es primario, PostgreSQL es backup
- Fallo de PostgreSQL no detiene el sistema

**Tablas**:

```sql
-- relay_events: Cambios de estado de relays
CREATE TABLE relay_events (
    id SERIAL PRIMARY KEY,
    client_id VARCHAR(50) NOT NULL,
    panel_id VARCHAR(50) NOT NULL,
    relay_id VARCHAR(20) NOT NULL,
    old_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    timestamp TIMESTAMP DEFAULT NOW(),
    source VARCHAR(20) DEFAULT 'mqtt',
    firestore_synced BOOLEAN DEFAULT TRUE
);

-- connectivity_events: Eventos de conectividad ESP32
CREATE TABLE connectivity_events (
    id SERIAL PRIMARY KEY,
    esp32_id VARCHAR(20) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    time_range VARCHAR(50),
    timestamp TIMESTAMP DEFAULT NOW()
);
```

**Código en firestore_handler.py**:

```python
from psycopg2.pool import SimpleConnectionPool
import psycopg2

class FirestoreHandler:
    def __init__(self):
        # Pool PostgreSQL
        try:
            self.pg_pool = SimpleConnectionPool(
                1, 10,
                host=config.PG_CONFIG['host'],
                database=config.PG_CONFIG['database'],
                user=config.PG_CONFIG['user'],
                password=config.PG_CONFIG['password']
            )
            logging.info("PostgreSQL connection pool initialized")
        except Exception as e:
            logging.error(f"Failed to initialize PostgreSQL pool: {e}")
            self.pg_pool = None

    # Al guardar evento de relay:
    def handle_panel_status_update(self, ...):
        # Guardar en Firestore (primario)
        # ...

        # NUEVO: Persistencia redundante en PostgreSQL
        if self.pg_pool:
            try:
                conn = self.pg_pool.getconn()
                cursor = conn.cursor()
                cursor.execute("""
                    INSERT INTO relay_events
                    (client_id, panel_id, relay_id, old_status, new_status, source)
                    VALUES (%s, %s, %s, %s, %s, %s)
                """, (client_id, panel_id, relay_name, old_status, new_status, 'mqtt'))
                conn.commit()
                cursor.close()
                self.pg_pool.putconn(conn)
                logging.info(f"Relay event persisted to PostgreSQL: {client_id}/{relay_name}")
            except Exception as pg_err:
                logging.error(f"PostgreSQL persist failed: {pg_err}")
```

**Verificación**:
```bash
psql -U hdd_monitor_user -d hdd_monitor
SELECT * FROM relay_events ORDER BY timestamp DESC LIMIT 10;
```

### 3.6 FCM Retry Logic

**Archivo**: `notification_handler.py`

**Función**: Reintentos exponenciales para notificaciones FCM.

**Configuración**:
- 3 intentos máximo
- Delays: 1s, 2s, 4s (exponential backoff)
- No reintenta en `UnregisteredError` (token inválido)

**Código**:

```python
from firebase_admin import messaging
import time

class NotificationHandler:
    def send_fcm_with_retry(self, message: messaging.Message, max_retries: int = 3) -> Optional[str]:
        """Envía mensaje FCM con reintentos exponenciales."""
        for attempt in range(1, max_retries + 1):
            try:
                response = messaging.send(message)
                logging.info(f"FCM sent successfully on attempt {attempt}")
                return response
            except messaging.UnregisteredError:
                logging.error("FCM token invalid (unregistered)")
                return None  # No reintentar si el token es inválido
            except Exception as e:
                if attempt < max_retries:
                    delay = 2 ** (attempt - 1)  # 1s, 2s, 4s
                    logging.warning(f"FCM failed (attempt {attempt}/{max_retries}), retry in {delay}s: {e}")
                    time.sleep(delay)
                else:
                    logging.error(f"FCM failed after {max_retries} attempts: {e}")
                    return None
        return None
```

**Uso en notification_handler.py**:
```python
# Enviar notificación con reintentos
response = self.send_fcm_with_retry(message, max_retries=3)
if response:
    # Registro exitoso en NFPA metrics
    self.firestore_handler.nfpa_metrics.record_notification_sent(event_id)
```

### 3.7 Validaciones ESP32 y Servidor

#### Validación NTP Sync (ESP32)

**Archivo**: `connectivity_monitor.c`

```c
// Bloquea eventos hasta sincronización NTP
if (!time_manager_is_synchronized()) {
    ESP_LOGW(TAG, "NTP not synced - rejecting connectivity event");
    return;
}

// Validación de duración mínima
int duration = (int)difftime(end_time, start_time);
if (duration < 5) {
    ESP_LOGW(TAG, "Event duration too short: %d seconds", duration);
    return;
}
```

#### Validación Boot Loop (ESP32)

**Archivo**: `config_manager.c`

```c
// Detecta loops infinitos de reinicio
void config_manager_check_boot_loop(void) {
    int boot_count = 0;
    time_t last_boot = 0;

    nvs_get_i32(nvs_handle, "boot_count", &boot_count);
    nvs_get_i64(nvs_handle, "last_boot", &last_boot);

    time_t now = time(NULL);

    if ((now - last_boot) < 300) {  // 5 minutos
        boot_count++;
        if (boot_count >= 5) {
            ESP_LOGE(TAG, "BOOT LOOP DETECTED - ENTERING SAFE MODE");
            enter_safe_mode();
        }
    } else {
        boot_count = 0;
    }

    nvs_set_i32(nvs_handle, "boot_count", boot_count);
    nvs_set_i64(nvs_handle, "last_boot", now);
}
```

#### Validación Time Range (Servidor Python)

**Archivo**: `mqtt_client.py`

```python
import re

def validate_time_range(time_range: str) -> bool:
    """
    Valida formato 'HH:MM a HH:MM' y rechaza duraciones cero.

    Returns:
        True si válido, False si inválido
    """
    pattern = r'^(\d{2}):(\d{2}) a (\d{2}):(\d{2})$'
    match = re.match(pattern, time_range)

    if not match:
        logging.warning(f"time_range invalid format: '{time_range}'")
        return False

    h1, m1, h2, m2 = map(int, match.groups())

    # Validar rangos
    if not (0 <= h1 <= 23 and 0 <= m1 <= 59 and 0 <= h2 <= 23 and 0 <= m2 <= 59):
        logging.warning(f"time_range out of bounds: '{time_range}'")
        return False

    # Rechazar duración cero
    if h1 == h2 and m1 == m2:
        logging.warning(f"time_range zero duration: '{time_range}'")
        return False

    return True

# Uso en connectivity event handler:
if time_range and not validate_time_range(time_range):
    logging.error(f"Rejecting connectivity event - invalid time_range: {time_range}")
    return  # No procesar evento inválido
```

### 3.8 Structured Logging

**Archivo**: `config.py`

```python
import structlog

# Configurar logging estructurado (JSON)
structlog.configure(
    processors=[
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.add_log_level,
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
        structlog.processors.JSONRenderer()
    ]
)
```

**Ejemplo de log JSON**:
```json
{
  "event": "nfpa72_notification_latency",
  "event_id": "client1_panel1_relay1_1738257845000",
  "latency_ms": 2345,
  "nfpa72_compliant": true,
  "timestamp": "2026-01-30T15:23:45.678Z",
  "log_level": "info"
}
```

---

## 4. DEPLOYMENT COMPLETO PASO A PASO

### 4.1 Acceso a la VM

**Opción 1: SSH Browser de Google Cloud** (Recomendado)

```
https://ssh.cloud.google.com/v2/ssh/projects/fir-hdd-monitor-d00de/zones/us-central1-c/instances/instanciavm-myqtthub
```

**Opción 2: SSH tradicional**

```bash
ssh pqsolutionsperu@34.63.146.196
```

### 4.2 Subir Archivos a la VM

#### Método 1: Botón "Upload file" en SSH Browser

1. En la terminal SSH del navegador, busca:
   - Ícono de **engranaje** (⚙️) o
   - Ícono de **tres puntos** (⋮) o
   - Botón **"⬆ Upload"**

2. Sube estos archivos desde tu PC:
   ```
   Ubicación local: E:\PQSolutions\HDD-Monitor\ESP32-IDF\ESP32-IDF\APP\HDD1_2\VM GOOGLE CLOUD\

   Archivos nuevos:
   ├── requirements.txt
   ├── rate_limiter.py
   ├── system_watchdog.py
   ├── nfpa_metrics.py
   ├── setup_postgresql.sql
   ├── hdd-monitor-watchdog.service
   └── verify_installation.sh

   Archivos modificados:
   ├── config.py
   ├── mqtt_client.py
   ├── notification_handler.py
   ├── firestore_handler.py
   └── main.py
   ```

3. Verificar archivos subidos:
   ```bash
   ls -lh ~/ | grep -E "\.py$|\.txt$|\.sql$|\.service$|\.sh$"
   ```

4. Mover a directorio temporal:
   ```bash
   mkdir -p ~/hdd-monitor-update
   mv ~/*.py ~/*.txt ~/*.sql ~/*.service ~/*.sh ~/hdd-monitor-update/ 2>/dev/null
   ls -lh ~/hdd-monitor-update/
   ```

#### Método 2: SCP desde tu máquina local (WSL)

```bash
# Subir todos los archivos de una vez
scp -r "/mnt/e/PQSolutions/HDD-Monitor/ESP32-IDF/ESP32-IDF/APP/HDD1_2/VM GOOGLE CLOUD/"* \
  pqsolutionsperu@34.63.146.196:~/hdd-monitor-update/
```

### 4.3 Instalación Completa

**IMPORTANTE**: Ejecutar estos comandos en secuencia en la VM.

```bash
# =========================================
# PASO 1: Backup del proyecto actual
# =========================================
sudo cp -r /home/pqsolutions/hdd-monitor /home/pqsolutions/hdd-monitor-backup-$(date +%Y%m%d-%H%M)

# =========================================
# PASO 2: Copiar archivos al proyecto
# =========================================
cd ~/hdd-monitor-update
sudo cp *.py /home/pqsolutions/hdd-monitor/
sudo cp *.txt /home/pqsolutions/hdd-monitor/
sudo cp *.sql /home/pqsolutions/hdd-monitor/
sudo cp *.sh /home/pqsolutions/hdd-monitor/
sudo chown -R pqsolutions:pqsolutions /home/pqsolutions/hdd-monitor/

# Verificar archivos copiados
ls -lh /home/pqsolutions/hdd-monitor/ | grep -E "(rate_limiter|nfpa_metrics|system_watchdog)"

# =========================================
# PASO 3: Instalar dependencias Python
# =========================================
cd /home/pqsolutions/hdd-monitor
source /home/pqsolutions/venv/bin/activate
pip install -r requirements.txt

# Verificar instalación
python3 -c "import structlog, redis, psycopg2; print('✓ Dependencies OK')"

# =========================================
# PASO 4: Instalar Redis
# =========================================
sudo apt update
sudo apt install -y redis-server
sudo systemctl enable redis-server
sudo systemctl start redis-server

# Verificar Redis
redis-cli ping
# Output esperado: PONG

# =========================================
# PASO 5: Instalar PostgreSQL
# =========================================
sudo apt install -y postgresql postgresql-contrib

# Crear base de datos y tablas
cd /home/pqsolutions/hdd-monitor
sudo -u postgres psql < setup_postgresql.sql

# Configurar password (USAR LA CONTRASEÑA REAL)
sudo -u postgres psql -c "ALTER USER hdd_monitor_user PASSWORD 'nJ71pNOGAd\$AKPtzlen9pGWg*qeYMW4z';"

# Configurar variable de entorno
echo "export PG_PASSWORD='nJ71pNOGAd\$AKPtzlen9pGWg*qeYMW4z'" >> ~/.bashrc
source ~/.bashrc

# Verificar PostgreSQL
psql -U hdd_monitor_user -d hdd_monitor -c "SELECT version();"
# Si pide password, usar: nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z

# Verificar tablas
psql -U hdd_monitor_user -d hdd_monitor -c "\dt"
# Output esperado: relay_events, connectivity_events

# =========================================
# PASO 6: Instalar Watchdog Service
# =========================================
sudo cp /home/pqsolutions/hdd-monitor/system_watchdog.py /home/pqsolutionsperu/
sudo chmod +x /home/pqsolutionsperu/system_watchdog.py

sudo cp /home/pqsolutions/hdd-monitor/hdd-monitor-watchdog.service /etc/systemd/system/

# OPCIONAL: Configurar email alerts
# sudo nano /etc/systemd/system/hdd-monitor-watchdog.service
# Actualizar Environment="SMTP_USER=tu_email@gmail.com"
# Actualizar Environment="SMTP_PASS=tu_app_password"

# Habilitar y arrancar watchdog
sudo systemctl daemon-reload
sudo systemctl enable hdd-monitor-watchdog
sudo systemctl start hdd-monitor-watchdog

# Verificar estado
sudo systemctl status hdd-monitor-watchdog

# =========================================
# PASO 7: Identificar y reiniciar servicio principal
# =========================================

# Listar servicios relacionados
sudo systemctl list-units --type=service --state=running | grep -iE "hdd|monitor|python|logserver"

# Identificar cuál ejecuta main.py
sudo grep -r "main.py" /etc/systemd/system/ 2>/dev/null

# Ejemplo: si el servicio es logserver.service
MAIN_SERVICE="logserver.service"

# Reiniciar servicio principal
sudo systemctl restart $MAIN_SERVICE

# Verificar estado
sudo systemctl status $MAIN_SERVICE

# Ver logs en tiempo real (Ctrl+C para salir)
sudo journalctl -u $MAIN_SERVICE -f -n 50

# =========================================
# PASO 8: Verificaciones finales
# =========================================

# Verificar Redis heartbeat (debe actualizarse cada 30s)
redis-cli GET server:last_heartbeat
# Esperar 30 segundos y verificar de nuevo
sleep 30
redis-cli GET server:last_heartbeat
# El timestamp debe cambiar

# Verificar PostgreSQL vacío (o con eventos existentes)
psql -U hdd_monitor_user -d hdd_monitor -c "SELECT COUNT(*) FROM relay_events;"

# Verificar watchdog activo
sudo systemctl status hdd-monitor-watchdog

# Verificar rate limiting (inicialmente vacío)
redis-cli KEYS "ratelimit:*"

# =========================================
# PASO 9: Script de verificación automática
# =========================================
cd /home/pqsolutions/hdd-monitor
chmod +x verify_installation.sh
./verify_installation.sh

# Output esperado:
# ✓ SISTEMA COMPLETAMENTE INSTALADO
```

### 4.4 Resolución de Problemas Comunes

#### Redis no responde

```bash
sudo systemctl status redis-server
sudo systemctl restart redis-server
redis-cli ping
```

#### PostgreSQL no conecta

```bash
# Verificar servicio
sudo systemctl status postgresql

# Verificar usuario
sudo -u postgres psql -c "\du" | grep hdd_monitor_user

# Verificar base de datos
sudo -u postgres psql -c "\l" | grep hdd_monitor

# Reintentar creación
sudo -u postgres psql < /home/pqsolutions/hdd-monitor/setup_postgresql.sql
```

#### Servicio principal no inicia

```bash
# Ver logs detallados
sudo journalctl -u $MAIN_SERVICE -n 100 --no-pager

# Verificar imports Python
cd /home/pqsolutions/hdd-monitor
source /home/pqsolutions/venv/bin/activate
python3 -c "import rate_limiter, nfpa_metrics; print('OK')"

# Si falla, reinstalar dependencias
pip install -r requirements.txt --force-reinstall
```

#### Watchdog no funciona

```bash
# Ver logs detallados
sudo journalctl -u hdd-monitor-watchdog -xe

# Verificar permisos
ls -l /home/pqsolutionsperu/system_watchdog.py

# Verificar sintaxis Python
python3 /home/pqsolutionsperu/system_watchdog.py
# Debe correr sin errores (Ctrl+C para detener)
```

---

## 5. CONFIGURACIÓN DE SERVICIOS

### 5.1 hdd-monitor-watchdog.service

**Ubicación**: `/etc/systemd/system/hdd-monitor-watchdog.service`

**Contenido completo**:

```ini
[Unit]
Description=HDD-Monitor System Watchdog
After=network.target

[Service]
Type=simple
User=pqsolutionsperu
ExecStart=/usr/bin/python3 /home/pqsolutionsperu/system_watchdog.py
Restart=always
RestartSec=10
Environment="SMTP_USER=alerts@pqsolutions.com"
Environment="SMTP_PASS=nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z"

[Install]
WantedBy=multi-user.target
```

**Comandos de gestión**:

```bash
# Instalar servicio
sudo cp hdd-monitor-watchdog.service /etc/systemd/system/
sudo systemctl daemon-reload

# Habilitar auto-inicio
sudo systemctl enable hdd-monitor-watchdog

# Iniciar servicio
sudo systemctl start hdd-monitor-watchdog

# Ver estado
sudo systemctl status hdd-monitor-watchdog

# Ver logs en tiempo real
sudo journalctl -u hdd-monitor-watchdog -f

# Reiniciar
sudo systemctl restart hdd-monitor-watchdog

# Detener
sudo systemctl stop hdd-monitor-watchdog
```

### 5.2 mqtt-manager.service (MQTT Manager Web UI)

**Ubicación**: `/etc/systemd/system/mqtt-manager.service`

**Contenido completo**:

```ini
[Unit]
Description=MQTT Manager Web Interface
After=network.target mosquitto.service nginx.service

[Service]
Type=exec
User=pqsolutionsperu
WorkingDirectory=/home/pqsolutions/mqtt-manager
Environment="PATH=/home/pqsolutions/mqtt-manager-venv/bin"
Environment="FLASK_SECRET_KEY=0b9e46369d7de802127fd58ad3ffc0f5bf27bdf70c22bb7f"
Environment="ADMIN_PASSWORD_HASH=scrypt:32768:8:1$oKcrWOh9tbp3UlWR$619f70528fe20bbbfbb859d677e38cb851ab013a24a56bddcce7dc2ff139969487ddc6249758f409b5a497d5174a8ed726582c2c10853642c861411e0652f641"
ExecStart=/home/pqsolutions/mqtt-manager-venv/bin/gunicorn --bind 127.0.0.1:5000 --workers 2 app:app
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

**Comandos de gestión**:

```bash
# Ver estado
sudo systemctl status mqtt-manager

# Reiniciar (después de cambios)
sudo systemctl restart mqtt-manager

# Ver logs
sudo journalctl -u mqtt-manager -f -n 50
```

### 5.3 logserver.service (ESP32 Log Server)

**Ubicación**: `/etc/systemd/system/logserver.service`

```bash
# Ver estado
sudo systemctl status logserver

# Reiniciar
sudo systemctl restart logserver

# Ver logs
sudo journalctl -u logserver -f
```

### 5.4 Variables de Entorno del Sistema

**Archivo**: `~/.bashrc` (usuario `pqsolutionsperu`)

```bash
# Agregar al final de ~/.bashrc:
export PG_PASSWORD='nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z'
```

**Recargar variables**:

```bash
source ~/.bashrc
echo $PG_PASSWORD  # Verificar que esté configurada
```

---

## 6. CÓDIGO FUENTE PYTHON COMPLETO

### 6.1 config.py (Configuración Central)

**Ubicación**: `/home/pqsolutions/hdd-monitor/config.py`

```python
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
```

### 6.2 rate_limiter.py (Ver sección 3.1)

### 6.3 nfpa_metrics.py (Ver sección 3.2)

### 6.4 system_watchdog.py (Ver sección 3.3)

### 6.5 Fragmentos Clave de Otros Archivos

#### main.py (Heartbeat)

```python
import redis
import threading
import time

def heartbeat_thread():
    """Actualiza heartbeat en Redis cada 30s."""
    r = redis.Redis(host='localhost', decode_responses=True)
    while True:
        try:
            r.set('server:last_heartbeat', time.time())
            time.sleep(30)
        except Exception as e:
            logging.error(f"Heartbeat update failed: {e}")
            time.sleep(5)

if __name__ == '__main__':
    # Iniciar heartbeat thread
    hb_thread = threading.Thread(target=heartbeat_thread, daemon=True)
    hb_thread.start()

    # ... resto del código
```

#### mqtt_client.py (Validación time_range)

```python
import re

def validate_time_range(time_range: str) -> bool:
    """Valida formato 'HH:MM a HH:MM' y rechaza duraciones cero."""
    pattern = r'^(\d{2}):(\d{2}) a (\d{2}):(\d{2})$'
    match = re.match(pattern, time_range)

    if not match:
        logging.warning(f"time_range invalid format: '{time_range}'")
        return False

    h1, m1, h2, m2 = map(int, match.groups())

    if not (0 <= h1 <= 23 and 0 <= m1 <= 59 and 0 <= h2 <= 23 and 0 <= m2 <= 59):
        logging.warning(f"time_range out of bounds: '{time_range}'")
        return False

    if h1 == h2 and m1 == m2:
        logging.warning(f"time_range zero duration: '{time_range}'")
        return False

    return True

# En connectivity event handler (línea ~199):
if time_range and not validate_time_range(time_range):
    logging.error(f"Rejecting connectivity event - invalid time_range: {time_range}")
    return
```

#### notification_handler.py (Rate Limiting + FCM Retry)

```python
from rate_limiter import RateLimiter, EventPriority
from firebase_admin import messaging
import time

class NotificationHandler:
    def __init__(self, firestore_handler):
        self.firestore_handler = firestore_handler
        self.rate_limiter = RateLimiter()  # NUEVO

    def send_wifi_disconnection_notification(self, ...):
        # Rate limiting (MEDIUM priority)
        event_key = f"ESP32_{esp32_id}_wifi_disconnected"
        if not self.rate_limiter.check_and_increment(event_key, EventPriority.MEDIUM):
            logging.info(f"Rate limited: {event_key}")
            return

        # ... resto del código de notificación

    def send_fcm_with_retry(self, message: messaging.Message, max_retries: int = 3) -> Optional[str]:
        """Envía mensaje FCM con reintentos exponenciales."""
        for attempt in range(1, max_retries + 1):
            try:
                response = messaging.send(message)
                logging.info(f"FCM sent successfully on attempt {attempt}")
                return response
            except messaging.UnregisteredError:
                logging.error("FCM token invalid (unregistered)")
                return None
            except Exception as e:
                if attempt < max_retries:
                    delay = 2 ** (attempt - 1)
                    logging.warning(f"FCM failed (attempt {attempt}/{max_retries}), retry in {delay}s: {e}")
                    time.sleep(delay)
                else:
                    logging.error(f"FCM failed after {max_retries} attempts: {e}")
                    return None
        return None

    # Uso en envío de notificaciones:
    def send_relay_notification(self, ...):
        # ... construir mensaje FCM
        response = self.send_fcm_with_retry(message, max_retries=3)
        if response:
            self.firestore_handler.nfpa_metrics.record_notification_sent(event_id)
```

#### firestore_handler.py (PostgreSQL + NFPA Metrics)

```python
from psycopg2.pool import SimpleConnectionPool
import psycopg2
from nfpa_metrics import NFPAMetricsCollector

class FirestoreHandler:
    def __init__(self):
        # ... código existente ...

        # Pool PostgreSQL
        try:
            self.pg_pool = SimpleConnectionPool(
                1, 10,
                host=config.PG_CONFIG['host'],
                database=config.PG_CONFIG['database'],
                user=config.PG_CONFIG['user'],
                password=config.PG_CONFIG['password']
            )
            logging.info("PostgreSQL connection pool initialized")
        except Exception as e:
            logging.error(f"Failed to initialize PostgreSQL pool: {e}")
            self.pg_pool = None

        # NFPA Metrics
        self.nfpa_metrics = NFPAMetricsCollector()

    def handle_panel_status_update(self, client_id, message):
        # ... código de procesamiento ...

        # Registrar detección de evento (NFPA)
        event_id = f"{client_id}_{panel_id}_{relay_name}_{timestamp_ms}"
        self.nfpa_metrics.record_relay_detected(event_id)

        # Guardar en Firestore (primario)
        # ... código de Firestore ...

        # Persistencia redundante en PostgreSQL
        if self.pg_pool:
            try:
                conn = self.pg_pool.getconn()
                cursor = conn.cursor()
                cursor.execute("""
                    INSERT INTO relay_events
                    (client_id, panel_id, relay_id, old_status, new_status, source)
                    VALUES (%s, %s, %s, %s, %s, %s)
                """, (client_id, panel_id, relay_name, old_status, new_status, 'mqtt'))
                conn.commit()
                cursor.close()
                self.pg_pool.putconn(conn)
                logging.info(f"Relay event persisted to PostgreSQL")
            except Exception as pg_err:
                logging.error(f"PostgreSQL persist failed: {pg_err}")

        # Enviar notificación
        # ... código de notificación ...

        # Registrar envío de notificación (NFPA)
        self.nfpa_metrics.record_notification_sent(event_id)
```

---

## 7. BASE DE DATOS POSTGRESQL

### 7.1 Schema Completo

**Archivo**: `/home/pqsolutions/hdd-monitor/setup_postgresql.sql`

```sql
-- HDD-Monitor PostgreSQL Redundancy Setup
-- Run as postgres user: sudo -u postgres psql < setup_postgresql.sql
-- ⚠️ SECURITY: Change password after first deployment

CREATE DATABASE hdd_monitor;
CREATE USER hdd_monitor_user WITH PASSWORD 'nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z';
GRANT ALL PRIVILEGES ON DATABASE hdd_monitor TO hdd_monitor_user;

\c hdd_monitor

CREATE TABLE relay_events (
    id SERIAL PRIMARY KEY,
    client_id VARCHAR(50) NOT NULL,
    panel_id VARCHAR(50) NOT NULL,
    relay_id VARCHAR(20) NOT NULL,
    old_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    timestamp TIMESTAMP DEFAULT NOW(),
    source VARCHAR(20) DEFAULT 'mqtt',
    firestore_synced BOOLEAN DEFAULT TRUE
);

CREATE INDEX idx_relay_events_timestamp ON relay_events(timestamp DESC);
CREATE INDEX idx_relay_events_panel ON relay_events(client_id, panel_id);

CREATE TABLE connectivity_events (
    id SERIAL PRIMARY KEY,
    esp32_id VARCHAR(20) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    time_range VARCHAR(50),
    timestamp TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_connectivity_events_timestamp ON connectivity_events(timestamp DESC);
CREATE INDEX idx_connectivity_events_esp32 ON connectivity_events(esp32_id);

-- Grant permissions
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO hdd_monitor_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO hdd_monitor_user;
```

### 7.2 Instalación y Configuración

```bash
# Instalar PostgreSQL
sudo apt update
sudo apt install -y postgresql postgresql-contrib

# Ejecutar script de setup
cd /home/pqsolutions/hdd-monitor
sudo -u postgres psql < setup_postgresql.sql

# Configurar password real (cambiar por el tuyo)
sudo -u postgres psql -c "ALTER USER hdd_monitor_user PASSWORD 'nJ71pNOGAd\$AKPtzlen9pGWg*qeYMW4z';"

# Verificar que se creó correctamente
sudo -u postgres psql -l | grep hdd_monitor
sudo -u postgres psql -c "\du" | grep hdd_monitor_user
```

### 7.3 Queries Útiles

#### Verificar eventos recientes

```sql
-- Conectar a la base de datos
psql -U hdd_monitor_user -d hdd_monitor

-- Ver últimos 10 eventos de relay
SELECT
    id,
    client_id,
    panel_id,
    relay_id,
    old_status,
    new_status,
    timestamp
FROM relay_events
ORDER BY timestamp DESC
LIMIT 10;

-- Contar eventos por cliente
SELECT
    client_id,
    COUNT(*) as event_count
FROM relay_events
WHERE timestamp > NOW() - INTERVAL '24 hours'
GROUP BY client_id
ORDER BY event_count DESC;

-- Ver eventos de conectividad
SELECT
    esp32_id,
    event_type,
    time_range,
    timestamp
FROM connectivity_events
ORDER BY timestamp DESC
LIMIT 10;
```

#### Estadísticas del sistema

```sql
-- Total de eventos registrados
SELECT
    COUNT(*) as total_events,
    MIN(timestamp) as first_event,
    MAX(timestamp) as last_event
FROM relay_events;

-- Eventos por hora (últimas 24 horas)
SELECT
    DATE_TRUNC('hour', timestamp) as hour,
    COUNT(*) as events
FROM relay_events
WHERE timestamp > NOW() - INTERVAL '24 hours'
GROUP BY hour
ORDER BY hour DESC;

-- Relays más activos
SELECT
    client_id,
    panel_id,
    relay_id,
    COUNT(*) as changes
FROM relay_events
WHERE timestamp > NOW() - INTERVAL '7 days'
GROUP BY client_id, panel_id, relay_id
ORDER BY changes DESC
LIMIT 10;
```

### 7.4 Mantenimiento

#### Backup de base de datos

```bash
# Backup completo
sudo -u postgres pg_dump hdd_monitor > hdd_monitor_backup_$(date +%Y%m%d).sql

# Backup comprimido
sudo -u postgres pg_dump hdd_monitor | gzip > hdd_monitor_backup_$(date +%Y%m%d).sql.gz

# Backup solo estructura (sin datos)
sudo -u postgres pg_dump -s hdd_monitor > hdd_monitor_schema.sql
```

#### Restore de backup

```bash
# Restaurar desde backup
sudo -u postgres psql hdd_monitor < hdd_monitor_backup_20260130.sql

# Restaurar desde backup comprimido
gunzip -c hdd_monitor_backup_20260130.sql.gz | sudo -u postgres psql hdd_monitor
```

#### Limpieza de eventos antiguos

```sql
-- Eliminar eventos de relay más antiguos de 90 días
DELETE FROM relay_events
WHERE timestamp < NOW() - INTERVAL '90 days';

-- Eliminar eventos de conectividad más antiguos de 30 días
DELETE FROM connectivity_events
WHERE timestamp < NOW() - INTERVAL '30 days';

-- Optimizar tablas después de limpieza
VACUUM ANALYZE relay_events;
VACUUM ANALYZE connectivity_events;
```

#### Verificar tamaño de base de datos

```sql
-- Tamaño total de la base de datos
SELECT pg_size_pretty(pg_database_size('hdd_monitor'));

-- Tamaño de cada tabla
SELECT
    schemaname,
    tablename,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
```

### 7.5 Troubleshooting PostgreSQL

#### No puedo conectar

```bash
# Verificar servicio
sudo systemctl status postgresql

# Ver logs
sudo journalctl -u postgresql -n 50

# Reiniciar servicio
sudo systemctl restart postgresql
```

#### Error "password authentication failed"

```bash
# Resetear password
sudo -u postgres psql -c "ALTER USER hdd_monitor_user PASSWORD 'nJ71pNOGAd\$AKPtzlen9pGWg*qeYMW4z';"

# Verificar variable de entorno
echo $PG_PASSWORD

# Si no está configurada:
echo "export PG_PASSWORD='nJ71pNOGAd\$AKPtzlen9pGWg*qeYMW4z'" >> ~/.bashrc
source ~/.bashrc
```

#### Conexión rechazada

```bash
# Verificar que PostgreSQL esté escuchando
sudo netstat -tulpn | grep 5432

# Verificar configuración de acceso
sudo cat /etc/postgresql/*/main/pg_hba.conf | grep -v "^#"

# Reiniciar PostgreSQL
sudo systemctl restart postgresql
```

---

## 8. MONITOREO Y TROUBLESHOOTING

### 8.1 Comandos de Verificación Diaria

#### Estado general del sistema

```bash
# Ver todos los servicios principales
sudo systemctl status redis-server postgresql mosquitto nginx mqtt-manager hdd-monitor-watchdog logserver

# Vista compacta
systemctl is-active redis-server postgresql mosquitto nginx mqtt-manager hdd-monitor-watchdog logserver
```

#### Redis Heartbeat

```bash
# Ver heartbeat actual
redis-cli GET server:last_heartbeat

# Convertir timestamp a fecha legible
date -d @$(redis-cli GET server:last_heartbeat)

# Monitorear heartbeat en tiempo real (actualiza cada 5s)
watch -n 5 'echo "Heartbeat:"; redis-cli GET server:last_heartbeat; echo ""; date -d @$(redis-cli GET server:last_heartbeat)'
```

#### PostgreSQL - Eventos recientes

```bash
# Últimos 10 eventos de relay
psql -U hdd_monitor_user -d hdd_monitor -c \
  "SELECT id, client_id, relay_id, old_status, new_status, timestamp FROM relay_events ORDER BY timestamp DESC LIMIT 10;"

# Contar eventos por hora
psql -U hdd_monitor_user -d hdd_monitor -c \
  "SELECT DATE_TRUNC('hour', timestamp) as hour, COUNT(*) FROM relay_events WHERE timestamp > NOW() - INTERVAL '24 hours' GROUP BY hour ORDER BY hour DESC;"
```

#### Rate Limiting Activo

```bash
# Ver claves de rate limiting
redis-cli KEYS "ratelimit:*"

# Contar claves activas
redis-cli KEYS "ratelimit:*" | wc -l

# Ver TTL (tiempo restante) de una clave
redis-cli TTL "ratelimit:MEDIUM:ESP32_42A8ACA0_wifi_disconnected"
```

#### Logs NFPA 72

```bash
# Ver eventos de compliance (últimas 24 horas)
sudo journalctl -u logserver --since "24 hours ago" | grep "nfpa72_compliant"

# Ver violaciones de NFPA 72
sudo journalctl -u logserver | grep "nfpa72_violation"

# Estadísticas de latencia
sudo journalctl -u logserver --since "24 hours ago" | grep "latency_ms" | tail -20
```

### 8.2 Monitoreo Continuo

#### Watchdog Status

```bash
# Estado del watchdog
sudo systemctl status hdd-monitor-watchdog

# Logs del watchdog (últimos 50 mensajes)
sudo journalctl -u hdd-monitor-watchdog -n 50

# Logs en tiempo real
sudo journalctl -u hdd-monitor-watchdog -f
```

#### MQTT Broker

```bash
# Ver logs de Mosquitto
sudo tail -f /var/log/mosquitto/mosquitto.log

# Ver conexiones activas
sudo netstat -tulpn | grep 8883

# Ver usuarios MQTT
sudo cat /etc/mosquitto/passwd
```

#### Servicio Principal

```bash
# Logs del servicio (identificar nombre correcto primero)
MAIN_SERVICE="logserver.service"  # O el que corresponda
sudo journalctl -u $MAIN_SERVICE -f -n 50

# Buscar errores en logs
sudo journalctl -u $MAIN_SERVICE --since "1 hour ago" | grep -i error

# Buscar warnings
sudo journalctl -u $MAIN_SERVICE --since "1 hour ago" | grep -i warning
```

### 8.3 Diagnóstico de Problemas

#### Problema: Notificaciones no llegan

```bash
# 1. Verificar heartbeat
redis-cli GET server:last_heartbeat
# Si es nulo o muy antiguo → servicio caído

# 2. Verificar servicio principal
sudo systemctl status logserver

# 3. Ver logs del servicio
sudo journalctl -u logserver -n 100

# 4. Verificar FCM (buscar errores)
sudo journalctl -u logserver | grep -i fcm | tail -20

# 5. Verificar rate limiting (puede estar bloqueando)
redis-cli KEYS "ratelimit:*"
```

#### Problema: Redis caído

```bash
# Verificar estado
sudo systemctl status redis-server

# Reiniciar
sudo systemctl restart redis-server

# Verificar conectividad
redis-cli ping
# Debe responder: PONG

# Ver logs de Redis
sudo journalctl -u redis-server -n 50
```

#### Problema: PostgreSQL no guarda eventos

```bash
# Verificar servicio
sudo systemctl status postgresql

# Verificar conexión
psql -U hdd_monitor_user -d hdd_monitor -c "SELECT 1;"

# Ver logs de PostgreSQL
sudo journalctl -u postgresql -n 50

# Ver logs del servicio principal (errores PostgreSQL)
sudo journalctl -u logserver | grep -i "postgres\|pg_pool" | tail -20
```

#### Problema: Watchdog reinicia constantemente

```bash
# Ver por qué reinicia
sudo journalctl -u hdd-monitor-watchdog -n 100

# Ver estado del servicio principal
sudo systemctl status logserver

# Ver si hay errores que causan crashes
sudo journalctl -u logserver -n 100 | grep -i "error\|exception\|critical"
```

### 8.4 Testing End-to-End

#### Test 1: Cambio de Relay (NFPA 72)

```bash
# 1. Cambiar físicamente un relay
# 2. Verificar que llegue a MQTT
sudo tail -f /var/log/mosquitto/mosquitto.log | grep panel_status

# 3. Verificar procesamiento en servidor
sudo journalctl -u logserver -f | grep relay

# 4. Verificar guardado en PostgreSQL
psql -U hdd_monitor_user -d hdd_monitor -c \
  "SELECT * FROM relay_events ORDER BY timestamp DESC LIMIT 1;"

# 5. Verificar NFPA metrics
sudo journalctl -u logserver | grep "nfpa72_notification_latency" | tail -1
# Debe mostrar latency_ms y nfpa72_compliant: true
```

#### Test 2: Rate Limiting

```bash
# 1. Generar múltiples eventos de conectividad seguidos
# (Desconectar/conectar WiFi del ESP32 5 veces en 1 minuto)

# 2. Verificar rate limiting en acción
redis-cli KEYS "ratelimit:MEDIUM:*"

# 3. Ver logs de eventos bloqueados
sudo journalctl -u logserver | grep "Rate limit exceeded" | tail -10

# 4. Verificar que eventos CRITICAL (relay) nunca se bloquean
# (Cambiar relay varias veces seguidas)
# Todas las notificaciones deben llegar
```

#### Test 3: Watchdog Auto-Restart

```bash
# 1. Detener servicio principal manualmente
sudo systemctl stop logserver

# 2. Monitorear watchdog (en otra terminal)
sudo journalctl -u hdd-monitor-watchdog -f

# 3. Esperar ~2 minutos
# El watchdog debe detectar fallo y reiniciar el servicio

# 4. Verificar que el servicio volvió
sudo systemctl status logserver

# 5. Ver logs del reinicio
sudo journalctl -u hdd-monitor-watchdog | grep "Service Down - Restarting" | tail -1
```

### 8.5 Métricas Críticas KPIs

#### NFPA 72 Compliance Rate

```bash
# Total de eventos relay
TOTAL=$(sudo journalctl -u logserver --since "7 days ago" | grep "nfpa72_notification_latency" | wc -l)

# Eventos compliant (<90s)
COMPLIANT=$(sudo journalctl -u logserver --since "7 days ago" | grep "nfpa72_compliant.*true" | wc -l)

# Calcular porcentaje
echo "NFPA 72 Compliance: $COMPLIANT / $TOTAL"
echo "Percentage: $(echo "scale=2; $COMPLIANT*100/$TOTAL" | bc)%"
```

#### Rate Limit Triggers

```bash
# Total de eventos bloqueados (últimas 24 horas)
sudo journalctl -u logserver --since "24 hours ago" | grep "Rate limit exceeded" | wc -l

# Por prioridad
echo "MEDIUM (connectivity):"
sudo journalctl -u logserver --since "24 hours ago" | grep "Rate limit exceeded.*MEDIUM" | wc -l

echo "HIGH (offline):"
sudo journalctl -u logserver --since "24 hours ago" | grep "Rate limit exceeded.*HIGH" | wc -l
```

#### FCM Retry Rate

```bash
# Total de envíos FCM
TOTAL_FCM=$(sudo journalctl -u logserver --since "24 hours ago" | grep "FCM sent successfully" | wc -l)

# Envíos que requirieron reintentos
RETRY_FCM=$(sudo journalctl -u logserver --since "24 hours ago" | grep "FCM failed (attempt" | wc -l)

echo "FCM Success: $TOTAL_FCM"
echo "FCM Retries: $RETRY_FCM"
echo "Retry Rate: $(echo "scale=2; $RETRY_FCM*100/$TOTAL_FCM" | bc)%"
```

#### Watchdog Restarts

```bash
# Contar reinicios automáticos (últimos 30 días)
sudo journalctl -u hdd-monitor-watchdog --since "30 days ago" | grep "Service Down - Restarting" | wc -l

# Ver fechas de reinicios
sudo journalctl -u hdd-monitor-watchdog --since "30 days ago" | grep "Service Down - Restarting"
```

#### PostgreSQL Sync Rate

```bash
# Total de eventos en PostgreSQL (últimas 24h)
PG_EVENTS=$(psql -U hdd_monitor_user -d hdd_monitor -t -c \
  "SELECT COUNT(*) FROM relay_events WHERE timestamp > NOW() - INTERVAL '24 hours';")

echo "PostgreSQL events (24h): $PG_EVENTS"

# Verificar que coincida con logs del servidor
LOGGED_EVENTS=$(sudo journalctl -u logserver --since "24 hours ago" | grep "Relay event persisted to PostgreSQL" | wc -l)

echo "Logged PostgreSQL persist: $LOGGED_EVENTS"
```

---

## 9. SECURITY Y MEJORES PRÁCTICAS

### 9.1 Rotación de Contraseñas

#### PostgreSQL Password

```bash
# 1. Generar nueva contraseña segura
NEW_PG_PASS=$(openssl rand -base64 32)
echo "Nueva contraseña PostgreSQL: $NEW_PG_PASS"

# 2. Actualizar en PostgreSQL
sudo -u postgres psql -c "ALTER USER hdd_monitor_user PASSWORD '$NEW_PG_PASS';"

# 3. Actualizar variable de entorno
sed -i "s/export PG_PASSWORD=.*/export PG_PASSWORD='$NEW_PG_PASS'/" ~/.bashrc
source ~/.bashrc

# 4. Actualizar config.py (si está hardcodeado)
# MEJOR: Usar variable de entorno (ya implementado)

# 5. Reiniciar servicio
sudo systemctl restart logserver
```

#### MQTT Manager Login

```bash
# 1. Generar nuevo SECRET_KEY
NEW_SECRET=$(python3 -c "import os; print(os.urandom(24).hex())")
echo "Nuevo SECRET_KEY: $NEW_SECRET"

# 2. Generar hash de nueva contraseña
source /home/pqsolutions/mqtt-manager-venv/bin/activate
python3 -c "from werkzeug.security import generate_password_hash; print(generate_password_hash('TU_NUEVA_PASSWORD_AQUI'))"
# Copiar el hash generado

# 3. Actualizar servicio systemd
sudo nano /etc/systemd/system/mqtt-manager.service
# Actualizar:
# Environment="FLASK_SECRET_KEY=NUEVO_SECRET_KEY"
# Environment="ADMIN_PASSWORD_HASH=NUEVO_HASH"

# 4. Recargar y reiniciar
sudo systemctl daemon-reload
sudo systemctl restart mqtt-manager
```

#### MQTT Broker Passwords

```bash
# Cambiar contraseña de usuario MQTT
sudo mosquitto_passwd -b /etc/mosquitto/passwd mqtt_firestore_handler NUEVA_PASSWORD

# Reiniciar Mosquitto
sudo systemctl restart mosquitto

# Actualizar en config.py
sudo nano /home/pqsolutions/hdd-monitor/config.py
# Buscar MQTT_CONFIG['PASSWORD'] y actualizar

# Reiniciar servicio
sudo systemctl restart logserver
```

### 9.2 Backup Strategies

#### Backup Completo Automático

```bash
# Crear script de backup
sudo nano /home/pqsolutions/backup.sh
```

```bash
#!/bin/bash
# Backup script para HDD-Monitor
BACKUP_DIR="/home/pqsolutions/backups/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR"

# Backup de código
tar -czf "$BACKUP_DIR/hdd-monitor.tar.gz" /home/pqsolutions/hdd-monitor/

# Backup de PostgreSQL
sudo -u postgres pg_dump hdd_monitor | gzip > "$BACKUP_DIR/hdd_monitor_db.sql.gz"

# Backup de configuración MQTT
sudo cp /etc/mosquitto/passwd "$BACKUP_DIR/mosquitto_passwd.bak"
sudo cp /etc/mosquitto/conf.d/hdd-monitor.conf "$BACKUP_DIR/mosquitto_conf.bak"

# Backup de systemd services
sudo cp /etc/systemd/system/hdd-monitor-watchdog.service "$BACKUP_DIR/"
sudo cp /etc/systemd/system/mqtt-manager.service "$BACKUP_DIR/"

# Backup de Redis dump (si existe)
if [ -f /var/lib/redis/dump.rdb ]; then
    sudo cp /var/lib/redis/dump.rdb "$BACKUP_DIR/"
fi

# Limpiar backups antiguos (>30 días)
find /home/pqsolutions/backups/ -mtime +30 -type d -exec rm -rf {} \; 2>/dev/null

echo "Backup completado: $BACKUP_DIR"
```

```bash
# Hacer ejecutable
sudo chmod +x /home/pqsolutions/backup.sh

# Programar backup diario (cron)
crontab -e
# Agregar línea:
# 0 2 * * * /home/pqsolutions/backup.sh > /home/pqsolutions/backup.log 2>&1
```

#### Backup Manual Rápido

```bash
# Crear directorio de backup
BACKUP_DIR="/home/pqsolutions/manual_backup_$(date +%Y%m%d)"
mkdir -p "$BACKUP_DIR"

# Código
sudo cp -r /home/pqsolutions/hdd-monitor "$BACKUP_DIR/"

# Base de datos
sudo -u postgres pg_dump hdd_monitor > "$BACKUP_DIR/hdd_monitor.sql"

# Configuraciones
sudo cp /etc/mosquitto/passwd "$BACKUP_DIR/"
sudo cp /etc/systemd/system/hdd-monitor-watchdog.service "$BACKUP_DIR/"

echo "Backup guardado en: $BACKUP_DIR"
```

### 9.3 Actualizaciones de Seguridad

#### Sistema Operativo

```bash
# Actualizar sistema (mensualmente)
sudo apt update
sudo apt upgrade -y

# Ver actualizaciones pendientes
apt list --upgradable

# Actualizar solo paquetes de seguridad
sudo apt update
sudo apt install -y unattended-upgrades
sudo dpkg-reconfigure -plow unattended-upgrades
```

#### Python Dependencies

```bash
# Ver dependencias desactualizadas
cd /home/pqsolutions/hdd-monitor
source /home/pqsolutions/venv/bin/activate
pip list --outdated

# Actualizar dependencias (con precaución)
pip install --upgrade redis structlog psycopg2-binary

# Verificar que todo funciona después
python3 -c "import redis, structlog, psycopg2; print('OK')"

# Reiniciar servicio
sudo systemctl restart logserver
```

### 9.4 Certificados SSL Let's Encrypt

#### Verificar Expiración

```bash
# Ver fecha de expiración
sudo certbot certificates

# Output mostrará:
# Certificate Name: hddm.pqsolutionsperu.com
# Expiry Date: 2026-04-30 12:34:56+00:00 (VALID: 89 days)
```

#### Renovación Manual

```bash
# Test de renovación (dry-run)
sudo certbot renew --dry-run

# Renovación real
sudo certbot renew

# Reiniciar servicios después de renovación
sudo systemctl reload mosquitto
sudo systemctl reload nginx
```

#### Renovación Automática

```bash
# Verificar timer de certbot
sudo systemctl status certbot.timer

# Ver próximas ejecuciones
sudo systemctl list-timers | grep certbot

# Logs de renovaciones automáticas
sudo journalctl -u certbot -n 50
```

### 9.5 Hardening del Sistema

#### Firewall UFW

```bash
# Ver reglas actuales
sudo ufw status verbose

# Agregar protección contra port scanning
sudo ufw limit 22/tcp  # Rate limit SSH

# Bloquear IPs específicas
sudo ufw deny from 1.2.3.4

# Permitir solo rangos específicos (si aplica)
# sudo ufw allow from 10.0.0.0/8 to any port 22
```

#### SSH Hardening

```bash
# Editar configuración SSH
sudo nano /etc/ssh/sshd_config

# Recomendaciones:
# PermitRootLogin no
# PasswordAuthentication yes  (o no si usas keys)
# Port 22  (o cambiar a puerto no estándar)
# MaxAuthTries 3
# ClientAliveInterval 300
# ClientAliveCountMax 2

# Reiniciar SSH
sudo systemctl restart sshd
```

#### PostgreSQL Hardening

```bash
# Verificar que solo escuche en localhost
sudo netstat -tulpn | grep 5432
# Debe mostrar: 127.0.0.1:5432

# Editar pg_hba.conf
sudo nano /etc/postgresql/*/main/pg_hba.conf

# Asegurar que solo hay acceso local:
# local   all             all                                     peer
# host    hdd_monitor     hdd_monitor_user    127.0.0.1/32       md5

# Reiniciar PostgreSQL
sudo systemctl restart postgresql
```

### 9.6 Auditoría y Logs

#### Habilitar Auditoría de Comandos

```bash
# Instalar auditd
sudo apt install -y auditd

# Auditar comandos críticos
sudo auditctl -w /etc/mosquitto/passwd -p wa -k mqtt_users
sudo auditctl -w /etc/systemd/system/ -p wa -k systemd_changes

# Ver logs de auditoría
sudo ausearch -k mqtt_users
sudo ausearch -k systemd_changes
```

#### Rotación de Logs

```bash
# Configurar logrotate para logs del sistema
sudo nano /etc/logrotate.d/hdd-monitor
```

```
/home/pqsolutions/hdd-monitor/logs/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    create 644 pqsolutions pqsolutions
}
```

```bash
# Test de configuración
sudo logrotate -d /etc/logrotate.d/hdd-monitor

# Forzar rotación manual
sudo logrotate -f /etc/logrotate.d/hdd-monitor
```

---

## 10. SCRIPTS AUXILIARES

### 10.1 verify_installation.sh

**Ubicación**: `/home/pqsolutions/hdd-monitor/verify_installation.sh`

**Uso**:

```bash
cd /home/pqsolutions/hdd-monitor
chmod +x verify_installation.sh
./verify_installation.sh
```

**Código completo**:

```bash
#!/bin/bash
# Script de Verificación - HDD Monitor Safety System
# Ejecutar en la VM después del deployment

echo "=========================================="
echo "  HDD Monitor - Verificación de Sistema  "
echo "=========================================="
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

SUCCESS=0
FAILED=0

# Función para verificar
check() {
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓${NC} $1"
        ((SUCCESS++))
    else
        echo -e "${RED}✗${NC} $1"
        ((FAILED++))
    fi
}

# 1. Verificar archivos nuevos
echo "1. Verificando archivos nuevos..."
[ -f /home/pqsolutions/hdd-monitor/rate_limiter.py ]
check "rate_limiter.py"

[ -f /home/pqsolutions/hdd-monitor/nfpa_metrics.py ]
check "nfpa_metrics.py"

[ -f /home/pqsolutions/hdd-monitor/system_watchdog.py ]
check "system_watchdog.py (en hdd-monitor)"

[ -f /home/pqsolutionsperu/system_watchdog.py ]
check "system_watchdog.py (en home)"

[ -f /home/pqsolutions/hdd-monitor/requirements.txt ]
check "requirements.txt"

echo ""

# 2. Verificar dependencias Python
echo "2. Verificando dependencias Python..."
source /home/pqsolutions/venv/bin/activate 2>/dev/null

python3 -c "import structlog" 2>/dev/null
check "structlog instalado"

python3 -c "import redis" 2>/dev/null
check "redis instalado"

python3 -c "import psycopg2" 2>/dev/null
check "psycopg2 instalado"

echo ""

# 3. Verificar Redis
echo "3. Verificando Redis..."
systemctl is-active --quiet redis-server
check "Redis service activo"

redis-cli ping > /dev/null 2>&1
check "Redis responde PONG"

HEARTBEAT=$(redis-cli GET server:last_heartbeat 2>/dev/null)
if [ ! -z "$HEARTBEAT" ]; then
    echo -e "${GREEN}✓${NC} Heartbeat encontrado: $HEARTBEAT"
    ((SUCCESS++))
else
    echo -e "${YELLOW}⚠${NC} Heartbeat no encontrado (normal si servicio no se ha iniciado)"
fi

echo ""

# 4. Verificar PostgreSQL
echo "4. Verificando PostgreSQL..."
systemctl is-active --quiet postgresql
check "PostgreSQL service activo"

sudo -u postgres psql -lqt | cut -d \| -f 1 | grep -qw hdd_monitor
check "Base de datos hdd_monitor existe"

sudo -u postgres psql -c "\du" 2>/dev/null | grep -q hdd_monitor_user
check "Usuario hdd_monitor_user existe"

# Verificar tablas
PGPASSWORD=$PG_PASSWORD psql -U hdd_monitor_user -d hdd_monitor -c "\dt" 2>/dev/null | grep -q relay_events
check "Tabla relay_events existe"

PGPASSWORD=$PG_PASSWORD psql -U hdd_monitor_user -d hdd_monitor -c "\dt" 2>/dev/null | grep -q connectivity_events
check "Tabla connectivity_events existe"

echo ""

# 5. Verificar Watchdog
echo "5. Verificando Watchdog..."
systemctl is-enabled --quiet hdd-monitor-watchdog
check "Watchdog enabled"

systemctl is-active --quiet hdd-monitor-watchdog
check "Watchdog activo"

[ -f /etc/systemd/system/hdd-monitor-watchdog.service ]
check "Servicio systemd instalado"

echo ""

# 6. Verificar servicio principal
echo "6. Verificando servicio principal..."
MAIN_SERVICE=$(sudo systemctl list-units --type=service --state=running | grep -iE "logserver|hdd.*monitor" | awk '{print $1}' | head -1)

if [ ! -z "$MAIN_SERVICE" ]; then
    echo -e "${GREEN}✓${NC} Servicio principal encontrado: $MAIN_SERVICE"
    systemctl is-active --quiet $MAIN_SERVICE
    check "Servicio principal activo"
    ((SUCCESS++))
else
    echo -e "${YELLOW}⚠${NC} No se pudo identificar el servicio principal"
    echo "  Ejecuta: sudo systemctl list-units --type=service --state=running | grep -i monitor"
fi

echo ""

# 7. Verificar imports en Python
echo "7. Verificando imports Python..."
cd /home/pqsolutions/hdd-monitor
source /home/pqsolutions/venv/bin/activate 2>/dev/null

python3 -c "from rate_limiter import RateLimiter, EventPriority" 2>/dev/null
check "rate_limiter importa correctamente"

python3 -c "from nfpa_metrics import NFPAMetricsCollector" 2>/dev/null
check "nfpa_metrics importa correctamente"

python3 -c "import config; print(config.PG_CONFIG)" 2>/dev/null > /dev/null
check "config.py tiene PG_CONFIG"

echo ""

# 8. Resumen
echo "=========================================="
echo "           RESUMEN DE VERIFICACIÓN        "
echo "=========================================="
echo -e "${GREEN}Exitosos: $SUCCESS${NC}"
echo -e "${RED}Fallidos: $FAILED${NC}"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}✓ SISTEMA COMPLETAMENTE INSTALADO${NC}"
    echo ""
    echo "Próximos pasos:"
    echo "1. Reiniciar servicio principal"
    echo "2. Monitorear logs: sudo journalctl -u [servicio] -f"
    echo "3. Verificar heartbeat: watch -n 5 'redis-cli GET server:last_heartbeat'"
else
    echo -e "${YELLOW}⚠ REVISIÓN NECESARIA${NC}"
    echo ""
    echo "Elementos fallidos: $FAILED"
    echo "Revisa los pasos en DEPLOYMENT_GUIDE.md"
fi

echo ""
echo "Para más información:"
echo "  - Guía completa: DEPLOYMENT_GUIDE.md"
echo "  - Arquitectura: ARQUITECTURA_VM.md"
echo ""
```

### 10.2 Script de Monitoreo en Tiempo Real

**Crear**: `/home/pqsolutions/monitor.sh`

```bash
#!/bin/bash
# Script de monitoreo en tiempo real

clear
echo "======================================"
echo "  HDD Monitor - Dashboard en Vivo    "
echo "======================================"

while true; do
    clear
    echo "======================================"
    echo "  HDD Monitor - Dashboard en Vivo    "
    echo "======================================"
    echo ""

    # Timestamp
    echo "Actualización: $(date '+%Y-%m-%d %H:%M:%S')"
    echo ""

    # Servicios
    echo "=== SERVICIOS ==="
    echo -n "Redis:       "; systemctl is-active redis-server
    echo -n "PostgreSQL:  "; systemctl is-active postgresql
    echo -n "Mosquitto:   "; systemctl is-active mosquitto
    echo -n "Nginx:       "; systemctl is-active nginx
    echo -n "MQTT Manager:"; systemctl is-active mqtt-manager
    echo -n "Watchdog:    "; systemctl is-active hdd-monitor-watchdog
    echo -n "LogServer:   "; systemctl is-active logserver
    echo ""

    # Heartbeat
    echo "=== HEARTBEAT ==="
    HB=$(redis-cli GET server:last_heartbeat 2>/dev/null)
    if [ ! -z "$HB" ]; then
        SECONDS_AGO=$(echo "$(date +%s) - $HB" | bc)
        echo "Último heartbeat: ${SECONDS_AGO}s ago"
        if [ "$SECONDS_AGO" -lt 60 ]; then
            echo "Estado: ✓ VIVO"
        else
            echo "Estado: ✗ POSIBLE PROBLEMA"
        fi
    else
        echo "Heartbeat: NO ENCONTRADO"
    fi
    echo ""

    # PostgreSQL
    echo "=== POSTGRESQL ==="
    EVENTS=$(PGPASSWORD=$PG_PASSWORD psql -U hdd_monitor_user -d hdd_monitor -t -c \
        "SELECT COUNT(*) FROM relay_events WHERE timestamp > NOW() - INTERVAL '1 hour';" 2>/dev/null | tr -d ' ')
    echo "Eventos (última hora): $EVENTS"
    echo ""

    # Rate Limiting
    echo "=== RATE LIMITING ==="
    RL_COUNT=$(redis-cli KEYS "ratelimit:*" 2>/dev/null | wc -l)
    echo "Claves activas: $RL_COUNT"
    echo ""

    # Últimos logs
    echo "=== ÚLTIMOS LOGS ==="
    sudo journalctl -u logserver -n 3 --no-pager | tail -3
    echo ""

    echo "Presiona Ctrl+C para salir"
    sleep 5
done
```

```bash
# Hacer ejecutable
chmod +x /home/pqsolutions/monitor.sh

# Ejecutar
/home/pqsolutions/monitor.sh
```

### 10.3 Script de Limpieza de Logs

**Crear**: `/home/pqsolutions/cleanup_logs.sh`

```bash
#!/bin/bash
# Limpieza de logs antiguos

echo "Limpiando logs antiguos del sistema HDD-Monitor..."

# Limpiar logs de ESP32 (>90 días)
find /home/pqsolutions/esp32_log/ -type f -mtime +90 -delete
echo "✓ Logs ESP32 >90 días eliminados"

# Limpiar eventos de PostgreSQL (>90 días)
PGPASSWORD=$PG_PASSWORD psql -U hdd_monitor_user -d hdd_monitor -c \
    "DELETE FROM relay_events WHERE timestamp < NOW() - INTERVAL '90 days';" >/dev/null
echo "✓ Eventos PostgreSQL >90 días eliminados"

PGPASSWORD=$PG_PASSWORD psql -U hdd_monitor_user -d hdd_monitor -c \
    "DELETE FROM connectivity_events WHERE timestamp < NOW() - INTERVAL '30 days';" >/dev/null
echo "✓ Eventos conectividad >30 días eliminados"

# Optimizar tablas
PGPASSWORD=$PG_PASSWORD psql -U hdd_monitor_user -d hdd_monitor -c \
    "VACUUM ANALYZE relay_events; VACUUM ANALYZE connectivity_events;" >/dev/null
echo "✓ Tablas PostgreSQL optimizadas"

# Limpiar backups antiguos (>30 días)
find /home/pqsolutions/hdd-monitor-backup-* -maxdepth 0 -type d -mtime +30 -exec rm -rf {} \; 2>/dev/null
echo "✓ Backups >30 días eliminados"

echo "Limpieza completada: $(date)"
```

```bash
# Hacer ejecutable
chmod +x /home/pqsolutions/cleanup_logs.sh

# Programar mensualmente (cron)
crontab -e
# Agregar:
# 0 3 1 * * /home/pqsolutions/cleanup_logs.sh >> /home/pqsolutions/cleanup.log 2>&1
```

---

## 11. GESTIÓN DE MQTT MANAGER WEB UI

### 11.1 Acceso a la Interfaz Web

**URL**: https://hddm.pqsolutionsperu.com

**Credenciales de Login**:
- Usuario: `pqsowner`
- Contraseña: (configurada en hash scrypt - ver sección 1.3)

### 11.2 Flujo de Autenticación

```
Usuario accede a https://hddm.pqsolutionsperu.com/
  ↓
No autenticado → Redirect a /login
  ↓
Ingresa credenciales → POST /api/auth/login
  ↓
Hash verificado con check_password_hash()
  ↓
Sesión creada: session['logged_in'] = True
  ↓
Redirect a dashboard (/)
  ↓
Acceso a todas las funciones:
  - Listar usuarios MQTT
  - Crear nuevo usuario ESP32
  - Eliminar usuario
  - Cambiar contraseña
  ↓
Click "Salir" → GET /logout → session.clear()
```

### 11.3 Gestión de Usuarios MQTT

#### Ver Usuarios Actuales

```bash
# Desde terminal de la VM
sudo cat /etc/mosquitto/passwd

# Output ejemplo:
# mqtt_firestore_handler:$7$101$...hash...
# esp32_config_manager:$7$101$...hash...
# ESP32_42A8ACA0:$7$101$...hash...
```

#### Crear Usuario Manual (sin Web UI)

```bash
# Agregar usuario nuevo
sudo mosquitto_passwd -b /etc/mosquitto/passwd NEW_USER_ID NEW_PASSWORD

# Ejemplo para ESP32:
ESP32_ID="42A8ACA0"
sudo mosquitto_passwd -b /etc/mosquitto/passwd "ESP32_$ESP32_ID" "$ESP32_ID"

# Reiniciar Mosquitto (sin downtime)
sudo systemctl reload mosquitto
```

#### Eliminar Usuario

```bash
# Editar archivo manualmente
sudo nano /etc/mosquitto/passwd

# Eliminar línea del usuario

# Reiniciar Mosquitto
sudo systemctl reload mosquitto
```

### 11.4 Troubleshooting MQTT Manager

#### No puedo hacer login

```bash
# Verificar servicio activo
sudo systemctl status mqtt-manager

# Ver logs
sudo journalctl -u mqtt-manager -n 50

# Verificar variables de entorno cargadas
sudo systemctl show mqtt-manager | grep Environment

# Ver intentos de login
sudo journalctl -u mqtt-manager | grep "login"
```

#### Página no carga

```bash
# Verificar Nginx
sudo systemctl status nginx
sudo nginx -t  # Test de configuración

# Ver logs de Nginx
sudo tail -f /var/log/nginx/error.log
sudo tail -f /var/log/nginx/access.log

# Verificar proxy pass
sudo cat /etc/nginx/sites-available/default | grep -A 10 "location /"
```

#### Session expira inmediatamente

```bash
# Verificar SECRET_KEY configurada
sudo systemctl show mqtt-manager | grep SECRET_KEY

# Si no está configurada, editar servicio
sudo nano /etc/systemd/system/mqtt-manager.service

# Agregar:
# Environment="FLASK_SECRET_KEY=0b9e46369d7de802127fd58ad3ffc0f5bf27bdf70c22bb7f"

# Recargar y reiniciar
sudo systemctl daemon-reload
sudo systemctl restart mqtt-manager
```

### 11.5 Comandos Útiles MQTT

#### Probar Conexión MQTT

```bash
# Suscribirse a un topic (desde la VM)
mosquitto_sub -h localhost -p 1883 -u mqtt_firestore_handler -P mqtt_firestore_handler -t "esp32/+/panel_status" -v

# Publicar mensaje de prueba
mosquitto_pub -h localhost -p 1883 -u mqtt_firestore_handler -P mqtt_firestore_handler \
  -t "esp32/TEST/panel_status" -m '{"test": "message"}'
```

#### Ver Conexiones Activas

```bash
# Ver logs de Mosquitto en tiempo real
sudo tail -f /var/log/mosquitto/mosquitto.log

# Filtrar solo conexiones
sudo tail -f /var/log/mosquitto/mosquitto.log | grep "New connection"

# Filtrar desconexiones
sudo tail -f /var/log/mosquitto/mosquitto.log | grep "Socket error"
```

---

## 🎯 RESUMEN EJECUTIVO

Este documento es la **ÚNICA FUENTE DE VERDAD** para la VM Google Cloud del proyecto HDD-Monitor.

### Sistema Certificable NFPA 72 / EN 54

- ✅ Notificaciones <90s (tracked con NFPA metrics)
- ✅ Rate limiting con priorización (CRITICAL events NUNCA limitados)
- ✅ Redundancia PostgreSQL + Firestore
- ✅ Watchdog externo con auto-restart
- ✅ FCM retry logic (3 intentos exponenciales)
- ✅ Validaciones ESP32 y servidor (NTP, time_range, boot loop)
- ✅ Structured logging (JSON con structlog)

### Credenciales Críticas

- PostgreSQL: `hdd_monitor_user` / `nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z`
- MQTT Manager: SECRET_KEY + ADMIN_PASSWORD_HASH (ver sección 1.3)
- MQTT Users: ver `/etc/mosquitto/passwd`

### Deployment Rápido

1. Subir archivos vía SSH browser
2. Backup: `sudo cp -r hdd-monitor hdd-monitor-backup-$(date +%Y%m%d)`
3. Copiar archivos: `sudo cp *.py /home/pqsolutions/hdd-monitor/`
4. Instalar dependencias: `pip install -r requirements.txt`
5. Instalar Redis + PostgreSQL
6. Configurar watchdog service
7. Reiniciar servicios
8. Verificar: `./verify_installation.sh`

### Monitoreo Diario

```bash
# Heartbeat (debe actualizarse cada 30s)
redis-cli GET server:last_heartbeat

# Eventos PostgreSQL
psql -U hdd_monitor_user -d hdd_monitor -c "SELECT COUNT(*) FROM relay_events;"

# Estado servicios
sudo systemctl status redis-server postgresql mosquitto watchdog

# Logs NFPA 72
sudo journalctl -u logserver | grep "nfpa72_compliant"
```

---

**Última actualización**: 2026-01-30
**Versión del sistema**: Safety-Critical NFPA 72 Compliant
**Documentado por**: Claude Code (Anthropic)
**Mantenido por**: PQ Solutions Peru

**ADVERTENCIA FINAL**: Este archivo contiene credenciales reales. Mantenerlo fuera de Git en PRIVATE_CREDENTIALS.
