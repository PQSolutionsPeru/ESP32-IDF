from flask import Flask, request
from werkzeug.utils import secure_filename
import os

app = Flask(__name__)

BASE_FOLDER = '/home/pqsolutionsperu/esp32_log'
os.makedirs(BASE_FOLDER, exist_ok=True)

@app.route('/upload', methods=['POST'])
def upload():
    if 'file' not in request.files:
        return 'No file part', 400

    esp32_id = request.form.get('esp32_id')
    if not esp32_id:
        return 'ESP32_ID missing', 400

    device_folder = os.path.join(BASE_FOLDER, secure_filename(esp32_id))
    os.makedirs(device_folder, exist_ok=True)

    files = request.files.getlist('file')
    for f in files:
        if f.filename:
            filename = secure_filename(f.filename)
            f.save(os.path.join(device_folder, filename))
    return 'Files uploaded', 200

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8080)
