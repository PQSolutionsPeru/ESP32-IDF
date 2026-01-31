# 🚀 Guía de Deployment - HDD Monitor Safety System

## 📋 Resumen
Esta guía te muestra cómo subir y configurar el sistema de seguridad certificable en la VM de GCP.

## 🎯 Configuración de la VM (Confirmada)

- **IP**: `34.63.146.196`
- **Usuario SSH**: `pqsolutionsperu`
- **Usuario servicios**: `pqsolutions`
- **Ruta proyecto**: `/home/pqsolutions/hdd-monitor/`
- **Entorno virtual**: `/home/pqsolutions/venv/`

---

## ⚡ Comandos Rápidos (Copy-Paste)

**IMPORTANTE**: Estos comandos se ejecutan en la terminal SSH del navegador de Google Cloud.

URL de acceso SSH:
```
https://ssh.cloud.google.com/v2/ssh/projects/fir-hdd-monitor-d00de/zones/us-central1-c/instances/instanciavm-myqtthub
```

### Secuencia de Comandos (después de subir archivos):

```bash
# === PASO 1: Crear directorio temporal (si aún no existe) ===
mkdir -p ~/hdd-monitor-update

# === PASO 2: Si subiste archivos con "Upload file", muévelos al directorio ===
# Los archivos subidos aparecen en /home/pqsolutionsperu/
# Muévelos a la carpeta temporal:
mv ~/*.py ~/*.txt ~/*.sql ~/*.service ~/*.sh ~/hdd-monitor-update/ 2>/dev/null

# === PASO 3: Continuar con instalación ===
sudo cp -r /home/pqsolutions/hdd-monitor /home/pqsolutions/hdd-monitor-backup-$(date +%Y%m%d-%H%M)
cd ~/hdd-monitor-update
sudo cp *.py *.txt *.sql *.sh /home/pqsolutions/hdd-monitor/
sudo chown -R pqsolutions:pqsolutions /home/pqsolutions/hdd-monitor/

cd /home/pqsolutions/hdd-monitor
source /home/pqsolutions/venv/bin/activate
pip install -r requirements.txt

sudo apt update && sudo apt install -y redis-server postgresql postgresql-contrib
sudo systemctl enable redis-server && sudo systemctl start redis-server
sudo -u postgres psql < setup_postgresql.sql
sudo -u postgres psql -c "ALTER USER hdd_monitor_user PASSWORD 'nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z';"
echo "export PG_PASSWORD='nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z'" >> ~/.bashrc && source ~/.bashrc

sudo cp system_watchdog.py /home/pqsolutionsperu/
sudo cp hdd-monitor-watchdog.service /etc/systemd/system/
sudo chmod +x /home/pqsolutionsperu/system_watchdog.py
sudo systemctl daemon-reload
sudo systemctl enable hdd-monitor-watchdog
sudo systemctl start hdd-monitor-watchdog

# Identificar y reiniciar servicio principal (AJUSTAR NOMBRE)
sudo systemctl list-units --type=service --state=running | grep -iE "hdd|monitor|python"
sudo systemctl restart logserver.service  # O el servicio que ejecute main.py

# Verificaciones
redis-cli GET server:last_heartbeat
psql -U hdd_monitor_user -d hdd_monitor -c "SELECT COUNT(*) FROM relay_events;"
sudo systemctl status hdd-monitor-watchdog
```

---

## 📦 PASO 1: Preparar Archivos en PC Local

### Archivos Nuevos a Subir

