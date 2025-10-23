# ✅ CHECKLIST - Sistema de Gestión MQTT Web

Sistema web para gestionar usuarios MQTT del broker Mosquitto desde `hddm.pqsolutionsperu.com`

---

## 📌 **FASE 0: Verificación Inicial**

### DNS y Red
- [x] Configurar registro DNS tipo A
  - Nombre: `hddm`
  - Tipo: `A`
  - Valor: `34.63.146.196`
  - TTL: `900` (15 minutos durante configuración)
- [x] Verificar propagación DNS
  ```bash
  ping hddm.pqsolutionsperu.com
  # Debe resolver a 34.63.146.196
  ```

### Software Base
- [x] Verificar Nginx instalado
  ```bash
  /usr/sbin/nginx -v
  # nginx version: nginx/1.22.1
  ```
- [x] Verificar Python 3.11+
  ```bash
  python3 --version
  # Python 3.11.2
  ```

---

## 📌 **FASE 1: Configuración de Firewall** ✅

### UFW (Firewall VM)
- [x] Abrir puerto 80 (HTTP)
  ```bash
  sudo ufw allow 80/tcp
  ```
- [x] Abrir puerto 443 (HTTPS)
  ```bash
  sudo ufw allow 443/tcp
  ```
- [x] Verificar reglas
  ```bash
  sudo ufw status
  ```

### Google Cloud Firewall
- [x] Verificar regla existente para HTTP/HTTPS
  - Google Cloud Console → VPC Network → Firewall
  - Puertos 80 y 443 ya están abiertos

---

## 📌 **FASE 2: Configuración de Servicios** ✅

### Nginx
- [x] Nginx instalado y corriendo
  ```bash
  sudo systemctl status nginx
  # Active (running)
  ```
- [x] Nginx habilitado en boot
  ```bash
  sudo systemctl enable nginx
  ```

### Python
- [x] Python 3.11.2 instalado
- [x] pip instalado
- [x] python3-venv instalado

---

## 📌 **FASE 3: Entorno Virtual Python** ✅

### Crear venv específico para mqtt-manager
- [x] Salir de venv actual si está activo
  ```bash
  deactivate
  ```
- [x] Crear nuevo venv
  ```bash
  cd /home/pqsolutions
  python3 -m venv mqtt-manager-venv
  ```
- [x] Activar venv
  ```bash
  source mqtt-manager-venv/bin/activate
  ```
- [x] Instalar dependencias
  ```bash
  pip install flask flask-cors gunicorn werkzeug
  ```
- [x] Verificar instalación
  ```bash
  pip list
  # Debe mostrar: Flask, flask-cors, gunicorn, werkzeug
  ```

---

## 📌 **FASE 4: Estructura del Proyecto** ✅

### Crear directorios
- [x] Crear carpeta principal
  ```bash
  cd /home/pqsolutions
  mkdir -p mqtt-manager
  cd mqtt-manager
  ```
- [x] Crear estructura de carpetas
  ```bash
  mkdir -p templates static/css static/js
  ```
- [x] Verificar estructura
  ```bash
  tree /home/pqsolutions/mqtt-manager
  # mqtt-manager/
  # ├── templates/
  # ├── static/
  # │   ├── css/
  # │   └── js/
  ```

---

## 📌 **FASE 5: Código Backend (Flask API)** ✅

### Archivo principal: app.py
- [x] Crear `/home/pqsolutions/mqtt-manager/app.py`
  - Endpoints REST:
    - `GET /` - Interfaz web principal (protegida con @login_required)
    - `GET /login` - Página de login
    - `POST /api/auth/login` - Autenticación
    - `GET /logout` - Cerrar sesión
    - `GET /api/mqtt/users` - Listar usuarios MQTT (protegida)
    - `POST /api/mqtt/users` - Crear usuario ESP32 (protegida)
    - `DELETE /api/mqtt/users/<id>` - Eliminar usuario (protegida)
    - `GET /api/mqtt/status` - Estado del broker (protegida)

