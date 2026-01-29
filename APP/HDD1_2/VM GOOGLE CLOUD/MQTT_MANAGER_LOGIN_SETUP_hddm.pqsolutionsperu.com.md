# 🔐 Configuración de Login Seguro - MQTT Manager
# Dominio: hddm.pqsolutionsperu.com

Sistema de autenticación con sesiones seguras para el MQTT Manager Web Interface.

---

## 📋 Cambios Realizados

### 1. Backend (`app.py`)
- ✅ Sesiones seguras con Flask
- ✅ Hash de contraseñas con Werkzeug
- ✅ Decorator `@login_required` para proteger rutas
- ✅ Endpoints de autenticación:
  - `POST /api/auth/login` - Iniciar sesión
  - `GET /api/auth/check` - Verificar autenticación
  - `GET /logout` - Cerrar sesión
- ✅ Todas las rutas API protegidas

### 2. Frontend
- ✅ Página de login (`templates/login.html`)
- ✅ Botón de logout en header (`templates/index.html`)
- ✅ Estilos CSS para login y logout (`static/css/style.css`)

### 3. Seguridad
- ✅ SECRET_KEY configurable por variable de entorno
- ✅ Sesiones con expiración de 24 horas
- ✅ Passwords hasheados con PBKDF2
- ✅ Logs de intentos de login

---

## 🚀 Despliegue en la VM

### Paso 1: Subir archivos actualizados

```bash
# Conectar a la VM
ssh pqsolutionsperu@34.63.146.196

# Ir al directorio del proyecto
cd /home/pqsolutions/mqtt-manager

# Hacer backup del app.py actual
cp app.py app.py.backup.$(date +%Y%m%d_%H%M%S)

# Subir los archivos desde tu máquina local usando SCP:
# (Ejecutar desde tu máquina local, no desde la VM)
```

**Desde tu máquina local (WSL):**

```bash
# Subir app.py actualizado
scp "/mnt/e/PQSolutions/HDD-Monitor/ESP32-IDF/ESP32-IDF/VM GOOGLE CLOUD/mqtt-manager/app.py" \
  pqsolutionsperu@34.63.146.196:/home/pqsolutions/mqtt-manager/

# Subir login.html
scp "/mnt/e/PQSolutions/HDD-Monitor/ESP32-IDF/ESP32-IDF/VM GOOGLE CLOUD/mqtt-manager/templates/login.html" \
  pqsolutionsperu@34.63.146.196:/home/pqsolutions/mqtt-manager/templates/

# Subir index.html actualizado
scp "/mnt/e/PQSolutions/HDD-Monitor/ESP32-IDF/ESP32-IDF/VM GOOGLE CLOUD/mqtt-manager/templates/index.html" \
  pqsolutionsperu@34.63.146.196:/home/pqsolutions/mqtt-manager/templates/

# Subir style.css actualizado
scp "/mnt/e/PQSolutions/HDD-Monitor/ESP32-IDF/ESP32-IDF/VM GOOGLE CLOUD/mqtt-manager/static/css/style.css" \
  pqsolutionsperu@34.63.146.196:/home/pqsolutions/mqtt-manager/static/css/
```

### Paso 2: Configurar SECRET_KEY y contraseña

**En la VM:**

```bash
# Generar SECRET_KEY segura
python3 -c "import os; print(os.urandom(24).hex())"
# Copiar el output

# Generar hash de la contraseña que quieres usar
# Reemplaza 'tu_password_seguro' con la contraseña que desees
python3 -c "from werkzeug.security import generate_password_hash; print(generate_password_hash('tu_password_seguro'))"
# Copiar el hash generado
```
SECRET_KEY:
  0b9e46369d7de802127fd58ad3ffc0f5bf27bdf70c22bb7f

  ADMIN_PASSWORD_HASH:
  scrypt:32768:8:1$oKcrWOh9tbp3UlWR$619f70528fe20bbbfbb859d677e38cb851ab013a24a56bddcce7dc2ff139969487ddc6249758f409
  b5a497d5174a8ed726582c2c10853642c861411e0652f641

### Paso 3: Configurar variables de entorno

```bash
# Editar el archivo de servicio systemd
sudo nano /etc/systemd/system/mqtt-manager.service
```

Agregar las variables de entorno en la sección `[Service]`:

