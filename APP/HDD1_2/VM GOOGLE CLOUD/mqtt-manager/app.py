#!/usr/bin/env python3
"""
MQTT Manager Web Interface
Flask API para gestionar usuarios MQTT en Mosquitto
"""

from flask import Flask, render_template, jsonify, request, session, redirect, url_for
from flask_cors import CORS
from flask_wtf.csrf import CSRFProtect
from werkzeug.security import check_password_hash, generate_password_hash
from functools import wraps
import subprocess
import re
import os
from datetime import datetime, timedelta
import logging

# Import modules
from modules.firestore_client import FirestoreClient
from modules.cache import SimpleCache
from modules.health_system import HealthMonitoringSystem
from modules.email_alerter import EmailConfig

# Configurar logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

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
app.config['SECRET_KEY'] = os.environ.get('FLASK_SECRET_KEY', os.urandom(24).hex())
app.config['PERMANENT_SESSION_LIFETIME'] = timedelta(hours=24)
app.config['WTF_CSRF_TIME_LIMIT'] = None  # No expiration for CSRF tokens

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
# AUTENTICACIÓN
# ============================================================================

def login_required(f):
    """Decorator para proteger rutas que requieren autenticación"""
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if 'logged_in' not in session or not session['logged_in']:
            if request.is_json or request.path.startswith('/api/'):
                return jsonify({
                    'success': False,
                    'error': 'Authentication required'
                }), 401
            return redirect(url_for('login_page'))
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
        return jsonify({
            'success': False,
            'error': 'Firestore client not initialized'
        }), 503

    try:
        metrics = firestore_client.get_dashboard_metrics()
        return jsonify({
            'success': True,
            'metrics': metrics
        })
    except Exception as e:
        logger.error(f"Error getting dashboard metrics: {e}")
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
            session.permanent = True
            session['logged_in'] = True
            session['username'] = username
            session['login_time'] = datetime.now().isoformat()

            logger.info(f"Successful login: {username}")
            return jsonify({
                'success': True,
                'message': 'Login successful',
                'username': username
            })

    logger.warning(f"Failed login attempt: {username}")
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
    # Solo para desarrollo - En producción usar Gunicorn
    app.run(debug=True, host='127.0.0.1', port=5000)