### Funcionalidades del backend
- [x] Sistema de autenticación con Flask sessions
- [x] Password hashing con Werkzeug (scrypt)
- [x] Decorator @login_required para proteger rutas
- [x] Función para ejecutar `mosquitto_passwd`
- [x] Parser del archivo `/etc/mosquitto/passwd`
- [x] Validación de ESP32 ID (formato hexadecimal 8 chars)
- [x] Manejo de errores y logs
- [x] CORS habilitado
- [x] Reinicio automático de Mosquitto tras cambios
- [x] Usuario: `pqsowner`

### Permisos sudoers
- [x] Permitir ejecución de comandos específicos sin password
  ```bash
  sudo visudo
  # Agregado:
  pqsolutionsperu ALL=(ALL) NOPASSWD: /usr/bin/mosquitto_passwd
  pqsolutionsperu ALL=(ALL) NOPASSWD: /bin/systemctl restart mosquitto
  pqsolutionsperu ALL=(ALL) NOPASSWD: /bin/systemctl is-active mosquitto
  pqsolutionsperu ALL=(ALL) NOPASSWD: /bin/cat /etc/mosquitto/passwd
  ```

---

## 📌 **FASE 6: Frontend Web** ✅

### HTML
- [x] Crear `/home/pqsolutions/mqtt-manager/templates/index.html`
  - Tabla de usuarios MQTT
  - Formulario para agregar ESP32
  - Botones de eliminar
  - Indicador de estado del broker
  - Header con usuario y botón "🚪 Salir"
- [x] Crear `/home/pqsolutions/mqtt-manager/templates/login.html`
  - Página de login moderna
  - Validación de formularios
  - Manejo de errores
  - Animaciones smooth

### CSS
- [x] Crear `/home/pqsolutions/mqtt-manager/static/css/style.css`
  - Diseño responsive
  - Tema profesional (colores HDD Monitor)
  - Animaciones y transiciones
  - Estilos para login
  - Botón de logout

### JavaScript
- [x] Crear `/home/pqsolutions/mqtt-manager/static/js/app.js`
  - Fetch API para comunicación con backend
  - Auto-refresh de tabla de usuarios
  - Validación de formularios
  - Confirmación antes de eliminar
  - Notificaciones toast
- [x] JavaScript embebido en login.html
  - Autenticación con POST /api/auth/login
  - Manejo de sesiones
  - Redirección post-login

---

## 📌 **FASE 7: Configuración Nginx** ✅

### Crear configuración del sitio
- [x] Crear archivo de configuración
  ```bash
  sudo nano /etc/nginx/sites-available/mqtt-manager
  ```
- [x] Configurar reverse proxy
  ```nginx
  server {
      listen 80;
      server_name hddm.pqsolutionsperu.com;

      location / {
          proxy_pass http://127.0.0.1:5000;
          proxy_set_header Host $host;
          proxy_set_header X-Real-IP $remote_addr;
          proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
          proxy_set_header X-Forwarded-Proto $scheme;
      }

      location /static {
          alias /home/pqsolutions/mqtt-manager/static;
      }
  }
  ```

### Habilitar sitio
- [x] Crear symlink
  ```bash
  sudo ln -s /etc/nginx/sites-available/mqtt-manager /etc/nginx/sites-enabled/
  ```
- [x] Verificar configuración
  ```bash
  sudo nginx -t
  ```
- [x] Reiniciar Nginx
  ```bash
  sudo systemctl restart nginx
  ```

---

## 📌 **FASE 8: Servicio Systemd** ✅

### Crear servicio para Flask
- [x] Crear archivo de servicio
  ```bash
  sudo nano /etc/systemd/system/mqtt-manager.service
  ```
- [x] Configurar servicio con variables de entorno
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

### Habilitar y arrancar servicio
- [x] Recargar systemd
  ```bash
  sudo systemctl daemon-reload
  ```
- [x] Habilitar servicio
  ```bash
  sudo systemctl enable mqtt-manager
  ```
- [x] Iniciar servicio
  ```bash
  sudo systemctl start mqtt-manager
  ```
- [x] Verificar estado
  ```bash
  sudo systemctl status mqtt-manager
  # Active: active (running)
  ```

---

