# ESP32 Health Monitoring System

Sistema completo de monitoreo de salud para dispositivos ESP32 con alertas automáticas por correo electrónico.

## 📋 Descripción General

Este sistema monitorea continuamente la salud de los dispositivos ESP32 y envía alertas automáticas cuando detecta problemas críticos. Incluye:

### **ESP32 (Firmware)**
- Tarea independiente de alta prioridad en Core 1
- Monitoreo continuo de salud del sistema
- Detección automática de:
  - Boot loops
  - Memoria baja / Memory leaks
  - Reinicios inesperados
  - Timeouts de watchdog
  - Desconexiones WiFi/MQTT
  - Stack overflows
  - Corrupción de heap
- Envío de alertas MQTT al servidor
- Upload automático de logs en caso de errores críticos

### **Servidor (Python/Flask)**
- Monitor de salud que escucha alertas MQTT
- Detección de heartbeats perdidos
- Historial de alertas por dispositivo
- Sistema de email con adjuntos de logs
- Rate limiting (max 1 email por dispositivo por hora)
- API REST para dashboard

---

## 🏗️ Arquitectura

```
ESP32 Device
├── Main Task (Core 0)
│   └── Application logic
└── Health Monitor Task (Core 1) ⭐ NUEVO
    ├── Continuous health checks
    ├── MQTT alert publishing
    └── Log upload triggering

            ↓ MQTT

Server (VM Google Cloud)
├── MQTT Broker (Mosquitto)
│   └── Topic: hdd-monitor/alerts/{ESP32_ID}
├── Health Monitor Service
│   ├── Alert listener
│   ├── Heartbeat tracker
│   └── Statistics aggregator
├── Email Alerter
│   ├── SMTP sender
│   └── Log file attachment
└── Flask API
    ├── /api/esp32/health/status
    ├── /api/esp32/health/alerts/<id>
    └── /api/esp32/health/statistics
```

---

## 🚀 Componentes Creados

### **ESP32 Firmware**

#### `components/health_monitor/`
- **`health_monitor.h`**: API pública del monitor
- **`health_monitor.c`**: Implementación del monitor de salud
- **`CMakeLists.txt`**: Configuración de compilación

**Características:**
- Tarea independiente que corre en Core 1
- Alta prioridad (configMAX_PRIORITIES - 2)
- Checks cada 5 segundos
- Publicación MQTT de alertas
- Thread-safe con mutex

#### Modificaciones en `main/hddesp32_main.c`
```c
#include "health_monitor.h"

// En app_main():
ESP_ERROR_CHECK(health_monitor_init(g_esp32_id_buffer));
```

---

### **Servidor**

#### `modules/esp32_health_monitor.py`
Monitor de salud que escucha MQTT y rastrea el estado de los ESP32s.

**Clases principales:**
- `HealthAlert`: Estructura de alerta desde ESP32
- `ESP32HealthStatus`: Estado de salud actual
- `ESP32HealthMonitor`: Servicio principal de monitoreo

**Tópicos MQTT:**
- `hdd-monitor/alerts/+`: Alertas de problemas
- `hdd-monitor/heartbeat/+`: Señales de vida

#### `modules/email_alerter.py`
Sistema de envío de emails con adjuntos de logs.

**Características:**
- Soporte SMTP con TLS
- Adjunta archivo log más reciente del ESP32
- Email HTML formateado
- Rate limiting (1 email/hora por dispositivo)
- Búsqueda automática de logs en `/home/pqsolutions/esp32_log/{ESP32_ID}/`

#### `modules/health_system.py`
Integrador que conecta el monitor con el alerter.

**Funcionalidad:**
- Inicia el monitor MQTT
- Registra callback de alertas
- Envía emails solo para alertas CRITICAL y FATAL
- Evita duplicados

