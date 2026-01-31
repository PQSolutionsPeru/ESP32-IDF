import logging
import signal
import sys
from mqtt_client import MQTTClient
from firestore_handler import FirestoreHandler
import json
import threading
import time
import redis  # NUEVO

def heartbeat_thread():
    """Actualiza heartbeat en Redis cada 30s."""
    r = redis.Redis(host='localhost', decode_responses=True)
    while True:
        try:
            r.set('server:last_heartbeat', time.time())
            time.sleep(30)
        except Exception as e:
            logging.error(f"Heartbeat update failed: {e}")
            time.sleep(5)

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

        # NUEVO: Iniciar heartbeat thread
        hb_thread = threading.Thread(target=heartbeat_thread, daemon=True)
        hb_thread.start()

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