## 📌 **FASE 9: SSL con Let's Encrypt** ✅

### Instalar Certbot
- [x] Instalar Certbot y plugin Nginx
  ```bash
  sudo apt install certbot python3-certbot-nginx -y
  ```

### Obtener certificado SSL
- [x] Ejecutar Certbot
  ```bash
  sudo certbot --nginx -d hddm.pqsolutionsperu.com
  ```
- [x] Seguir instrucciones interactivas
  - Email de administrador
  - Aceptar términos
  - Redirect HTTP → HTTPS: Sí

### Configurar Mosquitto con Let's Encrypt
- [x] Actualizar configuración de Mosquitto
  ```bash
  sudo nano /etc/mosquitto/conf.d/hdd-monitor.conf
  ```
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

- [x] Configurar permisos de certificados
  ```bash
  sudo chmod 640 /etc/letsencrypt/archive/hddm.pqsolutionsperu.com/privkey1.pem
  sudo chgrp -R mosquitto /etc/letsencrypt/live/hddm.pqsolutionsperu.com/
  sudo chgrp -R mosquitto /etc/letsencrypt/archive/hddm.pqsolutionsperu.com/
  ```

- [x] Reiniciar Mosquitto
  ```bash
  sudo systemctl restart mosquitto
  sudo systemctl status mosquitto
  ```

### Verificar auto-renovación
- [x] Test de renovación
  ```bash
  sudo certbot renew --dry-run
  ```
- [x] Verificar timer de renovación
  ```bash
  sudo systemctl status certbot.timer
  ```

---

## 📌 **FASE 10: Pruebas Finales** ✅

### Pruebas de acceso web
- [x] Acceder a `http://hddm.pqsolutionsperu.com`
  - Redirige a HTTPS automáticamente ✅
- [x] Acceder a `https://hddm.pqsolutionsperu.com`
  - Certificado SSL válido ✅
  - Redirige a `/login` si no autenticado ✅
  - Interfaz web carga correctamente ✅

### Pruebas de autenticación
- [x] Página de login funcional
- [x] Login con usuario `pqsowner` funciona ✅
- [x] Dashboard carga después de login ✅
- [x] Botón "🚪 Salir" funciona correctamente ✅
- [x] Sesión persiste durante 24 horas ✅
- [x] Rutas protegidas sin login retornan 401 ✅

### Pruebas funcionales MQTT
- [x] Agregar usuario ESP32 desde web
  - ID: `42A8ACA0`
  - Password: `42A8ACA0`
- [x] Verificar que se crea en `/etc/mosquitto/passwd`
  ```bash
  sudo cat /etc/mosquitto/passwd | grep 42A8ACA0
  ```
- [x] Verificar que Mosquitto se reinicia automáticamente
  ```bash
  sudo journalctl -u mosquitto -n 20
  ```
- [x] Eliminar usuario desde web funciona ✅
- [x] Protección de usuarios del sistema (no se pueden eliminar) ✅

### Pruebas de integración ESP32
- [x] ESP32 conecta al broker con SSL ✅
- [x] Dispositivos visibles en la web (42A8ACA0, 1694ACA8) ✅
- [x] Broker status muestra "ONLINE" ✅
- [x] ESP32 publica mensajes correctamente ✅

### Monitoreo
- [x] Verificar logs de Flask
  ```bash
  sudo journalctl -u mqtt-manager -f
  # Logs de login visibles
  ```
- [x] Verificar logs de Nginx
  ```bash
  sudo tail -f /var/log/nginx/access.log
  sudo tail -f /var/log/nginx/error.log
  ```
- [x] Verificar logs de Mosquitto
  ```bash
  sudo journalctl -u mosquitto -f
  # Conexiones y mensajes visibles
  ```

---

## 📌 **FASE 11: Optimizaciones y Seguridad**

### Seguridad
- [x] Sistema de login con Flask sessions ✅
- [x] Usuario: `pqsowner` ✅
- [x] Password hashing con Werkzeug ✅
- [x] Todas las rutas API protegidas ✅
- [ ] Configurar rate limiting en Nginx
- [ ] Configurar headers de seguridad
  ```nginx
  add_header X-Frame-Options "SAMEORIGIN";
  add_header X-Content-Type-Options "nosniff";
  add_header X-XSS-Protection "1; mode=block";
  ```

