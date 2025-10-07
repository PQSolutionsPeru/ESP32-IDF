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

## 📌 **FASE 3: Entorno Virtual Python**

### Crear venv específico para mqtt-manager
- [ ] Salir de venv actual si está activo
  ```bash
  deactivate
  ```
- [ ] Crear nuevo venv
  ```bash
  cd /home/pqsolutions
  python3 -m venv mqtt-manager-venv
  ```
- [ ] Activar venv
  ```bash
  source mqtt-manager-venv/bin/activate
  ```
- [ ] Instalar dependencias
  ```bash
  pip install flask flask-cors gunicorn
  ```
- [ ] Verificar instalación
  ```bash
  pip list
  # Debe mostrar: Flask, flask-cors, gunicorn
  ```

---

## 📌 **FASE 4: Estructura del Proyecto**

### Crear directorios
- [ ] Crear carpeta principal
  ```bash
  cd /home/pqsolutions
  mkdir -p mqtt-manager
  cd mqtt-manager
  ```
- [ ] Crear estructura de carpetas
  ```bash
  mkdir -p templates static/css static/js
  ```
- [ ] Verificar estructura
  ```bash
  tree /home/pqsolutions/mqtt-manager
  # mqtt-manager/
  # ├── templates/
  # ├── static/
  # │   ├── css/
  # │   └── js/
  ```

---

## 📌 **FASE 5: Código Backend (Flask API)**

### Archivo principal: app.py
- [ ] Crear `/home/pqsolutions/mqtt-manager/app.py`
  - Endpoints REST:
    - `GET /` - Interfaz web principal
    - `GET /api/mqtt/users` - Listar usuarios MQTT
    - `POST /api/mqtt/users` - Crear usuario ESP32
    - `DELETE /api/mqtt/users/<id>` - Eliminar usuario
    - `GET /api/mqtt/status` - Estado del broker

### Funcionalidades del backend
- [ ] Función para ejecutar `mosquitto_passwd`
- [ ] Parser del archivo `/etc/mosquitto/passwd`
- [ ] Validación de ESP32 ID (formato hexadecimal 8 chars)
- [ ] Manejo de errores y logs
- [ ] CORS habilitado para desarrollo
- [ ] Reinicio automático de Mosquitto tras cambios

### Permisos sudoers
- [ ] Permitir ejecución de comandos específicos sin password
  ```bash
  sudo visudo
  # Agregar:
  pqsolutionsperu ALL=(ALL) NOPASSWD: /usr/bin/mosquitto_passwd
  pqsolutionsperu ALL=(ALL) NOPASSWD: /bin/systemctl restart mosquitto
  ```

---

## 📌 **FASE 6: Frontend Web**

### HTML
- [ ] Crear `/home/pqsolutions/mqtt-manager/templates/index.html`
  - Tabla de usuarios MQTT
  - Formulario para agregar ESP32
  - Botones de eliminar
  - Indicador de estado del broker

### CSS
- [ ] Crear `/home/pqsolutions/mqtt-manager/static/css/style.css`
  - Diseño responsive
  - Tema profesional (colores HDD Monitor)
  - Animaciones y transiciones

### JavaScript
- [ ] Crear `/home/pqsolutions/mqtt-manager/static/js/app.js`
  - Fetch API para comunicación con backend
  - Auto-refresh de tabla de usuarios
  - Validación de formularios
  - Confirmación antes de eliminar
  - Notificaciones toast

---

## 📌 **FASE 7: Configuración Nginx**

### Crear configuración del sitio
- [ ] Crear archivo de configuración
  ```bash
  sudo nano /etc/nginx/sites-available/mqtt-manager
  ```
- [ ] Configurar reverse proxy
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
- [ ] Crear symlink
  ```bash
  sudo ln -s /etc/nginx/sites-available/mqtt-manager /etc/nginx/sites-enabled/
  ```
- [ ] Verificar configuración
  ```bash
  sudo nginx -t
  ```
- [ ] Reiniciar Nginx
  ```bash
  sudo systemctl restart nginx
  ```

---

## 📌 **FASE 8: Servicio Systemd**

### Crear servicio para Flask
- [ ] Crear archivo de servicio
  ```bash
  sudo nano /etc/systemd/system/mqtt-manager.service
  ```
- [ ] Configurar servicio
  ```ini
  [Unit]
  Description=MQTT Manager Web Interface
  After=network.target mosquitto.service nginx.service

  [Service]
  Type=exec
  User=pqsolutionsperu
  WorkingDirectory=/home/pqsolutions/mqtt-manager
  Environment="PATH=/home/pqsolutions/mqtt-manager-venv/bin"
  ExecStart=/home/pqsolutions/mqtt-manager-venv/bin/gunicorn --bind 127.0.0.1:5000 --workers 2 app:app
  Restart=always
  RestartSec=10

  [Install]
  WantedBy=multi-user.target
  ```

