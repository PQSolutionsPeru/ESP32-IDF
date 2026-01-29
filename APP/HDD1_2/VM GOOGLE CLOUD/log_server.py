from flask import Flask, request, jsonify
from werkzeug.utils import secure_filename
import os
import logging
from datetime import datetime

# Configurar logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

app = Flask(__name__)

BASE_FOLDER = '/home/pqsolutions/esp32_log'

# Crear carpeta base si no existe
try:
    os.makedirs(BASE_FOLDER, exist_ok=True)
    logger.info(f"Base folder initialized: {BASE_FOLDER}")
except Exception as e:
    logger.error(f"Failed to create base folder: {e}")

@app.route('/upload', methods=['POST'])
def upload():
    try:
        # Validar que venga el archivo
        if 'file' not in request.files:
            logger.warning("Upload request without 'file' field")
            return jsonify({'error': 'No file part in request'}), 400

        # Validar ESP32_ID
        esp32_id = request.form.get('esp32_id')
        if not esp32_id:
            logger.warning("Upload request without 'esp32_id'")
            return jsonify({'error': 'ESP32_ID missing'}), 400

        logger.info(f"Processing upload from ESP32: {esp32_id}")

        # Crear carpeta del dispositivo
        device_folder = os.path.join(BASE_FOLDER, secure_filename(esp32_id))
        try:
            os.makedirs(device_folder, exist_ok=True)
            logger.debug(f"Device folder ready: {device_folder}")
        except Exception as e:
            logger.error(f"Failed to create device folder {device_folder}: {e}")
            return jsonify({'error': f'Failed to create device folder: {str(e)}'}), 500

        # Procesar archivos
        files = request.files.getlist('file')
        uploaded_files = []

        for f in files:
            if f.filename:
                filename = secure_filename(f.filename)
                filepath = os.path.join(device_folder, filename)

                try:
                    f.save(filepath)
                    file_size = os.path.getsize(filepath)
                    uploaded_files.append(filename)
                    logger.info(f"Saved file: {filename} ({file_size} bytes) for ESP32: {esp32_id}")
                except PermissionError as e:
                    logger.error(f"Permission denied saving {filename}: {e}")
                    return jsonify({'error': f'Permission denied: {str(e)}'}), 500
                except Exception as e:
                    logger.error(f"Failed to save file {filename}: {e}")
                    return jsonify({'error': f'Failed to save file: {str(e)}'}), 500

        if not uploaded_files:
            logger.warning(f"No files uploaded for ESP32: {esp32_id}")
            return jsonify({'error': 'No valid files provided'}), 400

        logger.info(f"Successfully uploaded {len(uploaded_files)} file(s) from ESP32: {esp32_id}")
        return jsonify({
            'message': 'Files uploaded successfully',
            'files': uploaded_files,
            'count': len(uploaded_files)
        }), 200

    except Exception as e:
        logger.error(f"Unexpected error in upload endpoint: {e}", exc_info=True)
        return jsonify({'error': f'Internal server error: {str(e)}'}), 500

@app.route('/health', methods=['GET'])
def health():
    """Health check endpoint"""
    return jsonify({
        'status': 'healthy',
        'service': 'ESP32 Log Server',
        'timestamp': datetime.now().isoformat(),
        'base_folder': BASE_FOLDER
    }), 200

@app.route('/', methods=['GET'])
def index():
    """Root endpoint"""
    return jsonify({
        'service': 'ESP32 Log Server',
        'version': '1.1.0',
        'endpoints': {
            'upload': 'POST /upload',
            'health': 'GET /health'
        }
    }), 200

if __name__ == '__main__':
    logger.info("Starting ESP32 Log Server on port 8080")
    app.run(host='0.0.0.0', port=8080)
