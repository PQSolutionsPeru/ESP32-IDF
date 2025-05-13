import asyncio
import uos
import network
from machine import reset
import esp
import json
import gc
import _thread
from esp32 import NVS
import sys
import time

nvs = NVS('config')
output_queue = []

def guardar_credenciales(ssid, password):
    print(f"Guardando credenciales: SSID={ssid}, Password={password}")
    ssid_blob = ssid.encode()
    password_blob = password.encode()
    nvs.set_blob('wifi_ssid', ssid_blob)
    nvs.set_blob('wifi_password', password_blob)
    nvs.commit()
    print("Credenciales guardadas en NVS")

def leer_credenciales():
    try:
        ssid_blob = bytearray(32)
        password_blob = bytearray(64)
        nvs.get_blob('wifi_ssid', ssid_blob)
        nvs.get_blob('wifi_password', password_blob)
        ssid = ssid_blob.decode('utf-8').rstrip('\x00')
        password = password_blob.decode('utf-8').rstrip('\x00')
        print(f"Credenciales leídas: SSID={ssid}, Password={password}")
    except Exception as e:
        print(f"Error leyendo credenciales: {e}")
        ssid = None
        password = None
    return ssid, password

async def conectar_wifi():
    ssid, password = leer_credenciales()
    if (ssid and password) and (ssid.strip() and password.strip()):
        station = network.WLAN(network.STA_IF)
        station.active(True)
        station.connect(ssid, password)
        for _ in range(20):
            if station.isconnected():
                print('Conectado a la red WiFi')
                print(f"Dirección IP: {station.ifconfig()[0]}")
                return True
            await asyncio.sleep(1)
    print('No se pudo conectar a la red WiFi')
    return False

async def modo_ap():
    ap = network.WLAN(network.AP_IF)
    ap.active(True)
    ap.config(essid='ESP32_Config')
    print('Modo AP activado, ESSID=ESP32_Config')
    return ap

threads = {}

def simular_counter():
    for i in range(100):
        print(i)
        time.sleep(1)

def iniciar_archivo(nombre_archivo):
    try:
        global threads
        if nombre_archivo in threads:
            print(f"{nombre_archivo} ya está en ejecución")
            return

        def run_script():
            print(f"Ejecutando {nombre_archivo}")
            simular_counter()
            print(f"Finalizó {nombre_archivo}")

        thread = _thread.start_new_thread(run_script, ())
        threads[nombre_archivo] = thread
        print(f"{nombre_archivo} iniciado en un nuevo hilo")
    except Exception as e:
        print(f"Error al iniciar {nombre_archivo}: {e}")

def detener_archivo(nombre_archivo):
    global threads
    if nombre_archivo in threads:
        _thread.exit()
        del threads[nombre_archivo]
        print(f"{nombre_archivo} detenido")
    else:
        print(f"{nombre_archivo} no está en ejecución")

async def mostrar_salida():
    while True:
        while output_queue:
            print(output_queue.pop(0), end='')
        await asyncio.sleep(1)

