import logging
import signal
import sys
from mqtt_client import MQTTClient
from firestore_handler import FirestoreHandler
import json
import threading
import time

def signal_handler(signum, frame):
    """Maneja la limpieza antes de cerrar"""
    logging.info("Señal de terminación recibida")
    try:
        firestore_handler.cleanup()
    except Exception as e:
        logging.error(f"Error durante la limpieza: {e}")
    sys.exit(0)

if __name__ == '__main__':
    try:
        logging.info("Iniciando servicio...")
        
        signal.signal(signal.SIGTERM, signal_handler)
        signal.signal(signal.SIGINT, signal_handler)
        
        firestore_handler = FirestoreHandler()
        
        firestore_handler.mqtt_client.connect_and_loop()
        
        while True:
            time.sleep(1)
        
    except KeyboardInterrupt:
        logging.info("Servicio interrumpido por el usuario")
        signal_handler(signal.SIGINT, None)
    except Exception as e:
        logging.error(f"Error fatal: {e}", exc_info=True)
        sys.exit(1)