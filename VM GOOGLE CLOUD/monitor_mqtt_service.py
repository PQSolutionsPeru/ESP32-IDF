#!/usr/bin/python3

import subprocess
import time
import logging
import sys
import traceback
from datetime import datetime, timedelta
from google.cloud import firestore

# Configuración de logging más detallada
logging.basicConfig(
    level=logging.DEBUG,  # Cambiar a DEBUG para más detalle
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler(sys.stdout)  # Asegura que los logs van a stdout
    ]
)

class ServiceMonitor:
    def __init__(self):
        try:
            logging.info("Iniciando ServiceMonitor...")
            self.db = firestore.Client(project='fir-hdd-monitor-d00de')
            self.services = {
                'vm_monitor_main': {'failure_count': 0, 'last_failure': None},
                'esp32-config-manager': {'failure_count': 0, 'last_failure': None}
            }
            self.last_alert_time = None
            self.last_cleanup_time = None
            self.alert_cooldown = timedelta(minutes=30)
            self.cleanup_interval = timedelta(hours=6)
            self.max_alerts = 20
            self.check_interval = 60
            logging.info("ServiceMonitor inicializado correctamente")
        except Exception as e:
            logging.error(f"Error en inicialización: {e}")
            logging.error(traceback.format_exc())
            raise

    def check_service_status(self, service_name: str) -> bool:
        try:
            logging.debug(f"Verificando estado de {service_name}")
            result = subprocess.run(
                ['systemctl', 'is-active', service_name],
                capture_output=True,
                text=True
            )
            is_active = result.stdout.strip() == 'active'
            logging.debug(f"Estado de {service_name}: {is_active}")
            return is_active
        except Exception as e:
            logging.error(f"Error verificando {service_name}: {e}")
            logging.error(traceback.format_exc())
            return False

    def run(self):
        logging.info("Iniciando monitoreo de servicios...")
        try:
            while True:
                for service_name, service_info in self.services.items():
                    try:
                        if not self.check_service_status(service_name):
                            service_info['failure_count'] += 1
                            logging.warning(f"{service_name} falló. Intento {service_info['failure_count']}")
                        else:
                            service_info['failure_count'] = 0
                            logging.info(f"{service_name} funcionando normalmente")
                    except Exception as e:
                        logging.error(f"Error monitoreando {service_name}: {e}")
                        logging.error(traceback.format_exc())
                
                time.sleep(self.check_interval)
        except Exception as e:
            logging.error(f"Error en el loop principal: {e}")
            logging.error(traceback.format_exc())
            raise

if __name__ == '__main__':
    try:
        logging.info("Iniciando script de monitoreo...")
        monitor = ServiceMonitor()
        monitor.run()
    except Exception as e:
        logging.error(f"Error fatal en el script principal: {e}")
        logging.error(traceback.format_exc())
        sys.exit(1)