#### Modificaciones en `app.py`
```python
from modules.health_system import HealthMonitoringSystem
from modules.email_alerter import EmailConfig

# Inicialización
health_system = HealthMonitoringSystem(email_config=..., mqtt_broker='localhost')
health_system.start()

# Nuevos endpoints API:
# - GET /api/esp32/health/status
# - GET /api/esp32/health/alerts/<esp32_id>
# - GET /api/esp32/health/statistics
```

---

## ⚙️ Configuración

### **1. Archivo de Configuración de Email**

Crear `/home/pqsolutions/mqtt-manager/config.email.json`:

```json
{
  "smtp_server": "smtp.gmail.com",
  "smtp_port": 587,
  "smtp_user": "tu-email@gmail.com",
  "smtp_password": "tu-app-password",
  "from_email": "tu-email@gmail.com",
  "to_emails": [
    "admin@pqsolutionsperu.com",
    "alerts@pqsolutionsperu.com"
  ],
  "use_tls": true,
  "log_base_path": "/home/pqsolutions/esp32_log",
  "mqtt_broker": "localhost",
  "mqtt_port": 1883
}
```

**Nota:** Para Gmail, necesitas crear una "App Password" en:
https://myaccount.google.com/apppasswords

### **2. Variables de Entorno (opcional)**

```bash
export EMAIL_CONFIG_PATH=/home/pqsolutions/mqtt-manager/config.email.json
```

### **3. Actualizar Dependencias del Servidor**

```bash
cd /home/pqsolutions/mqtt-manager
source venv/bin/activate
pip install paho-mqtt==1.6.1
```

---

## 📤 Compilar y Flashear ESP32

### **1. Limpiar y recompilar**

```bash
cd ESP-IDF-HDDESP32/hddesp32
idf.py fullclean
idf.py build
```

### **2. Flashear dispositivo**

```bash
idf.py -p /dev/ttyUSB0 flash monitor
```

---

## 🔍 Monitoreo y Logs

### **Ver logs del servidor**

```bash
# Logs de la aplicación
sudo journalctl -u mqtt-manager -f

# Logs del broker MQTT
sudo journalctl -u mosquitto -f

# Logs de salud ESP32 (si se implementa logging dedicado)
tail -f /var/log/esp32-health.log
```

### **Ver logs de un ESP32 específico**

```bash
# Último log
ls -lt /home/pqsolutions/esp32_log/3608AC08/log_*.txt | head -1

# Ver contenido
tail -f /home/pqsolutions/esp32_log/3608AC08/log_20260201_*.txt
```

### **Buscar errores críticos**

```bash
grep -r "CRITICAL\|FATAL\|BOOT LOOP" /home/pqsolutions/esp32_log/3608AC08/ | tail -20
```

---

## 📊 API REST

### **Obtener estado de salud de todos los ESP32s**

```bash
curl -X GET http://localhost:5000/api/esp32/health/status \
  -H "Authorization: Bearer <token>"
```

**Respuesta:**
```json
{
  "success": true,
  "devices": [
    {
      "esp32_id": "3608AC08",
      "is_healthy": false,
      "last_heartbeat": "2026-02-01T12:30:00",
      "total_alerts": 15,
      "critical_alerts_24h": 3,
      "consecutive_failures": 2,
      "uptime_ms": 3600000,
      "last_alert": {
        "status": "CRITICAL",
        "issue_type": "BOOT_LOOP",
        "description": "Boot loop detected: 20 boots in <5min",
        "timestamp": "2026-02-01T12:25:00"
      }
    }
  ]
}
```

### **Obtener historial de alertas de un ESP32**

```bash
curl -X GET "http://localhost:5000/api/esp32/health/alerts/3608AC08?limit=50" \
  -H "Authorization: Bearer <token>"
```

### **Obtener estadísticas generales**

```bash
curl -X GET http://localhost:5000/api/esp32/health/statistics \
  -H "Authorization: Bearer <token>"
```

