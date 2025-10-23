# 🏗️ Arquitectura VM Google Cloud - HDD Monitor

## 📋 Información General

- **IP Estática**: `34.63.146.196`
- **Hostname**: `instanciavm-myqtthub`
- **OS**: Debian GNU/Linux 12 (Bookworm)
- **Kernel**: `6.1.0-37-cloud-amd64`
- **Tipo**: e2-small
- **Usuario SSH**: `pqsolutionsperu`

---

## 🌐 Dominios Configurados

| Dominio | IP | Puerto | Servicio |
|---------|-----|--------|----------|
| `n8n.pqsolutionsperu.com` | 34.63.146.196 | - | N8N Automation |
| `hddm.pqsolutionsperu.com` | 34.63.146.196 | 80/443 | MQTT Manager Web UI |

---

## 📁 Estructura de Directorios

```
/home/pqsolutions/
├── venv/                          # Entorno virtual principal (Python 3.11.2)
│   └── [USADO POR: hdd-monitor]
│
├── mqtt-manager-venv/             # Entorno virtual para MQTT Manager Web UI
│   └── [Dependencias: Flask, flask-cors, gunicorn]
│
├── hdd-monitor/                   # Proyecto principal de monitoreo
│   ├── main.py
│   ├── log_server.py              # Servidor HTTP logs ESP32 (puerto 8080)
│   ├── mqtt_client.py             # Cliente MQTT
│   ├── firestore_handler.py       # Handler Firebase/Firestore
│   ├── esp32_config_manager.py    # Gestor configuración ESP32
│   ├── notification_handler.py    # Handler notificaciones
│   ├── monitor_mqtt_service.py
│   ├── log_cleanup.py
│   ├── config.py
│   └── logs/
│
├── mqtt-manager/                  # Gestor Web de usuarios MQTT (NUEVO)
│   ├── app.py                     # API Flask para CRUD usuarios
│   ├── templates/
│   │   └── index.html
│   ├── static/
│   │   ├── css/
│   │   │   └── style.css
│   │   └── js/
│   │       └── app.js
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

---

## 🔧 Servicios Activos

### 1. **Mosquitto MQTT Broker**

```bash
Service: mosquitto.service
Status: Active (running)
PID: 553915
```

**Puertos**:
- `1883` - MQTT sin SSL
- `8883` - MQTT con SSL/TLS

**Configuración**:
- Archivo principal: `/etc/mosquitto/mosquitto.conf`
- Configuración HDD: `/etc/mosquitto/conf.d/hdd-monitor.conf`
- Usuarios: `/etc/mosquitto/passwd`
- Certificados SSL: **Let's Encrypt**
  - `/etc/letsencrypt/live/hddm.pqsolutionsperu.com/cert.pem`
  - `/etc/letsencrypt/live/hddm.pqsolutionsperu.com/privkey.pem`
  - `/etc/letsencrypt/live/hddm.pqsolutionsperu.com/chain.pem`

**Características**:
- TLS v1.2 con Let's Encrypt (certificados renovables automáticamente)
- Verificación de certificados habilitada
- Autenticación requerida (`allow_anonymous false`)
- Persistencia: `/var/lib/mosquitto/`
- Logs verbosos: `/var/log/mosquitto/mosquitto.log` (`log_type all`)
- Conexión ESP32: `mqtts://hddm.pqsolutionsperu.com:8883`

### 2. **ESP32 Log Server**

```bash
Proceso: python3 log_server.py
PID: 95186
Puerto: 8080
Venv: /home/pqsolutions/venv
```

**Función**: Recibe logs HTTP de ESP32 y los almacena en `/home/pqsolutions/esp32_log/{ESP32_ID}/`

**Service**: `logserver.service`

### 3. **Nginx Web Server**

```bash
Service: nginx.service
Version: 1.22.1
Status: Active (running)
PID: 557314
```

**Puertos**:
- `80` - HTTP
- `443` - HTTPS (SSL)

**Configuración**:
- Principal: `/etc/nginx/nginx.conf`
- Sites: `/etc/nginx/sites-available/`

**Función**:
- Reverse proxy para aplicaciones Flask
- Servidor de archivos estáticos
- Terminación SSL/TLS