async def iniciar_servidor():
    async def manejar_solicitudes(reader, writer):
        request = await reader.read(1024)
        request = request.decode('utf-8')
        print(f"Solicitud recibida: {request}")
        response = ''
        if 'GET / ' in request or 'GET /index.html' in request:
            response = HTML_PAGE
        elif 'POST /guardar' in request:
            content_length = int([line for line in request.split('\r\n') if 'Content-Length:' in line][0].split(':')[1])
            body = request.split('\r\n\r\n')[1][:content_length]
            params = {k: v for k, v in (x.split('=') for x in body.split('&'))}
            guardar_credenciales(params['ssid'], params['password'])
            response = 'HTTP/1.1 303 See Other\r\nLocation: /\r\n\r\n'
            await writer.drain()
            print("Reiniciando ESP32 después de guardar credenciales")
            reset()
        elif 'GET /reiniciar' in request:
            response = 'HTTP/1.1 200 OK\r\n\r\nReiniciando ESP32...'
            writer.write(response.encode())
            await writer.drain()
            print("Reiniciando ESP32 por solicitud del usuario")
            reset()
        elif 'GET /listar' in request:
            archivos = uos.listdir()
            response = json.dumps({'archivos': archivos})
            response = f'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{response}'
        elif 'POST /subir' in request:
            try:
                content_length = int([line for line in request.split('\r\n') if 'Content-Length:' in line][0].split(':')[1])
                body = request.split('\r\n\r\n', 1)[1]
                boundary = request.split('boundary=')[1].split('\r\n')[0]
                parts = body.split('--' + boundary)
                for part in parts:
                    if 'Content-Disposition: form-data; name="file"; filename=' in part:
                        filename = part.split('filename=')[1].split('\r\n')[0].strip('"')
                        file_content = part.split('\r\n\r\n')[1].rsplit('\r\n', 1)[0]
                        with open(filename, 'wb') as f:
                            f.write(file_content.encode('latin1'))
                        response = 'HTTP/1.1 303 See Other\r\nLocation: /\r\n\r\n'
                        print(f"Archivo {filename} subido exitosamente")
                        break
            except Exception as e:
                response = f'HTTP/1.1 500 Internal Server Error\r\n\r\nError subiendo archivo: {e}'
                print(f"Error subiendo archivo: {e}")
            writer.write(response.encode())
            await writer.drain()
            await writer.wait_closed()
            return
        elif 'GET /descargar/' in request:
            filename = request.split('GET /descargar/')[1].split(' ')[0]
            with open(filename, 'rb') as f:
                file_content = f.read()
            response = f'HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n\r\n'
            writer.write(response.encode() + file_content)
            await writer.drain()
            return
        elif 'GET /eliminar/' in request:
            filename = request.split('GET /eliminar/')[1].split(' ')[0]
            uos.remove(filename)
            response = 'HTTP/1.1 303 See Other\r\nLocation: /\r\n\r\n'
        elif 'POST /control' in request:
            content_length = int([line for line in request.split('\r\n') if 'Content-Length:' in line][0].split(':')[1])
            body = request.split('\r\n\r\n')[1][:content_length]
            params = {k: v for k, v in (x.split('=') for x in body.split('&'))}
            accion = params['accion']
            archivo = params['archivo']
            if accion == 'iniciar':
                print(f"Iniciando {archivo}")
                iniciar_archivo(archivo)
                response = f'HTTP/1.1 200 OK\r\n\r\n{archivo} Iniciado'
            elif accion == 'detener':
                print(f"Deteniendo {archivo}")
                detener_archivo(archivo)
                response = f'HTTP/1.1 200 OK\r\n\r\n{archivo} Detenido'
        elif 'GET /estado' in request:
            gc.collect()
            memoria_libre = gc.mem_free()
            espacio_libre = uos.statvfs('/')[3] * uos.statvfs('/')[0]
            response = json.dumps({'memoria': memoria_libre, 'espacio_libre': espacio_libre})
            response = f'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{response}'
        else:
            response = 'HTTP/1.1 404 Not Found\r\n\r\n'
        writer.write(response.encode())
        await writer.drain()
        await writer.wait_closed()

    server = await asyncio.start_server(manejar_solicitudes, '0.0.0.0', 80)
    print('Servidor web iniciado en el puerto 80')
    asyncio.create_task(mostrar_salida())
    while True:
        await asyncio.sleep(3600)

HTML_PAGE = """\
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Configuración del ESP32</title>
</head>
<body>
    <h1>Configuración del ESP32</h1>
    <form action="/guardar" method="post">
        <label for="ssid">SSID:</label>
        <input type="text" id="ssid" name="ssid" required>
        <label for="password">Contraseña:</label>
        <input type="password" id="password" name="password" required>
        <button type="submit">Guardar y Conectar</button>
    </form>
    <form action="/reiniciar" method="get">
        <button type="submit">Reiniciar ESP32</button>
    </form>
    <h2>Subir Archivo</h2>
    <form action="/subir" method="post" enctype="multipart/form-data">
        <input type="file" name="file" required>
        <button type="submit">Subir</button>
    </form>
    <h2>Lista de Archivos</h2>
    <ul id="file-list"></ul>
    <h2>Controlar Programa Principal</h2>
    <form action="/control" method="post">
        <label for="archivo">Archivo:</label>
        <select id="archivo" name="archivo">
            <script>
                async function listarArchivos() {
                    const response = await fetch('/listar');
                    const archivos = await response.json();
                    const archivoSelect = document.getElementById('archivo');
                    archivoSelect.innerHTML = '';
                    archivos.archivos.forEach(file => {
                        const option = document.createElement('option');
                        option.value = file;
                        option.textContent = file;
                        archivoSelect.appendChild(option);
                    });
                }
                listarArchivos();
            </script>
        </select>
        <button type="submit" name="accion" value="iniciar">Iniciar Archivo</button>
        <button type="submit" name="accion" value="detener">Detener Archivo</button>
    </form>
    <h2>Estado del ESP32</h2>
    <button onclick="getEstado()">Ver Estado</button>
    <p id="estado"></p>
    <script>
        async function getEstado() {
            const response = await fetch('/estado');
            const estado = await response.json();
            document.getElementById('estado').innerText = `Memoria Libre: ${estado.memoria} bytes, Espacio Libre: ${estado.espacio_libre} bytes`;
        }

        async function listarArchivos() {
            const response = await fetch('/listar');
            const archivos = await response.json();
            const fileList = document.getElementById('file-list');
            fileList.innerHTML = '';
            archivos.archivos.forEach(file => {
                const li = document.createElement('li');
                li.textContent = file;
                const downloadLink = document.createElement('a');
                downloadLink.href = `/descargar/${file}`;
                downloadLink.textContent = ' Descargar';
                li.appendChild(downloadLink);
                const deleteLink = document.createElement('a');
                deleteLink.href = `/eliminar/${file}`;
                deleteLink.textContent = ' Eliminar';
                fileList.appendChild(li);
            });
        }

        listarArchivos();
    </script>
</body>
</html>
"""

async def main():
    print("Iniciando...")
    if not await conectar_wifi():
        await modo_ap()
    await iniciar_servidor()

asyncio.run(main())
