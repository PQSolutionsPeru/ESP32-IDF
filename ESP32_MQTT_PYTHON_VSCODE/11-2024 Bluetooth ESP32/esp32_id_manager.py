import machine
import ubinascii
import os
import json
import gc
import utime
import random

class ESP32IdManager:
    def __init__(self, mqtt_manager=None, time_manager=None):
        """Inicializa el gestor de ID único y persistente"""
        print("[ESP32_ID] Iniciando gestor...")
        
        # Archivos de configuración
        self.ID_FILE = "esp32_id.json"
        self.BACKUP_FILE = "esp32_id.bak"
        
        # Gestores externos
        self.mqtt_manager = mqtt_manager
        self.time_manager = time_manager
        
        # Variables de control
        self.esp32_id = None
        self.mac_address = None
        self.last_sync = 0
        self.is_synced = False
        
        # Control de errores
        self.error_count = 0
        self.MAX_ERRORS = 3
        self.last_error = None
        
        # Historial
        self.id_history = []
        self.MAX_HISTORY = 10
        
        # Constantes MQTT
        self.MAC_SEARCH_TIMEOUT = 30000  # 30 segundos
        self.SEARCH_RETRY_DELAY = 5000   # 5 segundos entre reintentos
        self.MAX_SEARCH_RETRIES = 3      # 3 intentos máximo
        
        # Inicialización
        print("[ESP32_ID] Obteniendo MAC address...")
        self._get_mac_address()
        
        # Primero intentar cargar ID existente
        if not self._load_or_generate_id():
            print("[ESP32_ID] Generando nuevo ID...")
            self._generate_unique_id()
            self._save_with_backup()
            
        print(f"[ESP32_ID] ID final: {self.esp32_id}")

    def _get_mac_address(self):
        """Obtiene MAC address del ESP32"""
        try:
            # Obtener y normalizar MAC
            mac = ubinascii.hexlify(machine.unique_id()).decode().upper()
            self.mac_address = mac
            print(f"[ESP32_ID] MAC address: {self.mac_address}")
        except Exception as e:
            print(f"[ESP32_ID] Error obteniendo MAC: {e}")
            self.mac_address = None

    def _wait_mqtt_ready(self):
        """Espera a que MQTT esté disponible"""
        if not self.mqtt_manager:
            return False
            
        retry_count = 0
        while retry_count < 3:
            if self.mqtt_manager.check_connection():
                return True
            print(f"[ESP32_ID] Esperando MQTT... Intento {retry_count + 1}")
            utime.sleep_ms(1000)
            retry_count += 1
            
        return False

    def _generate_unique_id(self):
        """Genera ID único basado únicamente en MAC - 8 caracteres"""
        try:
            print("[ESP32_ID] Generando ID único...")
            
            if not self.mac_address:
                print("[ESP32_ID] Error: MAC no disponible")
                return None
            
            # Usar los últimos 4 caracteres del MAC
            mac_end = self.mac_address[-4:].upper()
            # Usar los primeros 2 caracteres del MAC
            mac_start = self.mac_address[:2].upper()
            
            # Generar ID de 8 caracteres
            self.esp32_id = f"{mac_end}AC{mac_start}"
            print(f"[ESP32_ID] ID generado: {self.esp32_id}")
            
            # Añadir al historial
            self._add_to_history(self.esp32_id, "generated")
            return self.esp32_id
            
        except Exception as e:
            print(f"[ESP32_ID] Error generando ID: {e}")
            self._handle_error("generation_error", str(e))
            return None

    def _search_mac_in_mqtt(self):
        """Busca ID existente usando MAC vía MQTT"""
        if not self._wait_mqtt_ready():
            return False
            
        try:
            print(f"[ESP32_ID] Buscando ID para MAC: {self.mac_address}")
            response_topic = f"esp32/mac_response/{self.mac_address}"
            id_found = False
            
            def handle_mac_response(topic, msg):
                try:
                    response = json.loads(msg.decode())
                    if response.get('MAC') == self.mac_address:
                        esp32_id = response.get('esp32_id')
                        if esp32_id:
                            nonlocal id_found
                            self.esp32_id = esp32_id
                            id_found = True
                            print(f"[ESP32_ID] ID encontrado: {esp32_id}")
                except Exception as e:
                    print(f"[ESP32_ID] Error procesando respuesta MAC: {e}")

            # Suscribirse al tópico de respuesta
            self.mqtt_manager.subscribe(response_topic, handle_mac_response)
            
            # Publicar búsqueda
            search_request = {
                'MAC': self.mac_address,
                'response_topic': response_topic,
                'timestamp': utime.ticks_ms()
            }
            
            retry_count = 0
            while retry_count < self.MAX_SEARCH_RETRIES and not id_found:
                if retry_count > 0:
                    print(f"[ESP32_ID] Reintento {retry_count + 1} de búsqueda MAC")
                    utime.sleep_ms(self.SEARCH_RETRY_DELAY)
                
                self.mqtt_manager.publish_event(
                    "esp32/mac_search",
                    search_request,
                    retain=False
                )
                
                # Esperar respuesta
                start_time = utime.ticks_ms()
                while not id_found:
                    if utime.ticks_diff(utime.ticks_ms(), start_time) >= self.MAC_SEARCH_TIMEOUT:
                        break
                    utime.sleep_ms(100)
                    self.mqtt_manager.check_msg()
                    
                retry_count += 1
                
            # Limpiar suscripción
            self.mqtt_manager.unsubscribe(response_topic)
            
            if id_found:
                self._add_to_history(self.esp32_id, "recovered_from_mqtt")
                self._save_with_backup()
                return True
                
            return False
            
        except Exception as e:
            print(f"[ESP32_ID] Error en búsqueda MAC: {e}")
            return False

    def _save_id(self, filename):
        """Guarda ID de manera segura"""
        try:
            gc.collect()
            
            # Crear archivo temporal
            temp_file = filename + '.tmp'
            save_data = {
                'esp32_id': self.esp32_id,
                'mac': self.mac_address,
                'timestamp': utime.ticks_ms(),
                'history': self.id_history
            }
            
            with open(temp_file, 'w') as f:
                json.dump(save_data, f)
            
            # Verificar archivo temporal
            with open(temp_file, 'r') as f:
                data = json.load(f)
                if not data.get('esp32_id'):
                    raise ValueError("ID no válido")
            
            # Reemplazar archivo original
            os.rename(temp_file, filename)
            return True
            
        except Exception as e:
            print(f"[ESP32_ID] Error guardando en {filename}: {e}")
            self._handle_error("save_error", str(e))
            try:
                os.remove(temp_file)
            except:
                pass
            return False

    def _save_with_backup(self):
        """Guarda ID con sistema de respaldo"""
        try:
            print("[ESP32_ID] Guardando con backup...")
            
            # Guardar backup primero
            if not self._save_id(self.BACKUP_FILE):
                return False
                
            # Luego guardar archivo principal
            if not self._save_id(self.ID_FILE):
                return False
                
            return True
                
        except Exception as e:
            print(f"[ESP32_ID] Error en save_with_backup: {e}")
            self._handle_error("backup_error", str(e))
            return False

    def _load_or_generate_id(self):
        """Carga ID existente o inicia proceso de generación"""
        try:
            # 1. Intentar cargar de archivo principal
            if self.ID_FILE in os.listdir():
                with open(self.ID_FILE, 'r') as f:
                    data = json.load(f)
                    if data.get('esp32_id'):
                        self.esp32_id = data['esp32_id']
                        self.id_history = data.get('history', [])
                        self._add_to_history(self.esp32_id, "loaded_from_file")
                        return True

            # 2. Intentar cargar de backup
            if self.BACKUP_FILE in os.listdir():
                with open(self.BACKUP_FILE, 'r') as f:
                    data = json.load(f)
                    if data.get('esp32_id'):
                        self.esp32_id = data['esp32_id']
                        self.id_history = data.get('history', [])
                        self._add_to_history(self.esp32_id, "loaded_from_backup")
                        self._save_id(self.ID_FILE)
                        return True

            # 3. Intentar recuperar por MQTT si está disponible
            if self.mqtt_manager and self._search_mac_in_mqtt():
                return True

            # 4. Si todo lo anterior falla, devolver False para generar nuevo ID
            return False
            
        except Exception as e:
            print(f"[ESP32_ID] Error cargando/generando ID: {e}")
            self._handle_error("load_error", str(e))
            return False

    def _handle_error(self, error_type, error_message):
        """Maneja errores del gestor"""
        try:
            self.error_count += 1
            self.last_error = {
                'type': error_type,
                'message': error_message,
                'timestamp': utime.ticks_ms()
            }
            
            # Si hay demasiados errores, intentar recuperación
            if self.error_count >= self.MAX_ERRORS:
                print("[ESP32_ID] Demasiados errores, intentando recuperación")
                self._recover_from_errors()
                
        except Exception as e:
            print(f"[ESP32_ID] Error en handle_error: {e}")

    def _recover_from_errors(self):
        """Intenta recuperarse de errores críticos"""
        try:
            print("[ESP32_ID] Iniciando recuperación de errores...")
            self.error_count = 0
            
            # 1. Intentar cargar desde backup
            if self.BACKUP_FILE in os.listdir():
                with open(self.BACKUP_FILE, 'r') as f:
                    data = json.load(f)
                    if data.get('esp32_id'):
                        self.esp32_id = data['esp32_id']
                        self._save_id(self.ID_FILE)
                        print("[ESP32_ID] Recuperado desde backup")
                        return

            # 2. Intentar búsqueda MAC
            if self.mqtt_manager and self._search_mac_in_mqtt():
                print("[ESP32_ID] Recuperado vía MQTT")
                return

            # 3. Si todo falla, generar nuevo
            print("[ESP32_ID] Generando nuevo ID en recuperación")
            self._generate_unique_id()
            self._save_with_backup()
            
        except Exception as e:
            print(f"[ESP32_ID] Error en recuperación: {e}")

    def _add_to_history(self, esp32_id, event_type):
        """Añade evento al historial"""
        try:
            self.id_history.append({
                'id': esp32_id,
                'event': event_type,
                'timestamp': utime.ticks_ms()
            })
            
            # Mantener límite de historial
            if len(self.id_history) > self.MAX_HISTORY:
                self.id_history = self.id_history[-self.MAX_HISTORY:]
                
        except Exception as e:
            print(f"[ESP32_ID] Error añadiendo a historial: {e}")

    def get_id(self):
        """Obtiene ID actual"""
        return self.esp32_id

    def get_mac(self):
        """Obtiene MAC address"""
        return self.mac_address

    def get_status(self):
        """Obtiene estado del gestor"""
        return {
            'esp32_id': self.esp32_id,
            'mac_address': self.mac_address,
            'error_count': self.error_count,
            'last_error': self.last_error,
            'history': self.id_history,
            'is_synced': self.is_synced
        }

    def validate_id(self):
        """Valida que el ID sea correcto"""
        try:
            if not self.esp32_id:
                return False
                
            # Validar formato
            if len(self.esp32_id) != 8:
                return False
                
            if not self.esp32_id.isalnum():
                return False
                
            if not self.esp32_id.isupper():
                return False
                
            return True
                
        except Exception as e:
            print(f"[ESP32_ID] Error validando ID: {e}")
            return False

    def sync(self):
        """Sincroniza estado con la VM"""
        if not self.mqtt_manager or not self.esp32_id:
            return False
            
        try:
            # Publicar información de dispositivo
            self.mqtt_manager.publish_event(
                "esp32/network_info",
                {
                    'esp32_id': self.esp32_id,
                    'MAC': self.mac_address,
                    'status': 'AWAITING_CONFIG',
                    'timestamp': utime.ticks_ms()
                },
                retain=False
            )
            
            self.last_sync = utime.ticks_ms()
            self.is_synced = True
            return True
            
        except Exception as e:
            print(f"[ESP32_ID] Error en sincronización: {e}")
            self.is_synced = False
            return False

    def check_sync(self):
        """Verifica y mantiene sincronización con la VM"""
        if not self.mqtt_manager or not self.esp32_id:
            return False
            
        try:
            current_time = utime.ticks_ms()
            sync_interval = 300000  # 5 minutos
            
            if not self.is_synced or utime.ticks_diff(current_time, self.last_sync) >= sync_interval:
                return self.sync()
                
            return True
            
        except Exception as e:
            print(f"[ESP32_ID] Error verificando sync: {e}")
            return False

    def reset(self):
        """Resetea el gestor a estado inicial"""
        try:
            print("[ESP32_ID] Reseteando gestor...")
            
            # Limpiar datos en memoria
            self.esp32_id = None
            self.error_count = 0
            self.last_error = None
            self.is_synced = False
            
            # Eliminar archivos
            try:
                os.remove(self.ID_FILE)
            except:
                pass
                
            try:
                os.remove(self.BACKUP_FILE)
            except:
                pass
                
            # Recolectar basura
            gc.collect()
            
            # Reiniciar proceso de identificación
            return self._load_or_generate_id()
            
        except Exception as e:
            print(f"[ESP32_ID] Error en reset: {e}")
            return False

    def maintenance(self):
        """Realiza tareas de mantenimiento periódicas"""
        try:
            # Verificar memoria
            gc.collect()
            
            # Verificar y limpiar historial si es necesario
            if len(self.id_history) > self.MAX_HISTORY:
                self.id_history = self.id_history[-self.MAX_HISTORY:]
                self._save_with_backup()
            
            # Verificar sincronización
            if not self.check_sync():
                print("[ESP32_ID] Error en sincronización durante mantenimiento")
                
            return True
            
        except Exception as e:
            print(f"[ESP32_ID] Error en mantenimiento: {e}")
            return False

    def process(self):
        """Procesa tareas periódicas del gestor"""
        try:
            current_time = utime.ticks_ms()
            maintenance_interval = 3600000  # 1 hora
            
            # Verificar sync más frecuentemente
            self.check_sync()
            
            # Mantenimiento menos frecuente
            if utime.ticks_diff(current_time, self.last_sync) >= maintenance_interval:
                self.maintenance()
                
            return True
            
        except Exception as e:
            print(f"[ESP32_ID] Error en process: {e}")
            return False