```ini
[Service]
Type=exec
User=pqsolutionsperu
WorkingDirectory=/home/pqsolutions/mqtt-manager
Environment="PATH=/home/pqsolutions/mqtt-manager-venv/bin"
Environment="FLASK_SECRET_KEY=tu_secret_key_generada_aqui"
Environment="ADMIN_PASSWORD_HASH=pbkdf2:sha256:600000$hash_generado_aqui"
ExecStart=/home/pqsolutions/mqtt-manager-venv/bin/gunicorn --bind 127.0.0.1:5000 --workers 2 app:app
Restart=always
RestartSec=10
```

**Ejemplo completo:**

```ini
[Unit]
Description=MQTT Manager Web Interface
After=network.target mosquitto.service nginx.service

[Service]
Type=exec
User=pqsolutionsperu
WorkingDirectory=/home/pqsolutions/mqtt-manager
Environment="PATH=/home/pqsolutions/mqtt-manager-venv/bin"
Environment="FLASK_SECRET_KEY=a3f8d9e2c1b4567890abcdef12345678"
Environment="ADMIN_PASSWORD_HASH=pbkdf2:sha256:600000$abc123$xyz789"
ExecStart=/home/pqsolutions/mqtt-manager-venv/bin/gunicorn --bind 127.0.0.1:5000 --workers 2 app:app
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

### Paso 4: Reiniciar el servicio

```bash
# Recargar configuración de systemd
sudo systemctl daemon-reload

# Reiniciar mqtt-manager
sudo systemctl restart mqtt-manager

# Verificar que está corriendo
sudo systemctl status mqtt-manager

# Ver logs en tiempo real
sudo journalctl -u mqtt-manager -f
```

---

## 🔑 Credenciales

**Usuario configurado:** `pqsowner`

**⚠️ SEGURIDAD IMPORTANTE:**
- Si NO configuras `ADMIN_PASSWORD_HASH` en las variables de entorno, el sistema generará un password aleatorio imposible de adivinar.
- Esto efectivamente DESHABILITA el login hasta que configures la variable correcta.
- **DEBES configurar las variables de entorno para poder acceder al sistema.**

---

## 🧪 Pruebas

### 1. Probar acceso a la web

```bash
# Debe redirigir a /login
curl -I https://hddm.pqsolutionsperu.com/
```

### 2. Probar login

```bash
# Desde la VM o tu máquina
curl -X POST https://hddm.pqsolutionsperu.com/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"tu_password"}' \
  -c cookies.txt

# Debe retornar: {"success":true,"message":"Login successful","username":"admin"}
```

### 3. Probar acceso a API protegida

```bash
# Sin sesión (debe fallar)
curl https://hddm.pqsolutionsperu.com/api/mqtt/users

# Con sesión (debe funcionar)
curl https://hddm.pqsolutionsperu.com/api/mqtt/users -b cookies.txt
```

### 4. Probar logout

```bash
curl https://hddm.pqsolutionsperu.com/logout -b cookies.txt -L
```

### 5. Probar desde el navegador

1. Abrir `https://hddm.pqsolutionsperu.com`
2. Debe aparecer la página de login
3. Ingresar credenciales: `pqsowner` / `tu_password_configurada`
4. Debe redirigir al dashboard
5. Click en "🚪 Salir" debe cerrar sesión y volver al login

---

## 🔒 Características de Seguridad

### Implementadas

- ✅ **Sesiones seguras**: Flask sessions con SECRET_KEY
- ✅ **Passwords hasheados**: PBKDF2-SHA256 (600,000 iteraciones)
- ✅ **Expiración de sesión**: 24 horas
- ✅ **Protección CSRF**: Implícito en Flask sessions
- ✅ **HTTPS**: Let's Encrypt en Nginx
- ✅ **Logs de seguridad**: Login exitosos y fallidos

### Flujo de autenticación

```
1. Usuario accede a https://hddm.pqsolutionsperu.com/
   └─> No autenticado → Redirect a /login

2. Usuario ingresa credenciales en /login
   └─> POST /api/auth/login
       ├─> Hash verificado con check_password_hash()
       ├─> Sesión creada: session['logged_in'] = True
       └─> Redirect a /

3. Usuario accede a dashboard (/)
   └─> @login_required verifica session['logged_in']
   └─> Acceso permitido

4. Usuario hace request a /api/mqtt/users
   └─> @login_required verifica session
   └─> API responde si está autenticado

5. Usuario click en "Salir"
   └─> GET /logout
   └─> session.clear()
   └─> Redirect a /login
```

