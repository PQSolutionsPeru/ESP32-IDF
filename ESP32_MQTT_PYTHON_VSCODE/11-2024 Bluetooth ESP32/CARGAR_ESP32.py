import subprocess
import time
import sys
import os
from typing import List, Dict
import logging

# Configurar logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)

class ESP32Uploader:
    def __init__(self, port: str):
        self.port = port
        self.file_groups = {
            "configs_basic": [
                ("mkdir", "config"),
                ("put", "config/__init__.py", "config/__init__.py"),
                ("put", "config/base_config.py", "config/base_config.py"),
                ("put", "config/wifi_config.py", "config/wifi_config.py"),
                ("put", "config/mqtt_config.py", "config/mqtt_config.py"),
            ],
            "configs_additional_1": [
                ("put", "config/device_pool_config.py", "config/device_pool_config.py"),
                ("put", "config/watchdog_config.py", "config/watchdog_config.py"),
                ("put", "config/relay_config.py", "config/relay_config.py"),
            ],
            "configs_additional_2": [
                ("put", "config/system_config.py", "config/system_config.py"),
            ],
            "wifi_esp32": [
                ("put", "wifi_manager.py"),
                ("put", "esp32_id_manager.py"),
            ],
            "mqtt_files": [
                ("put", "mqtt_ssl_setup.py"),
                ("put", "robust.py"),
                ("put", "simple.py"),
            ],
            "mqtt_main": [
                ("put", "mqtt_manager.py"),
            ],
            "managers_1": [
                ("put", "relay_manager.py"),
                ("put", "time_manager.py"),
                ("put", "led_manager.py"),
            ],
            "managers_2": [
                ("put", "watchdog_manager.py"),
                ("put", "bluetooth_manager.py"),
            ],
            "ble_files": [
                ("put", "ble_advertising.py"),
                ("put", "ble_uart_peripheral.py"),
            ],
            "certs": [
                ("put", "combined_ca.crt"),
            ],
            "final_files": [
                ("put", "main.py"),
            ]
        }
        self.max_retries = 3
        self.delay_between_groups = 3  # Aumentado a 3 segundos
        self.delay_between_retries = 10  # Aumentado a 10 segundos
        self.command_timeout = 60  # Aumentado a 60 segundos

    def reset_and_wait(self):
        """Resetea el ESP32 y espera a que esté listo"""
        logging.info("Reseteando ESP32...")
        try:
            subprocess.run(
                ["ampy", "-p", self.port, "reset"],
                capture_output=True,
                text=True,
                timeout=5  # Timeout reducido para el reset
            )
            logging.info("ESP32 reseteado")
        except:
            logging.warning("No se pudo resetear el ESP32, probablemente ya está reiniciando")
            
        logging.info("Esperando 5 segundos después del reset...")
        time.sleep(5)
        return True

    def execute_ampy_command(self, command: List[str], retry_count: int = 0) -> bool:
        """Ejecuta un comando ampy con reintentos"""
        try:
            cmd = ["ampy", "-p", self.port] + command
            logging.info(f"Ejecutando: {' '.join(cmd)}")
            
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=self.command_timeout
            )
            
            if result.returncode == 0:
                logging.info("Comando ejecutado exitosamente")
                return True
            else:
                logging.error(f"Error: {result.stderr}")
                if retry_count < self.max_retries:
                    logging.info(f"Reintentando en {self.delay_between_retries} segundos...")
                    time.sleep(self.delay_between_retries)
                    return self.execute_ampy_command(command, retry_count + 1)
                return False
                
        except subprocess.TimeoutExpired:
            logging.error("Timeout ejecutando comando")
            # Resetear el ESP32 si hay timeout
            self.reset_and_wait()
            if retry_count < self.max_retries:
                logging.info(f"Reintentando en {self.delay_between_retries} segundos...")
                time.sleep(self.delay_between_retries)
                return self.execute_ampy_command(command, retry_count + 1)
            return False
            
        except Exception as e:
            logging.error(f"Error inesperado: {e}")
            return False

    def process_group(self, group_name: str, commands: List[tuple]) -> bool:
        """Procesa un grupo de comandos"""
        logging.info(f"\nProcesando grupo: {group_name}")
        success = True
        
        for cmd_type, *args in commands:
            if cmd_type == "mkdir":
                cmd = ["mkdir"] + list(args)
            elif cmd_type == "put":
                cmd = ["put"] + list(args)
            else:
                logging.error(f"Comando desconocido: {cmd_type}")
                continue
                
            if not self.execute_ampy_command(cmd):
                success = False
                logging.error(f"Error en comando {cmd}")
                break
                
            # Pequeña pausa entre archivos del mismo grupo
            time.sleep(1)
                
        return success

    def upload_all(self):
        """Sube todos los archivos en grupos"""
        total_groups = len(self.file_groups)
        current_group = 0
        
        # Reset inicial
        if not self.reset_and_wait():
            return False
        
        for group_name, commands in self.file_groups.items():
            current_group += 1
            logging.info(f"\nGrupo {current_group}/{total_groups}: {group_name}")
            
            if not self.process_group(group_name, commands):
                logging.error(f"Error en grupo {group_name}. Deteniendo proceso.")
                return False
                
            logging.info(f"Grupo {group_name} completado. Esperando {self.delay_between_groups} segundos...")
            time.sleep(self.delay_between_groups)
            
            # Reset después de grupos grandes
            if group_name in ["mqtt_main", "managers_1", "managers_2"]:
                self.reset_and_wait()
            
        logging.info("\nTodos los archivos han sido subidos exitosamente!")
        return True

def main():
    if len(sys.argv) != 2:
        print("Uso: python esp32_upload.py <puerto>")
        print("Ejemplo: python esp32_upload.py COM3")
        sys.exit(1)

    port = sys.argv[1]
    uploader = ESP32Uploader(port)
    
    try:
        if uploader.upload_all():
            logging.info("Proceso completado exitosamente")
            logging.info("El ESP32 se reiniciará automáticamente en modo BLE")
            sys.exit(0)  # Salir limpiamente después de subir todo
        else:
            logging.error("El proceso falló")
            sys.exit(1)
            
    except KeyboardInterrupt:
        logging.info("\nProceso interrumpido por el usuario")
        sys.exit(1)
    except Exception as e:
        logging.error(f"Error inesperado: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()