### 4. **MQTT Manager Web UI** ✅ EN PRODUCCIÓN

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
- Usuario: `pqsowner`
- Password hashing con Werkzeug (scrypt)
- Todas las rutas protegidas con @login_required
- Sesiones con expiración de 24 horas

---

## 🐍 Entornos Virtuales Python

### venv (Principal)

**Ubicación**: `/home/pqsolutions/venv/`
**Usuario propietario**: `pqsolutions`
**Python**: 3.11.2

**Paquetes principales**:
```
Flask==3.1.2
firebase-admin==7.1.0
google-cloud-firestore==2.21.0
google-cloud-storage==3.3.1
paho-mqtt==2.1.0
Werkzeug==3.1.3
```

**Usado por**:
- `/home/pqsolutions/hdd-monitor/`
- Servicio: `logserver.service`

### mqtt-manager-venv (MQTT Manager)

**Ubicación**: `/home/pqsolutions/mqtt-manager-venv/`
**Usuario propietario**: `pqsolutionsperu`
**Python**: 3.11.2

**Paquetes**:
```
Flask==3.1.2
flask-cors==6.0.1
gunicorn==23.0.0
Werkzeug==3.1.3
```

**Usado por**:
- `/home/pqsolutions/mqtt-manager/`
- Servicio: `mqtt-manager.service`

---

## 🔥 Firewall (UFW)

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

---

## 🔐 Usuarios y Permisos

### Usuarios del sistema

| Usuario | UID | Rol | Directorio Home |
|---------|-----|-----|-----------------|
| `pqsolutionsperu` | - | Usuario SSH principal | `/home/pqsolutionsperu` |
| `pqsolutions` | - | Usuario de servicios/apps | `/home/pqsolutions` |
| `mosquitto` | - | Usuario del servicio MQTT | `/var/lib/mosquitto` |

### Permisos importantes

```bash
/home/pqsolutions/              # pqsolutions:pqsolutions
/home/pqsolutions/venv/         # pqsolutions:pqsolutions
/home/pqsolutions/mqtt-manager-venv/  # pqsolutionsperu:pqsolutionsperu
/etc/mosquitto/passwd           # mosquitto:mosquitto (644)
/etc/letsencrypt/live/hddm.pqsolutionsperu.com/    # root:mosquitto
/etc/letsencrypt/archive/hddm.pqsolutionsperu.com/ # root:mosquitto
  privkey*.pem                  # 640 (mosquitto needs read access)
```

---

## 🔄 Flujo de Datos

### ESP32 → Firestore

```
ESP32 Device
  ↓ (MQTT SSL 8883)
Mosquitto Broker
  ↓ (Subscribe)
mqtt_client.py
  ↓ (Process)
firestore_handler.py
  ↓ (Store)
Google Cloud Firestore
  ↓ (Sync)
Android App
```

### ESP32 → Logs HTTP

```
ESP32 Device
  ↓ (HTTP POST 8080)
log_server.py
  ↓ (Save)
/home/pqsolutions/esp32_log/{ESP32_ID}/
```

### Gestión Web Usuarios MQTT

```
Admin Web Browser
  ↓ (HTTPS 443)
Nginx Reverse Proxy (hddm.pqsolutionsperu.com)
  ↓ (HTTP 5000)
Flask API (mqtt-manager)
  ├─ Login: POST /api/auth/login
  ├─ Session validation: @login_required
  └─ (Authenticated requests)
      ↓ (Execute)
    mosquitto_passwd CLI
      ↓ (Update)
    /etc/mosquitto/passwd
      ↓ (Reload)
    Mosquitto Broker
```

---

## 📊 Usuarios MQTT Registrados

Ubicación: `/etc/mosquitto/passwd`

```
mqtt_firestore_handler      # Handler de Firestore
esp32_config_manager        # Gestor de configuración
esp32_devices               # Usuario genérico para ESP32
admin_hdd                   # Administrador
```

**Usuarios ESP32 dinámicos**:
- Se crean automáticamente con formato: `{ESP32_ID}:{ESP32_ID}`
- Ejemplo: `42A8ACA0:42A8ACA0`

---

## 🚀 Comandos Útiles

### Gestión de servicios

