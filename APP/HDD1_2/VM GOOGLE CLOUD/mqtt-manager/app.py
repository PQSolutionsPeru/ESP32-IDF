#!/usr/bin/env python3
"""
MQTT Manager Web Interface
Flask API para gestionar usuarios MQTT en Mosquitto
"""

from flask import Flask, render_template, jsonify, request, session, redirect, url_for
from flask_cors import CORS
from flask_wtf.csrf import CSRFProtect
from flask_socketio import SocketIO, emit
from werkzeug.security import check_password_hash, generate_password_hash
from functools import wraps
import subprocess
import re
import os
import secrets
import hashlib
import threading
import json
from datetime import datetime, timedelta
import logging

# Import modules
from modules.firestore_client import FirestoreClient
from modules.cache import SimpleCache
from modules.health_system import HealthMonitoringSystem
from modules.email_alerter import EmailConfig
from modules.firestore_realtime import FirestoreRealtimeListener

# Configurar logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

def _sanitize_firestore_data(obj):
    """
    Convierte recursivamente tipos no serializables de Firestore a tipos JSON-compatibles.
    Resuelve: DatetimeWithNanoseconds, datetime, date → string ISO 8601
    """
    if hasattr(obj, 'isoformat'):  # datetime, DatetimeWithNanoseconds, date
        return obj.isoformat()
    if isinstance(obj, dict):
        return {k: _sanitize_firestore_data(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_sanitize_firestore_data(i) for i in obj]
    return obj

app = Flask(__name__)
CORS(app)

# Initialize CSRF protection
csrf = CSRFProtect(app)

# Initialize Firestore client (will be initialized with service account in production)
try:
    service_account_path = os.environ.get('GOOGLE_APPLICATION_CREDENTIALS',
                                          '/home/pqsolutionsperu/vm-service-key.json')
    if os.path.exists(service_account_path):
        firestore_client = FirestoreClient(service_account_path)
        logger.info("Firestore client initialized successfully")
    else:
        firestore_client = None
        logger.warning(f"Firestore service account not found at {service_account_path}")
except Exception as e:
    firestore_client = None
    logger.error(f"Failed to initialize Firestore client: {e}")

# Initialize Health Monitoring System
health_system = None
try:
    # Try to load email configuration
    email_config_path = os.environ.get('EMAIL_CONFIG_PATH', './config.email.json')
    if os.path.exists(email_config_path):
        import json
        with open(email_config_path, 'r') as f:
            email_conf = json.load(f)

        email_config = EmailConfig(
            smtp_server=email_conf['smtp_server'],
            smtp_port=email_conf['smtp_port'],
            smtp_user=email_conf['smtp_user'],
            smtp_password=email_conf['smtp_password'],
            from_email=email_conf['from_email'],
            to_emails=email_conf['to_emails'],
            use_tls=email_conf.get('use_tls', True)
        )

        mqtt_broker = email_conf.get('mqtt_broker', 'localhost')
        health_system = HealthMonitoringSystem(email_config=email_config, mqtt_broker=mqtt_broker)
        health_system.start()
        logger.info("Health monitoring system started with email alerts")
    else:
        # Start without email alerts
        health_system = HealthMonitoringSystem(email_config=None, mqtt_broker='localhost')
        health_system.start()
        logger.warning("Health monitoring started WITHOUT email alerts (config not found)")

except Exception as e:
    logger.error(f"Failed to initialize health monitoring system: {e}")

# Configuración de sesión
_secret_key = os.environ.get('FLASK_SECRET_KEY')
if not _secret_key:
    logger.critical("[AUTH] FLASK_SECRET_KEY no está configurado. Las sesiones no sobrevivirán reinicios. "
                    "Configura esta variable de entorno con un valor fijo.")
    _secret_key = os.urandom(24).hex()
app.config['SECRET_KEY'] = _secret_key
app.config['PERMANENT_SESSION_LIFETIME'] = timedelta(days=30)
app.config['WTF_CSRF_TIME_LIMIT'] = None  # No expiration for CSRF tokens

# Constantes de sesión
SESSION_LIFETIME_DAYS = 30
SESSION_CHECK_INTERVAL = 300  # Validar contra Firestore cada 5 minutos

# Initialize SocketIO for real-time updates
socketio = SocketIO(app, cors_allowed_origins="*", async_mode='threading')
logger.info("SocketIO initialized successfully")

# Initialize Firestore real-time listener
realtime_listener = None
if firestore_client and hasattr(firestore_client, 'db'):
    try:
        realtime_listener = FirestoreRealtimeListener(firestore_client.db, socketio)
        realtime_listener.start()
        logger.info("Firestore real-time listener started successfully")
    except Exception as e:
        logger.error(f"Failed to start Firestore real-time listener: {e}")
else:
    logger.warning("Firestore real-time listener not started (Firestore client not initialized)")

# Security headers
@app.after_request
def add_security_headers(response):
    """Add security headers to all responses"""
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['X-Frame-Options'] = 'SAMEORIGIN'
    response.headers['X-XSS-Protection'] = '1; mode=block'
    response.headers['Referrer-Policy'] = 'strict-origin-when-cross-origin'
    response.headers['Content-Security-Policy'] = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; frame-src 'self' http://127.0.0.1:19999 https://hddm.pqsolutionsperu.com;"
    return response

# Credenciales de login (DEBE configurar ADMIN_PASSWORD_HASH en variables de entorno)
# Para generar un hash: python3 -c "from werkzeug.security import generate_password_hash; print(generate_password_hash('tu_password'))"
# ADVERTENCIA: Si no configuras ADMIN_PASSWORD_HASH, el login quedará DESHABILITADO por seguridad
LOGIN_CREDENTIALS = {
    'pqsowner': os.environ.get('ADMIN_PASSWORD_HASH', generate_password_hash(os.urandom(32).hex()))
}

# Configuración
MOSQUITTO_PASSWD_FILE = '/etc/mosquitto/passwd'
MOSQUITTO_PASSWD_CMD = '/usr/bin/mosquitto_passwd'
SYSTEMCTL_CMD = '/bin/systemctl'

# ============================================================================
# AUTENTICACIÓN - SINGLE SESSION
# ============================================================================

def _generate_device_fingerprint(req):
    """Genera un fingerprint del dispositivo basado en headers HTTP"""
    components = [
        req.headers.get('User-Agent', ''),
        req.headers.get('Accept-Language', ''),
        req.headers.get('Accept-Encoding', ''),
    ]
    return hashlib.sha256('|'.join(components).encode()).hexdigest()[:16]

def _sessions_ref():
    """Referencia a la colección de sesiones en Firestore"""
    return firestore_client.db.collection('hdd-monitor').document('sessions')

def create_firestore_session(username, req):
    """
    Crea una nueva sesión en Firestore.
    Invalida automáticamente cualquier sesión anterior del mismo usuario (single session).
    """
    if not firestore_client:
        return None
    try:
        now = datetime.utcnow()
        session_id = secrets.token_urlsafe(32)
        expires_at = now + timedelta(days=SESSION_LIFETIME_DAYS)

        # 1. Invalidar sesión anterior si existe
        user_ref = _sessions_ref().collection('users').document(username)
        user_doc = user_ref.get()
        if user_doc.exists:
            prev_id = user_doc.to_dict().get('current_session_id')
            if prev_id:
                _sessions_ref().collection('active').document(prev_id).delete()
                logger.info(f"[AUTH] Sesión anterior invalidada para '{username}' (nuevo login desde {req.remote_addr})")

        # 2. Crear nueva sesión activa
        _sessions_ref().collection('active').document(session_id).set({
            'username': username,
            'session_id': session_id,
            'login_time': now,
            'last_active': now,
            'expires_at': expires_at,
            'ip': req.remote_addr,
            'user_agent': req.headers.get('User-Agent', '')[:200],
            'device_fingerprint': _generate_device_fingerprint(req),
        })

        # 3. Registrar sesión actual del usuario
        user_ref.set({'current_session_id': session_id, 'updated_at': now})

        logger.info(f"[AUTH] Nueva sesión creada para '{username}' desde {req.remote_addr}")
        return session_id
    except Exception as e:
        logger.error(f"[AUTH] Error creando sesión en Firestore: {e}")
        return None

def validate_and_renew_session(session_id, username):
    """
    Valida la sesión contra Firestore y renueva su expiración.
    Retorna True si la sesión es válida.
    Si Firestore no está disponible, retorna True (fail-open para garantizar uptime NFPA 72).
    """
    if not firestore_client:
        logger.warning("[AUTH] Firestore no disponible, usando sesión local (modo degradado)")
        return True
    if not session_id or not username:
        return False
    try:
        session_ref = _sessions_ref().collection('active').document(session_id)
        doc = session_ref.get()

        if not doc.exists:
            logger.warning(f"[AUTH] Sesión '{session_id[:8]}...' no encontrada para '{username}'")
            return False

        data = doc.to_dict()

        if data.get('username') != username:
            logger.warning(f"[AUTH] Sesión '{session_id[:8]}...' no pertenece a '{username}'")
            return False

        expires_at = data.get('expires_at')
        if expires_at and datetime.utcnow() > expires_at.replace(tzinfo=None) if hasattr(expires_at, 'tzinfo') else datetime.utcnow() > expires_at:
            session_ref.delete()
            logger.info(f"[AUTH] Sesión expirada eliminada para '{username}'")
            return False

        # Renovar: extender expiración y actualizar last_active
        now = datetime.utcnow()
        session_ref.update({
            'last_active': now,
            'expires_at': now + timedelta(days=SESSION_LIFETIME_DAYS),
        })
        return True
    except Exception as e:
        logger.error(f"[AUTH] Error validando sesión: {e}")
        return True  # Fail-open: no interrumpir monitoreo NFPA 72 por error de red

def invalidate_firestore_session(session_id, username):
    """Elimina la sesión de Firestore (logout explícito)"""
    if not firestore_client or not session_id:
        return
    try:
        _sessions_ref().collection('active').document(session_id).delete()
        _sessions_ref().collection('users').document(username).delete()
        logger.info(f"[AUTH] Logout: sesión eliminada para '{username}'")
    except Exception as e:
        logger.error(f"[AUTH] Error eliminando sesión: {e}")

def _cleanup_expired_sessions():
    """Elimina sesiones expiradas de Firestore (ejecutado periódicamente)"""
    if not firestore_client:
        return
    try:
        now = datetime.utcnow()
        expired = _sessions_ref().collection('active').where('expires_at', '<', now).stream()
        count = sum(1 for doc in expired if not doc.reference.delete() or True)
        if count > 0:
            logger.info(f"[AUTH] Limpieza: {count} sesiones expiradas eliminadas")
    except Exception as e:
        logger.error(f"[AUTH] Error en limpieza de sesiones: {e}")

def _start_session_cleanup_thread():
    """Inicia un thread que limpia sesiones expiradas cada 6 horas"""
    def run():
        import time
        while True:
            time.sleep(6 * 3600)
            _cleanup_expired_sessions()
    t = threading.Thread(target=run, daemon=True, name="session-cleanup")
    t.start()
    logger.info("[AUTH] Thread de limpieza de sesiones iniciado (cada 6h)")

_start_session_cleanup_thread()

def login_required(f):
    """
    Decorator para proteger rutas que requieren autenticación.
    Valida la sesión contra Firestore cada SESSION_CHECK_INTERVAL segundos.
    Si otro dispositivo inició sesión, esta sesión quedará invalidada en Firestore
    y el usuario será redirigido al login en la próxima verificación.
    """
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if not session.get('logged_in'):
            if request.is_json or request.path.startswith('/api/'):
                return jsonify({'success': False, 'error': 'Authentication required'}), 401
            return redirect(url_for('login_page'))

        # Verificar contra Firestore cada 5 minutos (no en cada request)
        now = datetime.now()
        last_check_str = session.get('last_firestore_check')
        needs_check = True
        if last_check_str:
            try:
                last_check = datetime.fromisoformat(last_check_str)
                needs_check = (now - last_check).total_seconds() > SESSION_CHECK_INTERVAL
            except (ValueError, TypeError):
                pass

        if needs_check:
            if not validate_and_renew_session(session.get('session_id'), session.get('username')):
                session.clear()
                if request.is_json or request.path.startswith('/api/'):
                    return jsonify({'success': False, 'error': 'Sesión expirada o iniciada en otro dispositivo'}), 401
                return redirect(url_for('login_page'))
            session['last_firestore_check'] = now.isoformat()

        return f(*args, **kwargs)
    return decorated_function

# ============================================================================
# UTILIDADES
# ============================================================================

def validate_esp32_id(esp32_id):
    """Valida que el ESP32 ID sea hexadecimal de 8 caracteres"""
    if not esp32_id or len(esp32_id) != 8:
        return False
    return bool(re.match(r'^[A-Fa-f0-9]{8}$', esp32_id))

def execute_command(cmd, check=True):
    """Ejecuta un comando del sistema de forma segura"""
    try:
        logger.info(f"Executing command: {' '.join(cmd)}")
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            check=check,
            timeout=10
        )
        logger.info(f"Command output: {result.stdout[:100]}")
        return {
            'success': True,
            'stdout': result.stdout,
            'stderr': result.stderr,
            'returncode': result.returncode
        }
    except subprocess.CalledProcessError as e:
        logger.error(f"Command failed: {e}")
        return {
            'success': False,
            'stdout': e.stdout if hasattr(e, 'stdout') else '',
            'stderr': e.stderr if hasattr(e, 'stderr') else '',
            'returncode': e.returncode,
            'error': str(e)
        }
    except subprocess.TimeoutExpired:
        logger.error("Command timeout")
        return {
            'success': False,
            'error': 'Command timeout'
        }
    except Exception as e:
        logger.error(f"Unexpected error: {e}")
        return {
            'success': False,
            'error': str(e)
        }

