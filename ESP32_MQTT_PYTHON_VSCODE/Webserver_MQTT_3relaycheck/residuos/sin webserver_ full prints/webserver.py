import socket
from log_manager import LogManager
from watchdog import feed_watchdog, restart_device

def read_output_log():
    with open("output_log.txt", "r") as file:
        return file.read()

def read_logs():
    try:
        with open("logs.txt", "r") as file:
            return file.read()
    except Exception as e:
        return f"Error al leer el archivo de logs: {str(e)}"

def http_server():
    addr = socket.getaddrinfo('0.0.0.0', 80)[0][-1]
    s = socket.socket()
    s.bind(addr)
    s.listen(1)

    LogManager.write_log("Servidor HTTP iniciado en " + str(addr))

    while True:
        feed_watchdog()
        try:
            cl, addr = s.accept()
            cl_file = cl.makefile('rwb', 0)
            request_line = cl_file.readline().decode().strip()
            if request_line:
                method, url, protocol = request_line.split()

                response = "Solicitud no válida"
                if url == '/output':
                    response = read_output_log()
                elif url == '/restart_device':
                    restart_device()
                    response = "Dispositivo reiniciado"
                elif url == '/logs':
                    response = read_logs()

                cl.sendall('HTTP/1.1 200 OK\n')
                cl.sendall('Content-Type: text/plain\n')
                cl.sendall('Connection: close\n\n')
                cl.sendall(response)
            cl.close()
        except Exception as e:
            LogManager.write_log(f"Error del servidor: {e}")

if __name__ == '__main__':
    http_server()
