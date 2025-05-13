import usocket as socket
import network
import gc
import ujson
import uos
import utime
import sys
from machine import Pin, reset

# Configuración de pines
led = Pin(2, Pin.OUT)

# Configuración de archivo de credenciales
CREDENTIALS_FILE = "credentials.txt"

def create_credentials_file():
    try:
        with open(CREDENTIALS_FILE, "r"):
            pass
    except OSError:
        with open(CREDENTIALS_FILE, "w"):
            pass

def save_credentials(ssid, password):
    with open(CREDENTIALS_FILE, "w") as f:
        f.write(f"{ssid}\n{password}")

def read_credentials():
    try:
        with open(CREDENTIALS_FILE, "r") as f:
            ssid = f.readline().strip()
            password = f.readline().strip()
            if ssid and password:
                print("Credenciales leídas del archivo:")
                print("SSID:", ssid)
                print("Password:", password)
                return ssid, password
            else:
                print("No se encontraron credenciales válidas en el archivo")
                return None, None
    except OSError as e:
        print("Error al leer las credenciales del archivo:", e)
        return None, None

def start_ap_mode():
    ap = network.WLAN(network.AP_IF)
    ap.active(True)
    ap.config(essid="ESP32-AP", password="12345678")
    while not ap.active():
        pass
    print("Modo AP iniciado")
    print("Dirección IP del AP:", ap.ifconfig()[0])

def connect_wifi(ssid, password):
    sta_if = network.WLAN(network.STA_IF)
    sta_if.active(True)
    if not sta_if.isconnected():
        print(f"Conectando a la red WiFi: {ssid}")
        sta_if.connect(ssid, password)
        start_time = utime.ticks_ms()
        while not sta_if.isconnected() and utime.ticks_diff(utime.ticks_ms(), start_time) < 10000:
            pass
        if sta_if.isconnected():
            print(f"Conectado a la red WiFi: {ssid}")
            print("Dirección IP:", sta_if.ifconfig()[0])
        else:
            print(f"No se pudo conectar a la red WiFi: {ssid}")
            start_ap_mode()
    else:
        print(f"Ya conectado a la red WiFi: {ssid}")

def send_response(conn, status_code, content_type, content):
    response = f"HTTP/1.1 {status_code} OK\r\nContent-Type: {content_type}\r\nContent-Length: {len(content)}\r\n\r\n{content}"
    conn.sendall(response.encode())
    utime.sleep(0.1)

def receive_data(conn):
    data = b""
    while True:
        chunk = conn.recv(4096)
        if not chunk:
            break
        data += chunk
        if b"\r\n\r\n" in data:
            break
    return data