def parse_passwd_file():
    """Lee y parsea el archivo /etc/mosquitto/passwd"""
    users = []

    try:
        logger.info("Reading passwd file")
        # Intentar leer directamente (el archivo tiene permisos 644)
        try:
            with open(MOSQUITTO_PASSWD_FILE, 'r') as f:
                content = f.read()
        except PermissionError:
            # Si falla, usar sudo
            result = execute_command(['sudo', 'cat', MOSQUITTO_PASSWD_FILE], check=False)
            if not result['success']:
                logger.error(f"Cannot read passwd file: {result.get('error')}")
                return {'success': False, 'error': 'Cannot read passwd file', 'users': []}
            content = result['stdout']

        for line in content.split('\n'):
            line = line.strip()
            if line and ':' in line:
                username = line.split(':')[0]

                # Clasificar tipo de usuario
                if validate_esp32_id(username):
                    user_type = 'esp32'
                elif username.startswith('esp32_'):
                    user_type = 'service'
                elif username == 'admin_hdd':
                    user_type = 'admin'
                else:
                    user_type = 'other'

                users.append({
                    'username': username,
                    'type': user_type
                })

        logger.info(f"Found {len(users)} users")
        return {'success': True, 'users': users}

    except Exception as e:
        logger.error(f"Error parsing passwd file: {e}")
        return {'success': False, 'error': str(e), 'users': []}

