#!/usr/bin/env python3
"""
MQTT Manager Web Interface
Flask API para gestionar usuarios MQTT en Mosquitto
"""

from flask import Flask, render_template, jsonify, request
from flask_cors import CORS
import subprocess
import re
import os
from datetime import datetime
import logging

# Configurar logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Flask(__name__)
CORS(app)

# Configuración
MOSQUITTO_PASSWD_FILE = '/etc/mosquitto/passwd'
MOSQUITTO_PASSWD_CMD = '/usr/bin/mosquitto_passwd'
SYSTEMCTL_CMD = '/bin/systemctl'

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

@app.route('/')
def index():
    """Página principal - Interfaz web"""
    return render_template('index.html')

# ============================================================================
# API REST
# ============================================================================

@app.route('/api/mqtt/users', methods=['GET'])
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