Desde: `E:\PQSolutions\HDD-Monitor\ESP32-IDF\ESP32-IDF\APP\HDD1_2\VM GOOGLE CLOUD\`

```
requirements.txt                    (Nuevas dependencias Python)
rate_limiter.py                     (Sistema rate limiting)
system_watchdog.py                  (Watchdog externo)
nfpa_metrics.py                     (Métricas NFPA 72)
setup_postgresql.sql                (Schema base de datos)
hdd-monitor-watchdog.service        (Servicio systemd)
install.sh                          (Script instalación)
```

### Archivos Modificados a Actualizar

```
config.py                           (Añadido PG_CONFIG, REDIS_CONFIG, structlog)
mqtt_client.py                      (Añadido validate_time_range())
notification_handler.py             (Rate limiting + FCM retries)
firestore_handler.py                (PostgreSQL + NFPA metrics)
main.py                             (Redis heartbeat)
ARQUITECTURA_VM.md                  (Documentación actualizada)
```

---

## 🔐 PASO 2: Conectar a la VM

Abre la interfaz SSH del navegador de Google Cloud:

```
https://ssh.cloud.google.com/v2/ssh/projects/fir-hdd-monitor-d00de/zones/us-central1-c/instances/instanciavm-myqtthub
```

Deberías ver:
```
pqsolutionsperu@instanciavm-myqtthub:~$
```

✓ **Ya estás conectado y listo para continuar**

---

## 📤 PASO 3: Subir Archivos a la VM

### Usando la Interfaz SSH del Navegador de Google Cloud

Ya que estás usando la interfaz SSH del navegador de GCP, sigue estas instrucciones:

#### 📖 Guía Detallada con Capturas

👉 **Ver guía completa**: `UPLOAD_FILES_GCP.md`

Esta guía incluye:
- ✓ Cómo encontrar el botón "Upload file"
- ✓ Lista completa de archivos a subir
- ✓ Método alternativo con copy-paste
- ✓ Troubleshooting

#### 📁 Resumen Rápido: Usar el Botón "Upload File"

1. En la terminal SSH del navegador, busca:
   - Ícono de **engranaje** (⚙️) o
   - Ícono de **tres puntos** (⋮) o
   - Botón **"⬆ Upload"**

2. Sube estos 12 archivos desde tu PC:
   ```
   E:\PQSolutions\HDD-Monitor\ESP32-IDF\ESP32-IDF\APP\HDD1_2\VM GOOGLE CLOUD\

   Archivos nuevos (7):
   ├── requirements.txt
   ├── rate_limiter.py
   ├── system_watchdog.py
   ├── nfpa_metrics.py
   ├── setup_postgresql.sql
   ├── hdd-monitor-watchdog.service
   └── verify_installation.sh

   Archivos modificados (5):
   ├── config.py
   ├── mqtt_client.py
   ├── notification_handler.py
   ├── firestore_handler.py
   └── main.py
   ```

3. Verificar y mover archivos:
   ```bash
   # Ver archivos subidos
   ls -lh ~/ | grep -E "\.py$|\.txt$|\.sql$|\.service$|\.sh$"

   # Crear directorio temporal
   mkdir -p ~/hdd-monitor-update

   # Mover archivos
   mv ~/*.py ~/*.txt ~/*.sql ~/*.service ~/*.sh ~/hdd-monitor-update/

   # Verificar
   ls -lh ~/hdd-monitor-update/
   # Deberías ver los 12 archivos
   ```

#### Opción B: Crear Archivos Manualmente (Si no hay botón Upload)

Si no ves el botón "Upload file", usaremos un método alternativo con `cat`:

```bash
# En la terminal SSH del navegador, crear directorio temporal
mkdir -p ~/hdd-monitor-update
cd ~/hdd-monitor-update
```

Luego, para cada archivo, usarás este método:

**Ejemplo para crear `requirements.txt`:**

```bash
cat > requirements.txt << 'EOF'
paho-mqtt==1.6.1
google-cloud-firestore==2.13.1
firebase-admin==6.2.0
pytz==2023.3
structlog==24.1.0
psycopg2-binary==2.9.9
redis==5.0.1
EOF
```

📋 **Te proporcionaré todos los archivos con este formato en el siguiente paso**

---

## 📝 PASO 3.5: Crear Archivos Manualmente (Opción B)

Si usaste el botón "Upload file", salta este paso. Si no, copia y pega estos comandos:

### 1. Crear directorio temporal y archivos

```bash
# Crear directorio
mkdir -p ~/hdd-monitor-update
cd ~/hdd-monitor-update

# ARCHIVO 1: requirements.txt
cat > requirements.txt << 'EOF'
paho-mqtt==1.6.1
google-cloud-firestore==2.13.1
firebase-admin==6.2.0
pytz==2023.3
structlog==24.1.0
psycopg2-binary==2.9.9
redis==5.0.1
EOF

# ARCHIVO 2: setup_postgresql.sql
cat > setup_postgresql.sql << 'EOF'
-- HDD-Monitor PostgreSQL Redundancy Setup
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

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO hdd_monitor_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO hdd_monitor_user;
EOF

# ARCHIVO 3: hdd-monitor-watchdog.service
cat > hdd-monitor-watchdog.service << 'EOF'
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
EOF

echo "✓ Archivos base creados"
```

### 2. Descargar archivos Python desde GitHub (Método Alternativo)

Si prefieres, puedo preparar los archivos Python para que los copies y pegues. **¿Prefieres que te los muestre ahora o usaste el botón "Upload file"?**

Por ahora, asumiremos que usaste el botón Upload. Si necesitas los archivos Python en formato copy-paste, avísame.

---

## 🔧 PASO 4: Instalar en la VM

Conectado a la VM via SSH:

```bash
# 1. Ir al directorio de actualización
cd ~/hdd-monitor-update

# 2. Verificar que todos los archivos estén
ls -lh

# 3. Hacer ejecutable el script de instalación
chmod +x install.sh
```

### 📍 Ubicación del Proyecto (CONFIRMADA)

**Ruta del proyecto**: `/home/pqsolutions/hdd-monitor/`

**Archivos actuales**:
```
config.py
firestore_handler.py
main.py
mqtt_client.py
notification_handler.py
esp32_config_manager.py
log_server.py
log_cleanup.py
monitor_mqtt_service.py
combined_ca.crt
logs/
__pycache__/
```

---

## 🚀 PASO 5: Backup y Actualizar Archivos

```bash
# 1. Hacer backup del directorio actual (IMPORTANTE)
sudo cp -r /home/pqsolutions/hdd-monitor /home/pqsolutions/hdd-monitor-backup-$(date +%Y%m%d-%H%M)

# 2. Copiar TODOS los archivos de una vez
sudo cp ~/hdd-monitor-update/*.py /home/pqsolutions/hdd-monitor/
sudo cp ~/hdd-monitor-update/*.txt /home/pqsolutions/hdd-monitor/
sudo cp ~/hdd-monitor-update/*.sql /home/pqsolutions/hdd-monitor/
sudo cp ~/hdd-monitor-update/*.sh /home/pqsolutions/hdd-monitor/
sudo cp ~/hdd-monitor-update/*.service /home/pqsolutions/hdd-monitor/ 2>/dev/null || true

# 3. Ajustar permisos
sudo chown -R pqsolutions:pqsolutions /home/pqsolutions/hdd-monitor/
sudo chmod +x /home/pqsolutions/hdd-monitor/install.sh

# 4. Verificar que se copiaron correctamente
ls -lh /home/pqsolutions/hdd-monitor/ | grep -E "(rate_limiter|nfpa_metrics|system_watchdog)"
# Deberías ver los 3 archivos nuevos
```

---

## 📦 PASO 6: Instalar Dependencias y Servicios

```bash
# 1. Ir al directorio del proyecto
cd /home/pqsolutions/hdd-monitor

# 2. Activar el entorno virtual
source /home/pqsolutions/venv/bin/activate

# 3. Instalar nuevas dependencias Python
pip install -r requirements.txt

# Deberías ver instalarse:
# - structlog==24.1.0
# - psycopg2-binary==2.9.9
# - redis==5.0.1

# 4. Instalar Redis
sudo apt update
sudo apt install -y redis-server
sudo systemctl enable redis-server
sudo systemctl start redis-server

# Verificar Redis
redis-cli ping
# Debe responder: PONG

# 5. Instalar PostgreSQL
sudo apt install -y postgresql postgresql-contrib

# 6. Configurar PostgreSQL
cd /home/pqsolutions/hdd-monitor
sudo -u postgres psql < setup_postgresql.sql

# Verificar PostgreSQL
sudo -u postgres psql -c "\l" | grep hdd_monitor
# Debe aparecer: hdd_monitor | hdd_monitor_user

# 7. Configurar password de PostgreSQL
# IMPORTANTE: Cambiar 'nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z' por tu password real
sudo -u postgres psql -c "ALTER USER hdd_monitor_user PASSWORD 'nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z';"

# 8. Actualizar config.py con el password
# Opción A: Usar variable de entorno (RECOMENDADO)
echo "export PG_PASSWORD='nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z'" >> ~/.bashrc
source ~/.bashrc

# Opción B: Editar config.py directamente
# sudo nano /home/pqsolutions/hdd-monitor/config.py
# Buscar línea: 'password': os.environ.get('PG_PASSWORD', 'default_password')
# El password se tomará de la variable de entorno

# 9. Verificar conexión PostgreSQL
psql -U hdd_monitor_user -d hdd_monitor -c "SELECT version();"
# Si pide password, usa: nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z (o el que configuraste)
```

---

## 🐕 PASO 7: Instalar Watchdog Externo

```bash
# 1. Copiar script watchdog
sudo cp /home/pqsolutions/hdd-monitor/system_watchdog.py /home/pqsolutionsperu/

# 2. Hacer ejecutable
sudo chmod +x /home/pqsolutionsperu/system_watchdog.py

# 3. Copiar servicio systemd
sudo cp /home/pqsolutions/hdd-monitor/hdd-monitor-watchdog.service /etc/systemd/system/

# 4. Editar servicio para configurar SMTP (OPCIONAL - para alertas email)
sudo nano /etc/systemd/system/hdd-monitor-watchdog.service
# Actualizar líneas:
# Environment="SMTP_USER=tu_email@gmail.com"
# Environment="SMTP_PASS=tu_app_password"

# 5. Recargar systemd
sudo systemctl daemon-reload

# 6. Habilitar watchdog
sudo systemctl enable hdd-monitor-watchdog

# 7. Iniciar watchdog
sudo systemctl start hdd-monitor-watchdog

# 8. Verificar estado
sudo systemctl status hdd-monitor-watchdog
```

---

## 🔄 PASO 8: Identificar y Reiniciar Servicios

```bash
# 1. Listar servicios relacionados con HDD Monitor
sudo systemctl list-units --type=service --all | grep -iE "hdd|monitor|mqtt|logserver"

# Servicios típicos según ARQUITECTURA_VM.md:
# - logserver.service       → log_server.py (puerto 8080)
# - Servicio que ejecuta main.py (buscar)

# 2. Ver qué servicio ejecuta main.py
sudo systemctl list-units --type=service --state=running | grep -iE "hdd|monitor|python"

# 3. Ver contenido de servicios para identificar cuál ejecuta main.py
sudo systemctl cat logserver.service  # Ver si ejecuta main.py
# O buscar todos los servicios que ejecutan Python:
sudo grep -r "main.py" /etc/systemd/system/

# 4. Una vez identificado el servicio correcto, reiniciarlo
# Si es logserver.service:
sudo systemctl restart logserver.service
sudo systemctl status logserver.service

# Si hay otro servicio que ejecuta main.py:
# sudo systemctl restart [nombre-del-servicio].service

# 5. Ver logs en tiempo real para verificar que inició bien
# Reemplazar [nombre-servicio] por el servicio correcto
sudo journalctl -u logserver.service -f -n 50

# Busca en los logs:
# - "Iniciando servicio..."
# - Mensajes de Redis heartbeat
# - Sin errores de import
# Presionar Ctrl+C para salir
```

### 🔍 Identificar Servicio Correcto

Si no estás seguro cuál servicio ejecuta `main.py`, usa este comando:

```bash
# Ver todos los procesos Python activos
ps aux | grep python3 | grep -E "main.py|hdd-monitor"

# Ver qué servicios están activos
sudo systemctl --type=service --state=running | grep -i python
```

---

## ✅ PASO 9: Verificar que Todo Funciona

### 1. Verificar Redis Heartbeat

```bash
# Debe retornar un timestamp reciente (Unix time)
redis-cli GET server:last_heartbeat

# Si retorna un número, está funcionando
# Esperar 30 segundos y volver a verificar, debe cambiar
```

### 2. Verificar PostgreSQL

```bash
# Conectar a la base de datos
psql -U hdd_monitor_user -d hdd_monitor

# Dentro de psql, ejecutar:
SELECT COUNT(*) FROM relay_events;
# Debe retornar 0 o el número de eventos actuales

# Ver estructura de tablas
\dt

# Salir de psql
\q
```

### 3. Verificar Watchdog

```bash
# Ver estado
sudo systemctl status hdd-monitor-watchdog

# Ver logs
sudo journalctl -u hdd-monitor-watchdog -n 20

# Debe mostrar mensajes cada 30 segundos verificando el servicio
```

### 4. Verificar Rate Limiting

```bash
# Ver claves de rate limiting (inicialmente vacío)
redis-cli KEYS "ratelimit:*"

# Después de recibir eventos, deberías ver claves como:
# ratelimit:MEDIUM:client123_panel456
```

### 5. Verificar Logs Estructurados

```bash
# Ver logs del servicio principal
sudo journalctl -u vm_monitor_main.service -n 50

# Los logs deberían estar en formato JSON
# Buscar eventos NFPA 72:
sudo journalctl -u vm_monitor_main.service | grep "nfpa72"
```

### 6. Test de Funcionalidad Completa

```bash
# 1. Simular caída del servicio principal
sudo systemctl stop vm_monitor_main.service

# 2. Esperar 2 minutos

# 3. Ver si watchdog lo reinicia automáticamente
sudo systemctl status vm_monitor_main.service
# Estado debe volver a "active (running)"

# 4. Ver logs del watchdog
sudo journalctl -u hdd-monitor-watchdog -n 50
# Debe mostrar "Service Down - Restarting"
```

---

## 🔍 PASO 10: Troubleshooting

### Redis No Responde

```bash
sudo systemctl status redis-server
sudo systemctl restart redis-server
redis-cli ping
```

### PostgreSQL No Conecta

```bash
# Verificar que el servicio está activo
sudo systemctl status postgresql

# Verificar que el usuario existe
sudo -u postgres psql -c "\du" | grep hdd_monitor_user

# Verificar que la base de datos existe
sudo -u postgres psql -c "\l" | grep hdd_monitor

# Reintentar creación si no existe
sudo -u postgres psql < /home/pqsolutions/hdd-monitor/setup_postgresql.sql
```

### Servicio Principal No Inicia

```bash
# Ver logs detallados
sudo journalctl -u vm_monitor_main.service -n 100 --no-pager

# Verificar imports de Python
cd /home/pqsolutions/hdd-monitor
source /home/pqsolutions/venv/bin/activate
python3 -c "import rate_limiter, nfpa_metrics; print('OK')"

# Si falla, reinstalar dependencias
pip install -r requirements.txt
```

### Watchdog No Funciona

```bash
# Ver logs detallados
sudo journalctl -u hdd-monitor-watchdog -xe

# Verificar permisos
ls -l /home/pqsolutionsperu/system_watchdog.py

# Verificar sintaxis Python
python3 /home/pqsolutionsperu/system_watchdog.py --help
```

---

## 📊 PASO 11: Monitoreo Continuo

### Comandos de Monitoreo Diario

```bash
# 1. Estado general del sistema
sudo systemctl status redis-server postgresql hdd-monitor-watchdog vm_monitor_main.service

# 2. Heartbeat (debe actualizarse cada 30s)
watch -n 5 'redis-cli GET server:last_heartbeat'

# 3. Eventos en PostgreSQL (últimos 10)
psql -U hdd_monitor_user -d hdd_monitor -c \
  "SELECT id, relay_id, old_status, new_status, timestamp FROM relay_events ORDER BY timestamp DESC LIMIT 10;"

# 4. Rate limiting activo
redis-cli KEYS "ratelimit:*" | wc -l

# 5. Logs NFPA 72 (compliance)
sudo journalctl -u vm_monitor_main.service --since "1 hour ago" | grep "nfpa72_compliant"
```

---

## ⚠️ Notas Importantes

1. **Backup**: Siempre se hace backup antes de actualizar (paso 5)
2. **Password PostgreSQL**: En los comandos se usa `nJ71pNOGAd$AKPtzlen9pGWg*qeYMW4z` - cámbialo por uno diferente
3. **SMTP**: Configurar credenciales en el watchdog service si quieres alertas por email
4. **Servicio Principal**: IMPORTANTE - Necesitas identificar el servicio que ejecuta `main.py`:
   ```bash
   sudo systemctl list-units --type=service --state=running | grep -iE "hdd|monitor"
   sudo grep -r "main.py" /etc/systemd/system/
   ```
   Puede ser `logserver.service` u otro nombre
5. **Usuario**: El proyecto está bajo usuario `pqsolutions`, pero te conectas como `pqsolutionsperu`
6. **Variable de entorno**: El password de PostgreSQL se configura con `export PG_PASSWORD=...` en `.bashrc`

---

## 📧 Configurar Alertas Email (Opcional)

Si quieres recibir emails cuando el watchdog reinicie servicios:

```bash
# 1. Crear cuenta de aplicación en Gmail
# - Ir a https://myaccount.google.com/apppasswords
# - Crear contraseña de aplicación

# 2. Editar servicio watchdog
sudo nano /etc/systemd/system/hdd-monitor-watchdog.service

# 3. Actualizar:
Environment="SMTP_USER=tu_email@gmail.com"
Environment="SMTP_PASS=tu_app_password_de_16_caracteres"

# 4. Recargar y reiniciar
sudo systemctl daemon-reload
sudo systemctl restart hdd-monitor-watchdog
```

---

## 🧪 PASO 12: Script de Verificación Automática

Hemos incluido un script que verifica toda la instalación automáticamente:

```bash
# 1. Subir el script de verificación (desde PC local)
scp "$LOCAL_DIR/verify_installation.sh" $VM_USER@$VM_IP:~/hdd-monitor-update/

# 2. En la VM, copiar y ejecutar
cd ~/hdd-monitor-update
sudo cp verify_installation.sh /home/pqsolutions/hdd-monitor/
sudo chmod +x /home/pqsolutions/hdd-monitor/verify_installation.sh

# 3. Ejecutar verificación
cd /home/pqsolutions/hdd-monitor
./verify_installation.sh
```

El script verifica:
- ✓ Archivos nuevos instalados
- ✓ Dependencias Python (structlog, redis, psycopg2)
- ✓ Redis activo y respondiendo
- ✓ PostgreSQL con base de datos y tablas
- ✓ Watchdog habilitado y activo
- ✓ Servicio principal corriendo
- ✓ Imports Python funcionando

**Salida esperada**: `✓ SISTEMA COMPLETAMENTE INSTALADO`

---

## ✨ Sistema Listo

Si todos los pasos anteriores y el script de verificación pasan correctamente, tu sistema HDD-Monitor ahora es:

- ✅ **Certificable NFPA 72** (latencia <90s tracked)
- ✅ **Alta disponibilidad** (watchdog + heartbeat)
- ✅ **Redundante** (PostgreSQL backup)
- ✅ **Rate limited** (previene flooding, relay events NUNCA limitados)
- ✅ **Confiable** (FCM retries, validaciones ESP32 y servidor)

**¡Listo para producción con seguridad de vidas humanas!** 🚒🔥

---

## 📞 Soporte

Si encuentras problemas:
1. Revisa los logs: `sudo journalctl -u [servicio] -n 100`
2. Verifica el backup: `/home/pqsolutions/hdd-monitor-backup-*`
3. Consulta ARQUITECTURA_VM.md para estructura del sistema