def restart_mosquitto():
    """Reinicia el servicio Mosquitto"""
    result = execute_command(['sudo', SYSTEMCTL_CMD, 'restart', 'mosquitto'])
    return result['success']

# ============================================================================
# RUTAS WEB
# ============================================================================

@app.route('/login')
def login_page():
    """Página de login"""
    # Si ya está autenticado, redirigir al dashboard
    if session.get('logged_in'):
        return redirect(url_for('dashboard'))
    return render_template('login.html')

@app.route('/logout')
def logout():
    """Cerrar sesión"""
    invalidate_firestore_session(session.get('session_id'), session.get('username'))
    session.clear()
    return redirect(url_for('login_page'))

@app.route('/')
@login_required
def index():
    """Redirect to dashboard"""
    return redirect(url_for('dashboard'))

# ============================================================================
# DASHBOARD ROUTES
# ============================================================================

@app.route('/dashboard')
@login_required
def dashboard():
    """Main dashboard page"""
    return render_template('dashboard.html', username=session.get('username', 'admin'))

@app.route('/vm-monitoring')
@login_required
def vm_monitoring():
    """VM monitoring page with Netdata iframe"""
    return render_template('vm_monitoring.html', username=session.get('username', 'admin'))

@app.route('/esp32-devices')
@login_required
def esp32_devices():
    """ESP32 devices monitoring page"""
    return render_template('esp32_devices.html', username=session.get('username', 'admin'))

