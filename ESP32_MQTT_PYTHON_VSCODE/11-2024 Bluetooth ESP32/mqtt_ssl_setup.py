import ssl

def get_ssl_params():
    """
    Implementación mínima y optimizada de SSL para reducir consumo de memoria.
    Devuelve un objeto con método wrap_socket que usa parámetros básicos.
    """
    class MinimalSSL:
        def wrap_socket(self, sock, server_hostname=None):
            """Wrapper mínimo para el socket"""
            return ssl.wrap_socket(
                sock,
                cert_reqs=ssl.CERT_NONE  # Sin verificación de certificados
                # No usar el parámetro 'ciphers' que no es soportado
            )
    
    return MinimalSSL()