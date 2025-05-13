import usocket as socket
import network
import gc
import uos
import utime
from machine import Pin, reset

# Configuración de pines
led = Pin(2, Pin.OUT)

# Configuración de archivo de credenciales
CREDENTIALS_FILE = "credentials.txt"

# Credenciales WiFi
SSID = "PABLO-2.4G"
PASSWORD = "47009410"

def create_credentials_file():
    try:
        with open(CREDENTIALS_FILE, "r"):
            pass
    except OSError:
        with open(CREDENTIALS_FILE, "w") as f:
            f.write(f"{SSID}\n{PASSWORD}")

def read_credentials():
    try:
        with open(CREDENTIALS_FILE, "r") as f:
            ssid = f.readline().strip()
            password = f.readline().strip()
        return ssid, password
    except OSError:
        return None, None

def connect_wifi(ssid, password):
    sta_if = network.WLAN(network.STA_IF)
    if not sta_if.isconnected():
        print(f"Conectando a la red WiFi: {ssid}")
        sta_if.active(True)
        sta_if.connect(ssid, password)
        for _ in range(20):
            if sta_if.isconnected():
                print(f"Conectado. IP: {sta_if.ifconfig()[0]}")
                return True
            utime.sleep(1)
        print("No se pudo conectar")
        return False
    return True

def start_ap_mode():
    ap = network.WLAN(network.AP_IF)
    ap.active(True)
    ap.config(essid="ESP32-AP", password="12345678")
    while not ap.active():
        pass
    print(f"Modo AP iniciado. IP: {ap.ifconfig()[0]}")

def send_response(conn, status_code, content_type, content):
    conn.send(f"HTTP/1.1 {status_code} OK\r\n")
    conn.send(f"Content-Type: {content_type}\r\n")
    conn.send(f"Content-Length: {len(content)}\r\n\r\n")
    conn.sendall(content)

def receive_data(conn, chunk_size=1024):
    data = b""
    while True:
        chunk = conn.recv(chunk_size)
        data += chunk
        if len(chunk) < chunk_size or b"\r\n\r\n" in data:
            break
    return data

def handle_file_upload(conn, boundary):
    print("Iniciando handle_file_upload")
    buffer_size = 1024
    file_data = b""
    filename = None
    content_type = None
    
    while True:
        chunk = conn.recv(buffer_size)
        if not chunk:
            break
        
        if not filename:
            header_end = chunk.find(b'\r\n\r\n')
            if header_end != -1:
                header = chunk[:header_end]
                if b'Content-Disposition: form-data; name="file"' in header:
                    filename = header.split(b'filename="')[1].split(b'"')[0].decode()
                    content_type = header.split(b'Content-Type: ')[1].split(b'\r\n')[0].decode()
                    file_data = chunk[header_end+4:]
                    print(f"Nombre del archivo: {filename}")
                    print(f"Tipo de contenido: {content_type}")
        else:
            file_data += chunk
        
        if boundary in file_data:
            file_data = file_data.split(boundary)[0]
            break
    
    if not filename:
        raise ValueError("No se encontró el archivo en la solicitud")
    
    print(f"Longitud del contenido: {len(file_data)} bytes")
    return filename, file_data, content_type

def calculate_file_hash(filename, chunk_size=1024):
    total = 0
    with open(filename, "rb") as f:
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            total += sum(chunk)
    return total % 65536  # Retorna un valor de 16 bits

def handle_request(conn):
    request = receive_data(conn)

    if request.startswith(b"GET"):
        path = request.split(b" ")[1].decode().strip("/")

        if path == "":
            content = "<html><body><h1>Servidor web en MicroPython</h1>"
            content += "<ul>"
            content += "<li><a href='/files'>Gestionar Archivos</a></li>"
            content += "<li><a href='/status'>Monitorear Estado</a></li>"
            content += "<li><a href='/restart'>Reiniciar ESP32</a></li>"
            content += "</ul></body></html>"
            send_response(conn, 200, "text/html", content.encode())
        elif path == "files":
            content = "<html><body><h1>Gestionar Archivos</h1><ul>"
            for file in uos.listdir():
                content += f"<li>{file}</li>"
            content += "</ul>"
            content += "<form method='post' action='/upload' enctype='multipart/form-data'>"
            content += "<input type='file' name='file'><input type='submit' value='Subir'>"
            content += "</form></body></html>"
            send_response(conn, 200, "text/html", content.encode())
        elif path == "status":
            content = f"<html><body><h1>Estado</h1>"
            content += f"<p>Memoria libre: {gc.mem_free()} bytes</p>"
            content += f"<p>Espacio en flash: {uos.statvfs('/')[0] * uos.statvfs('/')[1]} bytes</p>"
            content += "</body></html>"
            send_response(conn, 200, "text/html", content.encode())
        elif path == "restart":
            send_response(conn, 200, "text/plain", b"Reiniciando...")
            utime.sleep(1)
            reset()
        else:
            send_response(conn, 404, "text/plain", b"Not Found")

    elif request.startswith(b"POST"):
        if b"/upload" in request:
            try:
                boundary = b"--" + request.split(b"boundary=")[1].split(b"\r\n")[0]
                filename, file_content, _ = handle_file_upload(conn, boundary)

                with open(filename, "wb") as f:
                    f.write(file_content)

                original_hash = sum(file_content) % 65536
                saved_hash = calculate_file_hash(filename)

                if original_hash == saved_hash:
                    result = f"Archivo {filename} cargado correctamente. Tamaño: {len(file_content)} bytes."
                else:
                    result = f"Error: El contenido del archivo {filename} no coincide después de guardarlo."

                content = f"<html><body><h1>Resultado de la carga</h1><p>{result}</p>"
                content += "<a href='/'>Volver</a></body></html>"
                send_response(conn, 200, "text/html", content.encode())

            except Exception as e:
                print(f"Error al procesar la carga del archivo: {e}")
                send_response(conn, 500, "text/plain", f"Error: {str(e)}".encode())
        else:
            send_response(conn, 400, "text/plain", b"Bad Request")

def main():
    print("Iniciando el servidor web...")
    create_credentials_file()
    ssid, password = read_credentials()

    if ssid and password:
        if not connect_wifi(ssid, password):
            start_ap_mode()
    else:
        start_ap_mode()

    s = socket.socket()
    s.bind(('', 80))
    s.listen(5)
    print("Servidor web iniciado")

    while True:
        try:
            conn, addr = s.accept()
            print(f"Conexión desde: {addr}")
            handle_request(conn)
        except Exception as e:
            print(f"Error: {e}")
        finally:
            conn.close()
        gc.collect()

if __name__ == "__main__":
    main()