**Respuesta:**
```json
{
  "success": true,
  "statistics": {
    "total_devices": 3,
    "healthy_devices": 2,
    "unhealthy_devices": 1,
    "total_alerts_24h": 12,
    "monitored_devices": ["3608AC08", "1694ACA8", "42A8ACA0"]
  },
  "unhealthy_devices": [
    {
      "esp32_id": "3608AC08",
      "critical_alerts_24h": 3,
      "consecutive_failures": 2,
      "last_issue": "BOOT_LOOP"
    }
  ]
}
```

---

## 📧 Formato de Emails

### **Asunto**
```
🚨 ESP32 Alert: 3608AC08 - BOOT_LOOP
```

### **Contenido**
- **Status badge** (color según severidad)
- **Device ID**
- **Tipo de problema**
- **Descripción detallada**
- **Métricas:**
  - Uptime
  - Free Heap
  - Min Heap
  - Boot Count
- **Timestamps**
- **Archivo log adjunto** (si disponible)

---

## 🎯 Tipos de Alertas Detectadas

| Tipo | Severidad | Descripción |
|------|-----------|-------------|
| `BOOT_LOOP` | FATAL | 20+ reinicios en 5 minutos |
| `MEMORY_LOW` | CRITICAL | Heap < 30KB |
| `MEMORY_LEAK` | WARNING | Disminución de 10KB+ por check |
| `UNEXPECTED_REBOOT` | CRITICAL | Reinicio por panic/watchdog |
| `WATCHDOG_TIMEOUT` | CRITICAL | Task watchdog disparado |
| `MQTT_DISCONNECTED` | WARNING | Desconexión de MQTT |
| `WIFI_DISCONNECTED` | WARNING | Desconexión de WiFi |
| `HEAP_CORRUPTION` | FATAL | Corrupción detectada en heap |
| `STACK_OVERFLOW` | FATAL | Desbordamiento de stack |

---

## 🛠️ Solución de Problemas

### **Emails no se envían**

1. Verificar configuración de email:
```bash
cat /home/pqsolutions/mqtt-manager/config.email.json
```

2. Verificar logs del servidor:
```bash
sudo journalctl -u mqtt-manager | grep -i email
```

3. Verificar credenciales de Gmail (App Password)

4. Verificar firewall:
```bash
sudo ufw status
# Debe permitir puerto 587 para SMTP
```

### **ESP32 no envía alertas**

1. Verificar conexión MQTT:
```bash
mosquitto_sub -h localhost -t "hdd-monitor/alerts/#" -v
```

2. Verificar logs del ESP32:
```bash
idf.py -p /dev/ttyUSB0 monitor
```

3. Verificar que health_monitor se inicializó:
```
I (1285) HDDESP32: Health monitor initialized
I (1290) HEALTH_MON: Health monitor task started on core 1
```

### **Health monitor no arranca en el servidor**

1. Verificar que Mosquitto está corriendo:
```bash
sudo systemctl status mosquitto
```

2. Verificar logs:
```bash
sudo journalctl -u mqtt-manager -f | grep -i health
```

3. Verificar dependencias:
```bash
pip list | grep paho-mqtt
# Debe mostrar: paho-mqtt 1.6.1
```

---

## 📝 Tareas Pendientes (Opcional)

- [ ] Dashboard web para visualizar alertas en tiempo real
- [ ] Notificaciones push/Telegram además de email
- [ ] Persistencia de alertas en base de datos
- [ ] Análisis de tendencias y predicción de fallos
- [ ] Auto-recovery actions (reinicio remoto, etc.)

---

## 📚 Referencias

- **ESP-IDF FreeRTOS**: https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-reference/system/freertos.html
- **Paho MQTT Python**: https://pypi.org/project/paho-mqtt/
- **Gmail SMTP**: https://support.google.com/mail/answer/7126229

---

**Autor:** Claude Sonnet 4.5
**Fecha:** 2026-02-01
**Versión:** 1.0.0