@app.route('/clients')
@login_required
def clients_panels():
    """Clients and panels management page"""
    return render_template('clients.html', username=session.get('username', 'admin'))

@app.route('/events')
@login_required
def events():
    """Events management page"""
    return render_template('events.html', username=session.get('username', 'admin'))

@app.route('/mqtt-config')
@login_required
def mqtt_config():
    """MQTT configuration page (formerly index.html)"""
    return render_template('mqtt_config.html', username=session.get('username', 'admin'))

@app.route('/health')
@login_required
def health_dashboard():
    """ESP32 Health Monitor Dashboard"""
    return render_template('health.html', username=session.get('username', 'admin'))

# ============================================================================
# DASHBOARD API ENDPOINTS
# ============================================================================

@app.route('/api/dashboard/metrics', methods=['GET'])
@login_required
def get_dashboard_metrics():
    """GET - Get dashboard overview metrics"""
    if not firestore_client:
        logger.error("Firestore client not initialized - check GOOGLE_APPLICATION_CREDENTIALS")
        return jsonify({
            'success': False,
            'error': 'Firestore client not initialized. Check service account credentials.'
        }), 503

    try:
        logger.info("Fetching dashboard metrics from Firestore...")
        metrics = firestore_client.get_dashboard_metrics()
        logger.info(f"Successfully fetched metrics: {metrics}")
        return jsonify({
            'success': True,
            'metrics': metrics
        })
    except Exception as e:
        logger.error(f"Error getting dashboard metrics: {e}", exc_info=True)
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/esp32/devices', methods=['GET'])
@login_required
def get_esp32_devices():
    """GET - Get all ESP32 devices"""
    if not firestore_client:
        return jsonify({
            'success': False,
            'error': 'Firestore client not initialized'
        }), 503

    try:
        devices = firestore_client.get_all_esp32_devices()
        return jsonify({
            'success': True,
            'devices': devices,
            'total': len(devices)
        })
    except Exception as e:
        logger.error(f"Error getting ESP32 devices: {e}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/esp32/devices/<device_id>', methods=['GET'])
@login_required
def get_esp32_device(device_id):
    """GET - Get specific ESP32 device details"""
    if not firestore_client:
        return jsonify({
            'success': False,
            'error': 'Firestore client not initialized'
        }), 503

    try:
        device = firestore_client.get_device_by_id(device_id)
        if device:
            return jsonify({
                'success': True,
                'device': device
            })
        else:
            return jsonify({
                'success': False,
                'error': 'Device not found'
            }), 404
    except Exception as e:
        logger.error(f"Error getting ESP32 device {device_id}: {e}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/esp32/devices/<device_id>', methods=['DELETE'])
@csrf.exempt
@login_required
def delete_esp32_device(device_id):
    """DELETE - Remove an ESP32 device and its associated panels/relays from Firestore"""
    if not firestore_client:
        return jsonify({'success': False, 'error': 'Firestore client not initialized'}), 503
    try:
        result = firestore_client.delete_device_with_panels(device_id)
        if result['success']:
            return jsonify({
                'success': True,
                'device_id': device_id,
                'deleted_panels': result['deleted_panels']
            })
        else:
            return jsonify({'success': False, 'error': result.get('error', 'Failed to delete device')}), 500
    except Exception as e:
        logger.error(f"Error deleting ESP32 device {device_id}: {e}")
        return jsonify({'success': False, 'error': str(e)}), 500

@app.route('/api/esp32/devices/<device_id>/test-mode', methods=['POST'])
@csrf.exempt
@login_required
def set_esp32_test_mode(device_id):
    """POST - Mark or unmark an ESP32 device as a test device"""
    if not firestore_client:
        return jsonify({
            'success': False,
            'error': 'Firestore client not initialized'
        }), 503

    try:
        body = request.get_json(silent=True) or {}
        is_test = bool(body.get('test_device', False))
        ok = firestore_client.set_test_device(device_id, is_test)
        if ok:
            return jsonify({'success': True, 'device_id': device_id, 'test_device': is_test})
        else:
            return jsonify({'success': False, 'error': 'Failed to update device'}), 500
    except Exception as e:
        logger.error(f"Error setting test mode for {device_id}: {e}")
        return jsonify({'success': False, 'error': str(e)}), 500

@app.route('/api/clients', methods=['GET'])
@login_required
def get_clients():
    """GET - Get all clients"""
    if not firestore_client:
        return jsonify({
            'success': False,
            'error': 'Firestore client not initialized'
        }), 503

    try:
        clients = firestore_client.get_all_clients()
        return jsonify({
            'success': True,
            'clients': clients,
            'total': len(clients)
        })
    except Exception as e:
        logger.error(f"Error getting clients: {e}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/clients/<client_id>/panels', methods=['GET'])
@login_required
def get_client_panels(client_id):
    """GET - Get all panels for a client"""
    if not firestore_client:
        return jsonify({
            'success': False,
            'error': 'Firestore client not initialized'
        }), 503

    try:
        panels = firestore_client.get_client_panels(client_id)
        return jsonify({
            'success': True,
            'panels': panels,
            'total': len(panels)
        })
    except Exception as e:
        logger.error(f"Error getting panels for client {client_id}: {e}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/panels/<client_id>/<panel_id>/relays', methods=['GET'])
@login_required
def get_panel_relays(client_id, panel_id):
    """GET - Get all relays for a panel"""
    if not firestore_client:
        return jsonify({
            'success': False,
            'error': 'Firestore client not initialized'
        }), 503

    try:
        relays = firestore_client.get_panel_relays(client_id, panel_id)
        return jsonify({
            'success': True,
            'relays': relays,
            'total': len(relays)
        })
    except Exception as e:
        logger.error(f"Error getting relays for panel {panel_id}: {e}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/events/<client_id>', methods=['GET'])
@login_required
def get_client_events(client_id):
    """GET - Get events for a client"""
    if not firestore_client:
        return jsonify({
            'success': False,
            'error': 'Firestore client not initialized'
        }), 503

    try:
        status = request.args.get('status')
        limit = int(request.args.get('limit', 100))

        events = firestore_client.get_client_events(client_id, status=status, limit=limit)
        return jsonify({
            'success': True,
            'events': events,
            'total': len(events)
        })
    except Exception as e:
        logger.error(f"Error getting events for client {client_id}: {e}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

# ============================================================================
# API REST
# ============================================================================

@app.route('/api/auth/login', methods=['POST'])
@csrf.exempt
def login():
    """POST - Autenticar usuario"""
    data = request.get_json()

    if not data or 'username' not in data or 'password' not in data:
        return jsonify({
            'success': False,
            'error': 'Missing username or password'
        }), 400

    username = data['username'].strip()
    password = data['password']

    # Verificar credenciales
    if username in LOGIN_CREDENTIALS:
        password_hash = LOGIN_CREDENTIALS[username]
        if check_password_hash(password_hash, password):
            # Crear sesión en Firestore (invalida la anterior automáticamente)
            session_id = create_firestore_session(username, request)

            session.permanent = True
            session['logged_in'] = True
            session['username'] = username
            session['login_time'] = datetime.now().isoformat()
            session['session_id'] = session_id
            session['last_firestore_check'] = datetime.now().isoformat()

            logger.info(f"[AUTH] Login exitoso: '{username}' desde {request.remote_addr}")
            return jsonify({
                'success': True,
                'message': 'Login successful',
                'username': username
            })

    logger.warning(f"[AUTH] Intento fallido: '{username}' desde {request.remote_addr}")
    return jsonify({
        'success': False,
        'error': 'Invalid credentials'
    }), 401

@app.route('/api/auth/check', methods=['GET'])
def check_auth():
    """GET - Verificar si está autenticado"""
    return jsonify({
        'success': True,
        'authenticated': session.get('logged_in', False),
        'username': session.get('username')
    })

@app.route('/api/auth/heartbeat', methods=['POST'])
@csrf.exempt
@login_required
def heartbeat():
    """
    POST - Mantiene la sesión activa desde el frontend en background.
    El frontend llama a este endpoint cada 10 minutos silenciosamente.
    Esto garantiza que la sesión nunca expire mientras el app está activo,
    independientemente de si el usuario interactúa con la interfaz.
    """
    return jsonify({
        'success': True,
        'timestamp': datetime.now().isoformat(),
        'session_renewed': True
    })

@app.route('/api/mqtt/users', methods=['GET'])
@login_required
def get_users():
    """GET - Lista todos los usuarios MQTT"""
    result = parse_passwd_file()

    if not result['success']:
        logger.error(f"Failed to get users: {result.get('error')}")
        return jsonify({
            'success': False,
            'error': result.get('error', 'Unknown error')
        }), 500

    return jsonify({
        'success': True,
        'users': result['users'],
        'total': len(result['users'])
    })

@app.route('/api/mqtt/users', methods=['POST'])
@csrf.exempt
@login_required
def create_user():
    """POST - Crea un nuevo usuario MQTT (ESP32)"""
    data = request.get_json()

    if not data or 'esp32_id' not in data:
        return jsonify({
            'success': False,
            'error': 'Missing esp32_id parameter'
        }), 400

    esp32_id = data['esp32_id'].upper().strip()

    # Validar formato
    if not validate_esp32_id(esp32_id):
        return jsonify({
            'success': False,
            'error': 'Invalid ESP32 ID format. Must be 8 hexadecimal characters (e.g., 42A8ACA0)'
        }), 400

    # Verificar si ya existe
    users_result = parse_passwd_file()
    if users_result['success']:
        existing_usernames = [u['username'] for u in users_result['users']]
        if esp32_id in existing_usernames:
            return jsonify({
                'success': False,
                'error': f'User {esp32_id} already exists'
            }), 409

    # Crear usuario (username = password = ESP32_ID)
    cmd = ['sudo', MOSQUITTO_PASSWD_CMD, '-b', MOSQUITTO_PASSWD_FILE, esp32_id, esp32_id]
    result = execute_command(cmd)

    if not result['success']:
        return jsonify({
            'success': False,
            'error': f'Failed to create user: {result.get("error", "Unknown error")}'
        }), 500

    # Reiniciar Mosquitto
    if not restart_mosquitto():
        return jsonify({
            'success': False,
            'error': 'User created but failed to restart Mosquitto'
        }), 500

    return jsonify({
        'success': True,
        'message': f'User {esp32_id} created successfully',
        'username': esp32_id
    }), 201

@app.route('/api/mqtt/users/<username>', methods=['DELETE'])
@csrf.exempt
@login_required
def delete_user(username):
    """DELETE - Elimina un usuario MQTT"""
    username = username.upper().strip()

    # Proteger usuarios del sistema
    protected_users = ['mqtt_firestore_handler', 'esp32_config_manager', 'esp32_devices', 'admin_hdd']
    if username in protected_users:
        return jsonify({
            'success': False,
            'error': f'Cannot delete protected system user: {username}'
        }), 403

    # Verificar que existe
    users_result = parse_passwd_file()
    if not users_result['success']:
        return jsonify({
            'success': False,
            'error': 'Failed to read users'
        }), 500

    existing_usernames = [u['username'] for u in users_result['users']]
    if username not in existing_usernames:
        return jsonify({
            'success': False,
            'error': f'User {username} not found'
        }), 404

    # Eliminar usuario usando mosquitto_passwd -D
    cmd = ['sudo', MOSQUITTO_PASSWD_CMD, '-D', MOSQUITTO_PASSWD_FILE, username]
    result = execute_command(cmd)

    if not result['success']:
        return jsonify({
            'success': False,
            'error': f'Failed to delete user: {result.get("error", "Unknown error")}'
        }), 500

    # Reiniciar Mosquitto
    if not restart_mosquitto():
        return jsonify({
            'success': False,
            'error': 'User deleted but failed to restart Mosquitto'
        }), 500

    # Eliminar de Firestore si es un ESP32 (ID hexadecimal de 8 chars)
    if firestore_client and validate_esp32_id(username):
        try:
            firestore_client.delete_device(username)
            logger.info(f"ESP32 {username} eliminado de Firestore")
        except Exception as e:
            logger.error(f"Usuario MQTT eliminado pero falló en Firestore: {e}")

    return jsonify({
        'success': True,
        'message': f'User {username} deleted successfully'
    })

@app.route('/api/mqtt/status', methods=['GET'])
@login_required
def get_status():
    """GET - Estado del broker Mosquitto"""
    result = execute_command(['sudo', SYSTEMCTL_CMD, 'is-active', 'mosquitto'], check=False)

    is_active = result.get('success', False) and result.get('stdout', '').strip() == 'active'

    return jsonify({
        'success': True,
        'broker': {
            'status': 'running' if is_active else 'stopped',
            'active': is_active,
            'timestamp': datetime.now().isoformat()
        }
    })

@app.route('/api/health', methods=['GET'])
def health_check():
    """Health check endpoint"""
    return jsonify({
        'success': True,
        'service': 'mqtt-manager',
        'status': 'healthy',
        'timestamp': datetime.now().isoformat()
    })

# ============================================================================
# ESP32 HEALTH MONITORING API
# ============================================================================

@app.route('/api/esp32/health/status', methods=['GET'])
@login_required
def get_esp32_health_status():
    """Get health status for all ESP32 devices"""
    if not health_system:
        return jsonify({
            'success': False,
            'error': 'Health monitoring system not initialized'
        }), 503

    try:
        monitor = health_system.get_health_monitor()
        all_status = monitor.get_all_device_status()

        return jsonify({
            'success': True,
            'devices': [
                {
                    'esp32_id': s.esp32_id,
                    'is_healthy': s.is_healthy,
                    'last_heartbeat': s.last_heartbeat.isoformat() if s.last_heartbeat else None,
                    'total_alerts': s.total_alerts,
                    'critical_alerts_24h': s.critical_alerts_24h,
                    'consecutive_failures': s.consecutive_failures,
                    'uptime_ms': s.uptime_ms,
                    'last_alert': {
                        'status': s.last_alert.status,
                        'issue_type': s.last_alert.issue_type,
                        'description': s.last_alert.description,
                        'timestamp': s.last_alert.timestamp.isoformat()
                    } if s.last_alert else None
                }
                for s in all_status
            ]
        })
    except Exception as e:
        logger.error(f"Error getting ESP32 health status: {e}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/esp32/health/alerts/<esp32_id>', methods=['GET'])
@login_required
def get_esp32_alerts(esp32_id):
    """Get alert history for specific ESP32 device"""
    if not health_system:
        return jsonify({
            'success': False,
            'error': 'Health monitoring system not initialized'
        }), 503

    try:
        limit = int(request.args.get('limit', 50))
        monitor = health_system.get_health_monitor()
        alerts = monitor.get_device_alerts(esp32_id, limit=limit)

        return jsonify({
            'success': True,
            'esp32_id': esp32_id,
            'alerts': [
                {
                    'status': a.status,
                    'issue_type': a.issue_type,
                    'description': a.description,
                    'uptime_ms': a.uptime_ms,
                    'free_heap': a.free_heap,
                    'min_free_heap': a.min_free_heap,
                    'boot_count': a.boot_count,
                    'timestamp': a.timestamp.isoformat(),
                    'server_received_at': a.server_received_at.isoformat()
                }
                for a in alerts
            ]
        })
    except Exception as e:
        logger.error(f"Error getting alerts for {esp32_id}: {e}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

@app.route('/api/esp32/health/statistics', methods=['GET'])
@login_required
def get_health_statistics():
    """Get overall health statistics"""
    if not health_system:
        return jsonify({
            'success': False,
            'error': 'Health monitoring system not initialized'
        }), 503

    try:
        monitor = health_system.get_health_monitor()
        stats = monitor.get_statistics()
        unhealthy = monitor.get_unhealthy_devices()

        return jsonify({
            'success': True,
            'statistics': stats,
            'unhealthy_devices': [
                {
                    'esp32_id': d.esp32_id,
                    'critical_alerts_24h': d.critical_alerts_24h,
                    'consecutive_failures': d.consecutive_failures,
                    'last_issue': d.last_alert.issue_type if d.last_alert else None
                }
                for d in unhealthy
            ]
        })
    except Exception as e:
        logger.error(f"Error getting health statistics: {e}")
        return jsonify({
            'success': False,
            'error': str(e)
        }), 500

# ============================================================================
# SOCKETIO EVENT HANDLERS
# ============================================================================

@socketio.on('connect')
def handle_connect():
    """Handle client connection"""
    logger.info(f"Client connected: {request.sid}")
    emit('connection_established', {
        'message': 'Connected to HDD Monitor real-time updates',
        'timestamp': datetime.now().isoformat()
    })

@socketio.on('disconnect')
def handle_disconnect():
    """Handle client disconnection"""
    logger.info(f"Client disconnected: {request.sid}")

@socketio.on('request_initial_data')
def handle_initial_data_request():
    """Send initial data to newly connected client"""
    if not firestore_client:
        emit('error', {'message': 'Firestore not available'})
        return

    try:
        # Send dashboard metrics
        metrics = firestore_client.get_dashboard_metrics()
        emit('initial_metrics', _sanitize_firestore_data(metrics))

        # Send ESP32 devices
        devices = firestore_client.get_all_esp32_devices()
        emit('initial_esp32_devices', _sanitize_firestore_data({'devices': devices}))

        logger.info(f"Sent initial data to client {request.sid}")
    except Exception as e:
        logger.error(f"Error sending initial data: {e}")
        emit('error', {'message': str(e)})

# ============================================================================
# ERROR HANDLERS
# ============================================================================

@app.errorhandler(404)
def not_found(e):
    return jsonify({
        'success': False,
        'error': 'Endpoint not found'
    }), 404

@app.errorhandler(500)
def internal_error(e):
    logger.error(f"Internal error: {e}")
    return jsonify({
        'success': False,
        'error': 'Internal server error'
    }), 500

# ============================================================================
# MAIN
# ============================================================================

if __name__ == '__main__':
    # Solo para desarrollo - En producción usar Gunicorn con eventlet
    socketio.run(app, debug=True, host='127.0.0.1', port=5000)