```bash
# Mosquitto
sudo systemctl status mosquitto
sudo systemctl restart mosquitto
sudo tail -f /var/log/mosquitto/mosquitto.log

# Nginx
sudo systemctl status nginx
sudo systemctl restart nginx
sudo nginx -t  # Test configuración

# Log Server
sudo systemctl status logserver
sudo journalctl -u logserver -f
```

### Gestión de usuarios MQTT

```bash
# Agregar usuario
sudo mosquitto_passwd -b /etc/mosquitto/passwd {username} {password}

# Listar usuarios
sudo cat /etc/mosquitto/passwd

# Eliminar usuario (editar manualmente)
sudo nano /etc/mosquitto/passwd

# Reiniciar Mosquitto después de cambios
sudo systemctl restart mosquitto

# Ver logs en tiempo real (incluye mensajes publicados)
sudo journalctl -u mosquitto -f
sudo tail -f /var/log/mosquitto/mosquitto.log
```

### Monitoreo

```bash
# Ver puertos abiertos
sudo netstat -tulpn | grep LISTEN

# Ver procesos Python
ps aux | grep python3

# Ver firewall
sudo ufw status verbose

# Ver logs del sistema
sudo journalctl -xe
```

### Gestión de venv

```bash
# Activar venv principal
source /home/pqsolutions/venv/bin/activate

# Activar venv mqtt-manager
source /home/pqsolutions/mqtt-manager-venv/bin/activate

# Ver paquetes instalados
pip list

# Salir del venv
deactivate
```

---

## 📝 Notas Importantes

1. **Dos usuarios diferentes**: `pqsolutionsperu` (SSH) vs `pqsolutions` (servicios)
2. **Dos venv separados**: Principal para hdd-monitor, específico para mqtt-manager
3. **Certificados SSL**: Renovar antes de expiración (verificar fechas)
4. **Firestore**: Credenciales en `/home/pqsolutions/credentials/`
5. **Logs ESP32**: Se almacenan indefinidamente (considerar política de limpieza)

---

## 🔐 Configuración SSL/TLS

### ESP32 → Mosquitto

**Broker**: `mqtts://hddm.pqsolutionsperu.com:8883`

**ESP32 SSL Configuration**:
- Usa **ESP-IDF Certificate Bundle** (`esp_crt_bundle_attach`)
- Incluye automáticamente CAs confiables (Let's Encrypt, etc.)
- Verificación de dominio habilitada (`skip_cert_common_name_check = false`)
- No requiere certificado embebido manualmente

**Archivos ESP32**:
- `mqtt_manager.c:35` - Define broker como `hddm.pqsolutionsperu.com`
- `mqtt_ssl_setup.c:33` - Configura `crt_bundle_attach`
- `CMakeLists.txt` - No incluye `EMBED_FILES` (ya no necesario)

**Mosquitto SSL Configuration** (`/etc/mosquitto/conf.d/hdd-monitor.conf`):
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

**Renovación Automática**:
- Certbot timer activo para renovación automática
- Verificar: `sudo systemctl status certbot.timer`
- Test: `sudo certbot renew --dry-run`

---

## 🎉 Sistema Completado

- [x] Certificados SSL Let's Encrypt para `hddm.pqsolutionsperu.com` ✅
- [x] ESP32 con verificación SSL completa (ESP-IDF Certificate Bundle) ✅
- [x] MQTT Manager Web UI en `hddm.pqsolutionsperu.com` ✅
- [x] Sistema de login seguro (Flask sessions + Werkzeug) ✅
- [x] Usuario: `pqsowner` con password hasheado ✅
- [x] Todas las rutas API protegidas ✅
- [x] Documentación completa ✅

## 🔮 Próximas Mejoras (Opcionales)

- [ ] Auto-registro de ESP32 en primera conexión
- [ ] Dashboard de monitoreo en tiempo real
- [ ] Sistema de alertas vía notificaciones push
- [ ] Rate limiting en Nginx
- [ ] Headers de seguridad adicionales
- [ ] Múltiples usuarios administrativos
- [ ] Logs de auditoría en base de datos

---

**Última actualización**: 2025-10-07
**Estado**: ✅ Sistema completamente funcional en producción
**URL producción**: https://hddm.pqsolutionsperu.com
**Mantenido por**: PQ Solutions Peru