### Habilitar y arrancar servicio
- [ ] Recargar systemd
  ```bash
  sudo systemctl daemon-reload
  ```
- [ ] Habilitar servicio
  ```bash
  sudo systemctl enable mqtt-manager
  ```
- [ ] Iniciar servicio
  ```bash
  sudo systemctl start mqtt-manager
  ```
- [ ] Verificar estado
  ```bash
  sudo systemctl status mqtt-manager
  ```

---

## 📌 **FASE 9: SSL con Let's Encrypt**

### Instalar Certbot
- [ ] Instalar Certbot y plugin Nginx
  ```bash
  sudo apt install certbot python3-certbot-nginx -y
  ```

### Obtener certificado SSL
- [ ] Ejecutar Certbot
  ```bash
  sudo certbot --nginx -d hddm.pqsolutionsperu.com
  ```
- [ ] Seguir instrucciones interactivas
  - Email de administrador
  - Aceptar términos
  - Redirect HTTP → HTTPS: Sí

### Verificar auto-renovación
- [ ] Test de renovación
  ```bash
  sudo certbot renew --dry-run
  ```
- [ ] Verificar timer de renovación
  ```bash
  sudo systemctl status certbot.timer
  ```

---

## 📌 **FASE 10: Pruebas Finales**

### Pruebas de acceso web
- [ ] Acceder a `http://hddm.pqsolutionsperu.com`
  - Debe redirigir a HTTPS automáticamente
- [ ] Acceder a `https://hddm.pqsolutionsperu.com`
  - Certificado SSL válido ✅
  - Interfaz web carga correctamente

### Pruebas funcionales
- [ ] Agregar usuario ESP32 de prueba
  - ID: `TEST1234`
  - Password: `TEST1234`
- [ ] Verificar que se crea en `/etc/mosquitto/passwd`
  ```bash
  sudo cat /etc/mosquitto/passwd | grep TEST1234
  ```
- [ ] Verificar que Mosquitto se reinicia automáticamente
  ```bash
  sudo journalctl -u mosquitto -n 20
  ```
- [ ] Probar conexión MQTT con nuevo usuario
  ```bash
  mosquitto_pub -h localhost -p 8883 -u TEST1234 -P TEST1234 \
    --cafile /etc/mosquitto/certs/ca.crt -t test/topic -m "Hello"
  ```
- [ ] Eliminar usuario de prueba desde web
- [ ] Verificar que se elimina correctamente

### Pruebas de integración ESP32
- [ ] Conectar ESP32 real al broker
- [ ] Verificar que se auto-registra en la web
- [ ] Ver logs del ESP32 en la interfaz
- [ ] Verificar sincronización con Firestore

### Monitoreo
- [ ] Verificar logs de Flask
  ```bash
  sudo journalctl -u mqtt-manager -f
  ```
- [ ] Verificar logs de Nginx
  ```bash
  sudo tail -f /var/log/nginx/access.log
  sudo tail -f /var/log/nginx/error.log
  ```
- [ ] Verificar logs de Mosquitto
  ```bash
  sudo tail -f /var/log/mosquitto/mosquitto.log
  ```

---

## 📌 **FASE 11: Optimizaciones y Seguridad**

### Seguridad
- [ ] Configurar rate limiting en Nginx
- [ ] Agregar autenticación básica (opcional)
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
- [ ] Crear `API_DOCUMENTATION.md` (endpoints, ejemplos)
- [ ] Crear `TROUBLESHOOTING.md` (problemas comunes)

### Monitoreo continuo
- [ ] Configurar alertas de disco lleno
- [ ] Monitorear uso de memoria
- [ ] Dashboard de métricas (opcional: Grafana)

---

## 🎯 **Tiempo Estimado Total: 3-4 horas**

| Fase | Tiempo | Estado |
|------|--------|--------|
| 0-2: Verificación y configuración | 30 min | ✅ COMPLETADO |
| 3-4: Entorno y estructura | 20 min | 🔄 EN PROGRESO |
| 5-6: Código backend y frontend | 60 min | ⏳ PENDIENTE |
| 7-8: Nginx y systemd | 30 min | ⏳ PENDIENTE |
| 9: SSL Let's Encrypt | 15 min | ⏳ PENDIENTE |
| 10: Pruebas | 30 min | ⏳ PENDIENTE |
| 11-12: Optimización y docs | 30 min | ⏳ PENDIENTE |

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

**Última actualización**: 2025-10-06
**Estado actual**: Fase 3 - Creación de venv
**Próximo paso**: Ejecutar comandos de creación de venv y estructura