### Performance
- [ ] Habilitar compresión Gzip en Nginx
- [ ] Configurar caché de archivos estáticos
- [ ] Optimizar workers de Gunicorn según CPU

### Backup
- [ ] Script de backup de `/etc/mosquitto/passwd`
- [ ] Cron job para backup diario
  ```bash
  0 3 * * * cp /etc/mosquitto/passwd /home/pqsolutions/backups/passwd_$(date +\%Y\%m\%d).bak
  ```

---

## 📌 **FASE 12: Documentación y Mantenimiento**

### Documentación
- [x] Crear `ARQUITECTURA_VM.md`
- [x] Crear `CHECKLIST_MQTT_MANAGER.md`
- [x] Crear `MQTT_MANAGER_LOGIN_SETUP_hddm.pqsolutionsperu.com.md` ✅
- [ ] Crear `API_DOCUMENTATION.md` (endpoints, ejemplos)
- [ ] Crear `TROUBLESHOOTING.md` (problemas comunes)

### Monitoreo continuo
- [ ] Configurar alertas de disco lleno
- [ ] Monitorear uso de memoria
- [ ] Dashboard de métricas (opcional: Grafana)

---

## 🎯 **Tiempo Estimado vs Real**

| Fase | Tiempo Estimado | Tiempo Real | Estado |
|------|----------------|-------------|--------|
| 0-2: Verificación y configuración | 30 min | ~30 min | ✅ COMPLETADO |
| 3-4: Entorno y estructura | 20 min | ~20 min | ✅ COMPLETADO |
| 5-6: Código backend y frontend | 60 min | ~90 min | ✅ COMPLETADO |
| 7-8: Nginx y systemd | 30 min | ~25 min | ✅ COMPLETADO |
| 9: SSL Let's Encrypt + Mosquitto | 15 min | ~45 min | ✅ COMPLETADO |
| 10: Pruebas y validación | 30 min | ~30 min | ✅ COMPLETADO |
| 11: Sistema de login seguro | N/A | ~60 min | ✅ COMPLETADO |
| 12: Documentación | 30 min | ~40 min | ✅ COMPLETADO |
| **TOTAL** | **~3.5 horas** | **~5.5 horas** | **✅ COMPLETADO** |

---

## 📝 Notas Importantes

1. **Usuarios y permisos**:
   - Usuario SSH: `pqsolutionsperu`
   - Usuario servicios: `pqsolutions`
   - Servicio corre como: `pqsolutionsperu`

2. **Venv separados**:
   - `/home/pqsolutions/venv` → hdd-monitor (NO TOCAR)
   - `/home/pqsolutions/mqtt-manager-venv` → mqtt-manager (NUEVO)

3. **Puertos**:
   - 5000: Flask (interno, no expuesto)
   - 80/443: Nginx (público)
   - 8883: Mosquitto SSL (ESP32)

4. **Archivos críticos**:
   - `/etc/mosquitto/passwd` - Requiere permisos sudo
   - `/etc/mosquitto/conf.d/hdd-monitor.conf` - Configuración broker
   - `/etc/nginx/sites-available/mqtt-manager` - Config Nginx

---

---

## 🎉 **PROYECTO COMPLETADO**

**Fecha de finalización**: 2025-10-07
**URL en producción**: https://hddm.pqsolutionsperu.com
**Estado**: ✅ Sistema completamente funcional en producción

### Características implementadas:
- ✅ MQTT Manager Web UI con autenticación segura
- ✅ Usuario: `pqsowner` con password hasheado
- ✅ SSL/TLS con Let's Encrypt en Nginx y Mosquitto
- ✅ ESP32 conectando con verificación SSL completa
- ✅ CRUD de usuarios MQTT desde interfaz web
- ✅ Logs de seguridad y monitoreo
- ✅ Documentación completa

**Última actualización**: 2025-10-07
**Próximos pasos**: Ver sección "Próximas Implementaciones" para features adicionales
