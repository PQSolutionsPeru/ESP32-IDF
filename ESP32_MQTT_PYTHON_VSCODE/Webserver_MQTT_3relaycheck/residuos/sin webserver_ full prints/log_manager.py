import os

class LogManager:
    def __init__(self, file_path, max_size_kb=50):
        self.file_path = file_path
        self.max_size_kb = max_size_kb
        self.ensure_file_exists()  # Asegurar que el archivo de log existe al crear el objeto

    def ensure_file_exists(self):
        # Intenta abrir el archivo en modo de append, lo que creará el archivo si no existe.
        try:
            with open(self.file_path, 'a') as file:
                pass
        except OSError as e:
            print(f"Error al asegurar que el archivo de log existe: {e}")

    def write_log(self, message):
        print(f"LOG: {message}")  # Imprimir en la terminal cada mensaje de log
        self._manage_log_size()
        with open(self.file_path, "a") as file:
            file.write(message + "\n")

    def _manage_log_size(self):
        # Verifica si el archivo es más grande que el tamaño máximo permitido y, de ser así, elimina las líneas más antiguas.
        try:
            if os.stat(self.file_path)[6] > self.max_size_kb * 1024:
                self._remove_oldest_log()
        except OSError as e:
            print(f"Error al gestionar el tamaño del archivo de log: {e}")

    def _remove_oldest_log(self):
        try:
            with open(self.file_path, "r+") as file:
                lines = file.readlines()
                file.seek(0)
                file.truncate()
                # Mantén un número mínimo de líneas si es necesario
                file.writelines(lines[1:])
        except OSError as e:
            print(f"Error al remover la línea más antigua del log: {e}")
