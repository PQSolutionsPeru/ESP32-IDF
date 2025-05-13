import os

class LogManager:
    def __init__(self, file_path, max_size_kb=50):
        self.file_path = file_path
        self.max_size_kb = max_size_kb
        self.ensure_file_exists()

    def ensure_file_exists(self):
        try:
            with open(self.file_path, 'a') as file:
                pass
        except OSError as e:
            print(f"Error al asegurar que el archivo de log existe: {e}")

    def write_log(self, message):
        print(f"LOG: {message}")
        self._manage_log_size()
        with open(self.file_path, "a") as file:
            file.write(message + "\n")

    def _manage_log_size(self):
        try:
            file_stat = os.stat(self.file_path)
            if file_stat[6] > self.max_size_kb * 1024:  # Acceso por índice si st_size no está disponible
                self._remove_oldest_log()
        except OSError as e:
            print(f"Error al gestionar el tamaño del archivo de log: {e}")



    def _remove_oldest_log(self):
        try:
            with open(self.file_path, "r+") as file:
                lines = file.readlines()
                file.seek(0)
                file.truncate()
                file.writelines(lines[-50:])
        except OSError as e:
            print(f"Error al remover la línea más antigua del log: {e}")