---

## 📊 Logs y Monitoreo

### Ver intentos de login

```bash
# Ver logs del servicio
sudo journalctl -u mqtt-manager | grep login

# Login exitoso:
# INFO:app:Successful login: pqsowner

# Login fallido:
# WARNING:app:Failed login attempt: hacker
```

### Monitorear sesiones activas

```bash
# No hay endpoint para esto por seguridad
# Las sesiones se almacenan en cookies del cliente (signed con SECRET_KEY)
```

---

## 🔄 Cambiar Contraseña

### Opción 1: Variables de entorno (recomendado)

```bash
# 1. Generar nuevo hash
python3 -c "from werkzeug.security import generate_password_hash; print(generate_password_hash('nueva_password'))"

# 2. Editar servicio
sudo nano /etc/systemd/system/mqtt-manager.service

# 3. Actualizar ADMIN_PASSWORD_HASH

# 4. Reiniciar
sudo systemctl daemon-reload
sudo systemctl restart mqtt-manager
```

### Opción 2: Múltiples usuarios

Editar `app.py` línea 31:

```python
LOGIN_CREDENTIALS = {
    'pqsowner': os.environ.get('PQSOWNER_PASSWORD_HASH', generate_password_hash(os.urandom(32).hex())),
    'admin': os.environ.get('ADMIN_PASSWORD_HASH', generate_password_hash(os.urandom(32).hex())),
    'supervisor': os.environ.get('SUPERVISOR_PASSWORD_HASH', generate_password_hash(os.urandom(32).hex()))
}
```

Luego configurar cada hash en el servicio systemd.

---

## 🆘 Troubleshooting

### Problema: No puedo hacer login

```bash
# Verificar que el servicio está corriendo
sudo systemctl status mqtt-manager

# Ver logs de errores
sudo journalctl -u mqtt-manager -n 50

# Verificar que las variables de entorno están cargadas
sudo systemctl show mqtt-manager | grep Environment
```

### Problema: Página de login no carga

```bash
# Verificar que login.html existe
ls -la /home/pqsolutions/mqtt-manager/templates/login.html

# Verificar logs de Nginx
sudo tail -f /var/log/nginx/error.log
```

### Problema: Session expira inmediatamente

```bash
# Verificar SECRET_KEY está configurada
sudo systemctl show mqtt-manager | grep SECRET_KEY

# Verificar cookies en el navegador (DevTools → Application → Cookies)
```

### Problema: Olvidé la contraseña

```bash
# 1. Generar nuevo hash con contraseña temporal
source /home/pqsolutions/mqtt-manager-venv/bin/activate
python3 -c "from werkzeug.security import generate_password_hash; print(generate_password_hash('temporal123'))"
# Copiar el hash

# 2. Editar servicio systemd
sudo nano /etc/systemd/system/mqtt-manager.service
# Reemplazar ADMIN_PASSWORD_HASH con el nuevo hash

# 3. Reiniciar
sudo systemctl daemon-reload
sudo systemctl restart mqtt-manager

# 4. Login con pqsowner/temporal123
# Luego cambiar la contraseña permanente siguiendo "Cambiar Contraseña"
```

---

## 📝 Notas Importantes

1. **SECRET_KEY**: Debe ser única y secreta. No compartir ni commitear en Git.

2. **Password Hash**: Nunca almacenar passwords en texto plano.

3. **HTTPS obligatorio**: Las sesiones deben transmitirse por HTTPS.

4. **Backup**: Hacer backup antes de cualquier cambio.

5. **Logs**: Revisar logs regularmente para detectar intentos de acceso no autorizado.

---

## ✅ Checklist Post-Despliegue

- [ ] Archivos subidos correctamente
- [ ] SECRET_KEY configurada (no usar default)
- [ ] ADMIN_PASSWORD_HASH configurada
- [ ] Servicio reiniciado correctamente
- [ ] Login funciona desde navegador
- [ ] Logout funciona correctamente
- [ ] API protegida (no accesible sin login)
- [ ] HTTPS funcionando
- [ ] Logs de login funcionando

---

**Dominio**: https://hddm.pqsolutionsperu.com
**Última actualización**: 2025-10-07
**Documentado por**: Claude Code
