from config.base_config import BaseConfig
import utime
import json
import os

class DevicePoolConfig(BaseConfig):
    def __init__(self):
        """Inicializa la configuración del pool de dispositivos"""
        self.DEFAULT_CONFIG = {
            # Historial de asignaciones para seguimiento
            "assignments": [],
            
            # Configuración del broker
            "broker": {
                "host": "node02.myqtthub.com",
                "port": 8883,
                "keep_alive": 120,
                "retry_limit": 5,
                "retry_delay": 10000  # 10 segundos
            }
        }
        super().__init__('device_pool.json')
        self._init_default_config()

    def _init_default_config(self):
        """Inicializa la configuración por defecto si no existe"""
        if not self.config:
            self.config = self.DEFAULT_CONFIG.copy()
            self._save_config()

    def create_device_credentials(self, esp32_id):
        """
        Crea credenciales MQTT basadas en el ESP32_ID
        
        Args:
            esp32_id (str): ID único del ESP32
            
        Returns:
            dict: Credenciales MQTT incluyendo client_id, user, password
        """
        if not esp32_id:
            return None
            
        return {
            "client_id": esp32_id,
            "user": esp32_id,
            "password": esp32_id,
            "broker": self.config['broker']['host'],
            "port": self.config['broker']['port'],
            "keep_alive": self.config['broker']['keep_alive']
        }

    def assign_device(self, mac_address, esp32_id):
        """
        Asigna credenciales a un ESP32 específico y registra la asignación
        
        Args:
            mac_address (str): Dirección MAC del ESP32
            esp32_id (str): ID único del ESP32
            
        Returns:
            dict: Credenciales MQTT o None si falla
        """
        try:
            if not mac_address or not esp32_id:
                print("[DEVICE_POOL] Error: MAC o ESP32_ID inválido")
                return None
                
            # Crear credenciales
            credentials = self.create_device_credentials(esp32_id)
            if not credentials:
                print("[DEVICE_POOL] Error: No se pudieron crear credenciales")
                return None
                
            # Registrar asignación con timestamp
            assignment = {
                'mac': mac_address,
                'esp32_id': esp32_id,
                'timestamp': utime.ticks_ms(),
                'broker': self.config['broker']['host']
            }
            
            # Mantener historial de asignaciones
            self.config['assignments'].append(assignment)
            
            # Mantener solo las últimas 10 asignaciones
            if len(self.config['assignments']) > 10:
                self.config['assignments'] = self.config['assignments'][-10:]
            
            # Guardar configuración
            if self._save_config():
                print(f"[DEVICE_POOL] Credenciales asignadas a {esp32_id}")
                return credentials
            else:
                print("[DEVICE_POOL] Error guardando asignación")
                return None
                
        except Exception as e:
            print(f"[DEVICE_POOL] Error en assign_device: {e}")
            return None

    def get_device_by_mac(self, mac_address):
        """
        Busca la última asignación para un MAC específico
        
        Args:
            mac_address (str): Dirección MAC del ESP32
            
        Returns:
            dict: Credenciales MQTT o None si no se encuentra
        """
        try:
            if not mac_address:
                return None
                
            # Buscar la asignación más reciente para este MAC
            assignments = sorted(
                [a for a in self.config['assignments'] if a['mac'] == mac_address],
                key=lambda x: x['timestamp'],
                reverse=True
            )
            
            if assignments:
                esp32_id = assignments[0]['esp32_id']
                print(f"[DEVICE_POOL] Encontrada asignación previa para {mac_address}")
                return self.create_device_credentials(esp32_id)
                
            print(f"[DEVICE_POOL] No se encontró asignación para {mac_address}")
            return None
            
        except Exception as e:
            print(f"[DEVICE_POOL] Error en get_device_by_mac: {e}")
            return None

    def release_device(self, mac_address):
        """
        Marca las credenciales como liberadas para un MAC específico
        
        Args:
            mac_address (str): Dirección MAC del ESP32
            
        Returns:
            bool: True si se liberó correctamente, False en caso contrario
        """
        try:
            if not mac_address:
                return False
                
            # Agregar entrada de liberación al historial
            release_entry = {
                'mac': mac_address,
                'esp32_id': None,  # Indica liberación
                'timestamp': utime.ticks_ms(),
                'event': 'released'
            }
            
            self.config['assignments'].append(release_entry)
            
            # Mantener solo las últimas 10 entradas
            if len(self.config['assignments']) > 10:
                self.config['assignments'] = self.config['assignments'][-10:]
                
            return self._save_config()
            
        except Exception as e:
            print(f"[DEVICE_POOL] Error en release_device: {e}")
            return False

    def get_broker_config(self):
        """
        Obtiene la configuración actual del broker
        
        Returns:
            dict: Configuración del broker
        """
        return self.config.get('broker', self.DEFAULT_CONFIG['broker'])

    def get_assignment_history(self, mac_address=None):
        """
        Obtiene el historial de asignaciones
        
        Args:
            mac_address (str, optional): Filtrar por MAC específico
            
        Returns:
            list: Lista de asignaciones
        """
        try:
            if mac_address:
                return [a for a in self.config['assignments'] if a['mac'] == mac_address]
            return self.config['assignments']
        except Exception as e:
            print(f"[DEVICE_POOL] Error obteniendo historial: {e}")
            return []