def handle_request(conn):
    request = receive_data(conn)

    if request.startswith(b"GET"):
        _, path, _ = request.split(b" ", 2)
        path = path.decode()

        if path == "/":
            content = "<html><body><h1>Servidor web en MicroPython</h1>"
            content += "<p>Hola desde MicroPython!</p>"
            content += "<ul>"
            content += "<li><a href='/wifi_config'>Configurar WiFi</a></li>"
            content += "<li><a href='/files'>Gestionar Archivos</a></li>"
            content += "<li><a href='/control'>Controlar Programa Principal</a></li>"
            content += "<li><a href='/status'>Monitorear Estado</a></li>"
            content += "<li><a href='/restart'>Reiniciar ESP32</a></li>"
            content += "</ul>"
            content += "<h2>Archivos en el ESP32:</h2>"
            content += "<ul>"
            for file in uos.listdir():
                content += f"<li><a href='/view/{file}'>{file}</a></li>"
            content += "</ul>"
            content += "</body></html>"
            send_response(conn, 200, "text/html", content)
        elif path.startswith("/view/"):
            filename = path.split("/")[-1]
            try:
                with open(filename, "r") as f:
                    content = f.read()
                    content = content.replace("<", "&lt;").replace(">", "&gt;")  # Escapar caracteres especiales
                    html_content = f"<html><head><meta charset='UTF-8'></head><body>"
                    html_content += f"<h1>Contenido de {filename}</h1>"
                    html_content += f"<pre>{content}</pre>"
                    html_content += "<a href='/'>Volver a la página principal</a>"
                    html_content += "</body></html>"
                    send_response(conn, 200, "text/html", html_content)
            except Exception as e:
                send_response(conn, 500, "text/plain", f"Error al leer el archivo: {str(e)}")
        elif path == "/wifi_config":
            content = "<html><body><h1>Configuración de WiFi</h1>"
            content += "<form method='post' action='/save_wifi'>"
            content += "<input type='text' name='ssid' placeholder='SSID'><br>"
            content += "<input type='password' name='password' placeholder='Contraseña'><br>"
            content += "<input type='submit' value='Guardar'>"
            content += "</form>"
            content += "</body></html>"
            send_response(conn, 200, "text/html", content)
        elif path == "/files":
            content = "<html><body><h1>Gestionar Archivos</h1>"
            content += "<ul>"
            for file in uos.listdir():
                content += f"<li>{file}</li>"
            content += "</ul>"
            content += "<form method='post' action='/upload' enctype='multipart/form-data'>"
            content += "<input type='file' name='file'><br>"
            content += "<input type='submit' value='Subir'>"
            content += "</form>"
            content += "</body></html>"
            send_response(conn, 200, "text/html", content)
        elif path == "/control":
            content = "<html><body><h1>Controlar Programa Principal</h1>"
            content += "<form method='post' action='/start'>"
            content += "<select name='filename'>"
            for file in uos.listdir():
                if file.endswith('.py'):
                    content += f"<option value='{file}'>{file}</option>"
            content += "</select><br>"
            content += "<input type='submit' value='Iniciar'>"
            content += "</form>"
            content += "<form method='post' action='/stop'>"
            content += "<input type='submit' value='Detener'>"
            content += "</form>"
            content += "</body></html>"
            send_response(conn, 200, "text/html", content)
        elif path == "/status":
            content = "<html><body><h1>Monitorear Estado</h1>"
            content += f"<p>Memoria libre: {gc.mem_free()} bytes</p>"
            content += f"<p>Espacio en flash: {uos.statvfs('/')[0]} bytes</p>"
            content += "</body></html>"
            send_response(conn, 200, "text/html", content)
        elif path == "/restart":
            send_response(conn, 200, "text/plain", "Reiniciando ESP32...")
            conn.close()
            reset()
        else:
            send_response(conn, 404, "text/plain", "Archivo no encontrado")
    elif request.startswith(b"POST"):
        header, data = request.split(b"\r\n\r\n", 1)
        _, path, _ = header.split(b" ", 2)
        path = path.decode()

        if path == "/save_wifi":
            data = data.decode()
            params = {}
            for param in data.split("&"):
                key, value = param.split("=")
                params[key] = value
            ssid = params["ssid"]
            password = params["password"]
            save_credentials(ssid, password)
            send_response(conn, 200, "text/plain", "Credenciales WiFi guardadas")
            conn.close()
            reset()
        elif path == "/upload":
            print("Iniciando procesamiento de carga de archivo")
            try:
                boundary = None
                for line in header.split(b"\r\n"):
                    if b"Content-Type:" in line and b"boundary=" in line:
                        boundary = b"--" + line.split(b"boundary=")[1]
                        break
                
                if not boundary:
                    raise ValueError("No se encontró el boundary en la solicitud")

                print(f"Boundary encontrado: {boundary}")

                parts = data.split(boundary)
                file_part = None
                for part in parts:
                    if b'Content-Disposition: form-data; name="file"' in part:
                        file_part = part
                        break
                
                if not file_part:
                    print("Contenido de la solicitud:")
                    print(data)
                    raise ValueError("No se encontró la parte del archivo en la solicitud")

                print("Parte del archivo encontrada")

                filename_start = file_part.find(b'filename="') + 10
                filename_end = file_part.find(b'"', filename_start)
                filename = file_part[filename_start:filename_end].decode()

                print(f"Nombre del archivo: {filename}")

                content_start = file_part.find(b'\r\n\r\n') + 4
                file_content = file_part[content_start:]

                print(f"Longitud del contenido del archivo: {len(file_content)} bytes")

                with open(filename, "wb") as f:
                    f.write(file_content)

                print(f"Archivo {filename} guardado correctamente")
                send_response(conn, 200, "text/plain", "Archivo cargado exitosamente")
            except Exception as e:
                print(f"Error al procesar la carga del archivo: {e}")
                print(f"Detalles del error: {type(e).__name__}, {str(e)}")
                send_response(conn, 500, "text/plain", f"Error al procesar la carga del archivo: {str(e)}")
        elif path == "/start":
            data = data.decode()
            params = {}
            for param in data.split("&"):
                key, value = param.split("=")
                params[key] = value
            filename = params["filename"]
            try:
                if filename not in uos.listdir():
                    raise FileNotFoundError(f"El archivo {filename} no existe")
                with open(filename, "r") as f:
                    code = f.read()
                try:
                    print(f"Intentando compilar {filename}")
                    # Intentar compilar el código para detectar errores de sintaxis
                    compile(code, filename, 'exec')
                    print(f"Compilación de {filename} exitosa")
                    # Si no hay errores de sintaxis, ejecutar el código
                    print(f"Intentando ejecutar {filename}")
                    exec(code, globals())
                    print(f"Ejecución de {filename} exitosa")
                    send_response(conn, 200, "text/plain", f"Programa {filename} iniciado exitosamente")
                except SyntaxError as se:
                    error_msg = f"Error de sintaxis en {filename}: {str(se)}"
                    print(error_msg)
                    print(f"Detalles del error: {type(se).__name__}, {str(se)}")
                    # Intentar obtener más información sobre el error
                    try:
                        print(f"Línea del error: {se.lineno}")
                        print(f"Offset del error: {se.offset}")
                        print(f"Texto del error: {se.text}")
                    except AttributeError:
                        print("No se pudo obtener información detallada del error de sintaxis")
                    send_response(conn, 500, "text/plain", f"Error al iniciar el programa: {error_msg}")
                except Exception as e:
                    error_msg = f"Error al ejecutar {filename}: {str(e)}"
                    print(error_msg)
                    print(f"Tipo de error: {type(e).__name__}")
                    print(f"Detalles del error: {str(e)}")
                    send_response(conn, 500, "text/plain", f"Error al iniciar el programa: {error_msg}")
            except Exception as e:
                error_msg = f"Error al abrir o leer {filename}: {str(e)}"
                print(error_msg)
                print(f"Tipo de error: {type(e).__name__}")
                print(f"Detalles del error: {str(e)}")
                send_response(conn, 500, "text/plain", f"Error al iniciar el programa: {error_msg}")
        elif path == "/stop":
            send_response(conn, 200, "text/plain", "Programa detenido")
        else:
            send_response(conn, 400, "text/plain", "Solicitud no válida")

def main():
    print("Iniciando el servidor web...")

    # Crear archivo de credenciales si no existe
    create_credentials_file()

    # Leer credenciales del archivo
    ssid, password = read_credentials()

    if ssid and password:
        print("Credenciales WiFi encontradas en el archivo")
        # Conectar a la red WiFi
        connect_wifi(ssid, password)
    else:
        print("No se encontraron credenciales WiFi en el archivo")
        # Iniciar modo AP
        start_ap_mode()

    # Crear socket y escuchar en el puerto 80
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("", 80))
    s.listen(1)
    print("Servidor web iniciado")

    while True:
        try:
            # Aceptar conexiones entrantes
            conn, addr = s.accept()
            print("Conexión desde:", addr)

            try:
                # Manejar la solicitud en una función separada
                handle_request(conn)
            except Exception as e:
                print(f"Error al manejar la solicitud: {e}")
                send_response(conn, 500, "text/plain", "Error interno del servidor")
            finally:
                # Cerrar la conexión
                conn.close()

            # Recolectar la basura para liberar memoria
            gc.collect()
        except Exception as e:
            print(f"Error en el bucle principal: {e}")

if __name__ == "__main__":
    main()