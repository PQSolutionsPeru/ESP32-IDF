from umqtt.robust import MQTTClient
import json
import gc
import utime
import machine
import ubinascii
import random
from config.mqtt_config import MQTTConfig
from config.device_pool_config import DevicePoolConfig

class MQTTManager:
    def __init__(self, wifi_manager):
        """Inicializa el gestor MQTT con configuración"""
        print("[MQTT] Iniciando gestor MQTT...")
        self.wifi_manager = wifi_manager
        self.mqtt_config = MQTTConfig()
        self.device_pool = DevicePoolConfig()
        self.client = None
        self.esp32_id = None
        self.mac_address = ubinascii.hexlify(machine.unique_id()).decode()
        
        # Control de conexión previa
        self.was_previously_connected = False
        
        # Inicialmente sin credenciales - se configuran con set_esp32_id
        self.MQTT_BROKER = self.device_pool.get_broker_config()['host']
        self.MQTT_PORT = self.device_pool.get_broker_config()['port']
        self.MQTT_CLIENT_ID = None
        self.MQTT_USER = None
        self.MQTT_PASSWORD = None
        
        # Información de operación
        self.client_id = None
        self.panel_id = None
        self.operation_mode = 'CONFIG'
        self.message_queue = []
        self._processed_ids = set()
        
        # Control de reportes y timeouts
        self.last_status_report = 0
        self.STATUS_REPORT_INTERVAL = self.mqtt_config.get_status_interval()
        self.RECONNECT_DELAY = self.mqtt_config.get_reconnect_delay()
        self.last_connection_attempt = 0
        self.last_ping_time = 0
        self.last_pong_time = 0
        self.ping_pending = False

        # Estados de conexión
        self.connection_healthy = False
        self.last_activity_time = 0
        self.last_heartbeat_time = 0
        
        # Límites y buffering
        self.MSG_BUFFER_SIZE = self.mqtt_config.get_buffer_size()
        self.MAX_QUEUE_SIZE = self.mqtt_config.get_queue_size()
        self.MAX_PROCESSED_IDS = self.mqtt_config.get_max_processed_ids()
        
        print("[MQTT] Gestor iniciado - esperando ESP32_ID")

    def check_socket(self):
        """Verifica el estado del socket MQTT"""
        try:
            if not self.client or not self.client.sock:
                print("[MQTT] Socket no existe")
                return False
                
            # Intenta enviar un ping para verificar la conexión
            try:
                self.client.ping()
                return True
            except Exception as e:
                print(f"[MQTT] Error en ping del socket: {e}")
                return False
                
        except Exception as e:
            print(f"[MQTT] Error verificando socket: {e}")
            return False

    def send_heartbeat(self):
        """Envía heartbeat al broker MQTT"""
        try:
            if not self.esp32_id:
                return False
                
            heartbeat_msg = {
                'esp32_id': self.esp32_id,
                'status': 'ONLINE',
                'timestamp': utime.ticks_ms(),
                'type': 'heartbeat',
                'message_id': f"hb-{utime.ticks_ms()}-{random.randint(1000,9999)}"
            }
            
            if self.client_id and self.panel_id:
                heartbeat_msg.update({
                    'client_id': self.client_id,
                    'panel_id': self.panel_id
                })
            
            print("[MQTT] Enviando heartbeat...")
            
            # Verificar socket antes de enviar
            if not self.check_socket():
                print("[MQTT] Socket cerrado antes de heartbeat - Reconectando")
                return False
                
            # Reducir QoS a 1 para evitar problemas con confirmaciones
            result = self.publish_event(
                f"system/status/{self.esp32_id}",
                heartbeat_msg,
                qos=1,
                retain=False
            )
            
            if result:
                self.last_heartbeat_time = utime.ticks_ms()
                print("[MQTT] Heartbeat enviado exitosamente")
                return True
            else:
                print("[MQTT] Error enviando heartbeat")
                return False
                
        except Exception as e:
            print(f"[MQTT] Error en heartbeat: {e}")
            import sys
            sys.print_exception(e)
            return False

    def check_connection(self):
        """Verifica si hay conexión MQTT usando check_msg periódico con más tolerancia"""
        try:
            if not self.client:
                return False
                    
            current_time = utime.ticks_ms()
            health_timeout = self.mqtt_config.get_health_timeout()
            
            # Primero verificar WiFi (esto es crucial)
            if not self.wifi_manager.check_connection():
                print("[MQTT] Sin conexión WiFi")
                return False
                    
            # Inicializar contadores de error si no existen
            if not hasattr(self, '_last_socket_check'):
                self._last_socket_check = 0
                self._socket_errors = 0
                self._network_errors = 0
                self._general_errors = 0
                self._heartbeat_errors = 0
                self._last_heartbeat_attempt = 0
                
            # Verificar estado del socket con menos frecuencia (más tolerancia)
            if utime.ticks_diff(current_time, self._last_socket_check) > 5000:  # Cada 5 segundos
                self._last_socket_check = current_time
                
                if not self.check_socket():
                    # Solo registrar como error, pero no fallar inmediatamente
                    print("[MQTT] Socket MQTT no saludable")
                    self._socket_errors += 1
                    
                    # Solo reconectar después de varios errores consecutivos
                    if self._socket_errors >= 3:
                        print(f"[MQTT] Múltiples errores de socket ({self._socket_errors}), reconectando...")
                        self.connection_healthy = False
                        self.reconnect()
                        return False
                else:
                    # Resetear contador si el socket está bien
                    self._socket_errors = 0
            
            try:
                # Verificar mensajes, pero manejar errores con más tolerancia
                try:
                    self.client.check_msg()
                    
                    # Actualizar timestamp de última actividad exitosa
                    self.last_activity_time = current_time
                    self.connection_healthy = True
                    
                except OSError as e:
                    print(f"[MQTT] Error de red: {e}")
                    self._network_errors += 1
                    
                    # Solo tomar acción si hemos superado el timeout y tenemos varios errores
                    if (utime.ticks_diff(current_time, getattr(self, 'last_activity_time', 0)) > health_timeout and
                        self._network_errors >= 3):
                        print("[MQTT] Conexión perdida - Iniciando reconexión")
                        self.connection_healthy = False
                        self.reconnect()
                        return False
                except Exception as e:
                    print(f"[MQTT] Error verificando mensajes: {e}")
                    self._general_errors += 1
                    
                    # Solo fallar después de múltiples errores
                    if self._general_errors >= 3:
                        self.connection_healthy = False
                        return False
                    
                # Verificar si toca enviar heartbeat (con intervalo mínimo entre envíos)
                heartbeat_interval = self.mqtt_config.get_status_interval()
                min_heartbeat_interval = 30000  # No enviar heartbeats más frecuentes que cada 30 segundos
                
                if not hasattr(self, '_last_heartbeat_attempt'):
                    self._last_heartbeat_attempt = 0
                
                if (utime.ticks_diff(current_time, getattr(self, 'last_heartbeat_time', 0)) >= heartbeat_interval and
                    utime.ticks_diff(current_time, self._last_heartbeat_attempt) >= min_heartbeat_interval):
                    
                    self._last_heartbeat_attempt = current_time
                    if not self.send_heartbeat():
                        self._heartbeat_errors += 1
                        if self._heartbeat_errors >= 3:
                            self.reconnect()
                            return False
                    else:
                        self._heartbeat_errors = 0
                
                return self.connection_healthy
                            
            except Exception as e:
                print(f"[MQTT] Error verificando conexión: {e}")
                self.connection_healthy = False
                return False

        except Exception as e:
            print(f"[MQTT] Error general en check_connection: {e}")
            return False

    def ensure_connection(self):
        """Asegura que hay conexión MQTT con manejo de errores"""
        if not self.wifi_manager.check_connection():
            print("[MQTT] Sin conexión WiFi")
            return False
            
        try:
            if self.client:
                return True
                
            current_time = utime.ticks_ms()
            if utime.ticks_diff(current_time, self.last_connection_attempt) < self.RECONNECT_DELAY:
                return False
                
            print("[MQTT] Intentando nueva conexión...")
            self.last_connection_attempt = current_time
            return self.connect()
                
        except Exception as e:
            print(f"[MQTT] Error en ensure_connection: {e}")
            return False

    def set_esp32_id(self, esp32_id):
        """Configura las credenciales MQTT basadas en el ESP32_ID"""
        try:
            print(f"[MQTT] Configurando credenciales para {esp32_id}")
            
            if not esp32_id:
                print("[MQTT] Error: ESP32_ID inválido")
                return False
                
            # Asignar ESP32_ID
            self.esp32_id = esp32_id
            
            # Obtener credenciales del pool
            credentials = self.device_pool.assign_device(self.mac_address, esp32_id)
            if not credentials:
                print("[MQTT] Error: No se pudieron obtener credenciales")
                return False
                
            # Configurar credenciales
            self.MQTT_CLIENT_ID = credentials['client_id']
            self.MQTT_USER = credentials['user']
            self.MQTT_PASSWORD = credentials['password']
            
            print(f"[MQTT] Credenciales configuradas exitosamente para {esp32_id}")
            print(f"[MQTT] Client ID: {self.MQTT_CLIENT_ID}")
            print(f"[MQTT] Usuario: {self.MQTT_USER}")
            
            return True
            
        except Exception as e:
            print(f"[MQTT] Error configurando credenciales: {e}")
            return False

    def connect(self):
        """Conecta al broker MQTT con optimización de memoria"""
        try:
            print("[MQTT] Iniciando conexión optimizada...")
            
            # Verificar credenciales
            if not all([self.MQTT_CLIENT_ID, self.MQTT_USER, self.MQTT_PASSWORD]):
                print("[MQTT] Error: Credenciales no configuradas")
                return False
                
            # Verificar WiFi
            if not self.wifi_manager.check_connection():
                print("[MQTT] Error: Sin conexión WiFi")
                return False
            
            # Limpiar cliente anterior completamente
            if self.client:
                try:
                    self.client.disconnect()
                except:
                    pass
                self.client = None
            
            # Forzar liberación de memoria
            import gc
            import utime
            
            # Limpiar colas de mensajes
            if hasattr(self, 'message_queue'):
                self.message_queue.clear()
            if hasattr(self, '_processed_ids'):
                self._processed_ids.clear()
            
            # Limpieza intensiva
            for _ in range(5):
                gc.collect()
                utime.sleep_ms(100)
                
            print(f"[MQTT] Memoria antes de SSL: {gc.mem_free()} bytes")
            
            # Obtener SSL simplificado directamente
            from mqtt_ssl_setup import get_ssl_params
            ssl_params = get_ssl_params()
            
            # Usar MQTTClient simple en lugar del robusto para ahorrar memoria
            print("[MQTT] Importando cliente MQTT simple...")
            from umqtt.simple import MQTTClient
            
            print(f"[MQTT] Memoria después de importar: {gc.mem_free()} bytes")
            
            # Retrying with backoff exponential
            retry_count = 0
            max_retries = 3
            base_delay = 2000  # 2 segundos
            
            while retry_count < max_retries:
                try:
                    # Limpiar memoria antes de cada intento
                    gc.collect()
                    utime.sleep_ms(200)
                    
                    print(f"[MQTT] Intento {retry_count + 1}/{max_retries}")
                    
                    # Crear cliente con configuración mínima
                    print("[MQTT] Creando cliente simple...")
                    self.client = MQTTClient(
                        client_id=self.MQTT_CLIENT_ID,
                        server=self.MQTT_BROKER,
                        port=self.MQTT_PORT,
                        user=self.MQTT_USER,
                        password=self.MQTT_PASSWORD,
                        keepalive=60,
                        ssl=ssl_params
                    )
                    
                    # IMPORTANTE: Configurar Last Will and Testament (LWT) ANTES de conectar
                    if self.esp32_id:
                        print("[MQTT] Configurando Last Will Testament...")
                        lwt_topic = f"system/status/{self.esp32_id}"
                        lwt_message = json.dumps({
                            'esp32_id': self.esp32_id,
                            'status': 'OFFLINE',
                            'type': 'lwt',
                            'client_id': self.client_id,
                            'panel_id': self.panel_id,
                            'message_id': f"lwt-{self.esp32_id}-{random.randint(1000,9999)}"
                        })
                        self.client.set_last_will(lwt_topic, lwt_message, False, 2)
                        print(f"[MQTT] LWT configurado para tópico: {lwt_topic}")
                    else:
                        print("[MQTT] Advertencia: No hay ESP32_ID para configurar LWT")
                    
                    print(f"[MQTT] Memoria antes de conectar: {gc.mem_free()} bytes")
                    
                    # Intentar conectar
                    print("[MQTT] Intentando conexión...")
                    self.client.connect()
                    print("[MQTT] Conectado exitosamente!")
                    
                    # Configurar callback básico
                    if self.esp32_id:
                        config_topic = f"esp32/config/{self.esp32_id}"
                        self.client.set_callback(self._handle_config_message)
                        self.client.subscribe(config_topic)
                        print(f"[MQTT] Suscrito a: {config_topic}")
                    
                    # Configurar estados
                    self.connection_healthy = True
                    self.last_activity_time = utime.ticks_ms()
                    self.was_previously_connected = True
                    
                    # Enviar información de red y estado ONLINE después de conectar
                    self.send_network_info()
                    self.send_heartbeat()  # Añadir envío de heartbeat inmediato
                    
                    return True
                    
                except MemoryError as e:
                    print(f"[MQTT] Error de memoria en intento {retry_count + 1}: {e}")
                    if self.client:
                        try:
                            self.client.disconnect()
                        except:
                            pass
                        self.client = None
                    
                    # Limpieza agresiva
                    for _ in range(10):
                        gc.collect()
                        utime.sleep_ms(200)
                    
                    # Esperar con backoff exponencial
                    delay = base_delay * (2 ** retry_count)
                    print(f"[MQTT] Esperando {delay}ms antes del siguiente intento...")
                    utime.sleep_ms(delay)
                    retry_count += 1
                    
                except Exception as e:
                    print(f"[MQTT] Error en intento {retry_count + 1}: {e}")
                    import sys
                    sys.print_exception(e)
                    
                    if self.client:
                        self.client = None
                    gc.collect()
                    
                    # Esperar con backoff exponencial
                    delay = base_delay * (2 ** retry_count)
                    utime.sleep_ms(delay)
                    retry_count += 1
            
            print("[MQTT] Fallaron todos los intentos de conexión")
            return False
                
        except Exception as e:
            print(f"[MQTT] Error general en connect: {e}")
            import sys
            sys.print_exception(e)
            return False

    def set_relay_manager(self, relay_manager):
        """Establece la referencia al gestor de relays"""
        self.relay_manager = relay_manager

    def _handle_config_message(self, topic, msg):
        try:
            print(f"[MQTT] Mensaje de configuración recibido en: {topic}")
            print(f"[MQTT] Contenido del mensaje: {msg}")
            
            # Decodificar mensaje
            try:
                msg_str = msg.decode()
                print(f"[MQTT] Mensaje decodificado: {msg_str}")
                config = json.loads(msg_str)
                print(f"[MQTT] Configuración parseada exitosamente")
            except Exception as e:
                print(f"[MQTT] Error decodificando mensaje: {e}")
                return

            # Validar estructura del mensaje
            required_fields = ['status', 'client_id', 'panel_id', 'mqtt', 'relays']
            missing_fields = [field for field in required_fields if field not in config]
            if missing_fields:
                print(f"[MQTT] Campos faltantes en la configuración: {missing_fields}")
                return

            # Procesar configuración
            if config['status'] == 'REGISTERED':
                print("[MQTT] Aplicando configuración...")
                
                # Guardar configuración
                self.client_id = config['client_id']
                self.panel_id = config['panel_id']
                self.relay_config = config['relays']
                
                # Configurar tópicos MQTT
                mqtt_config = config['mqtt']['topics']
                self.topics = {
                    'status': mqtt_config['status'],
                    'relays': mqtt_config['relays'],
                    'config': mqtt_config['config'],
                    'response': f"{mqtt_config['config']}/response"
                }
                print(f"[MQTT] Tópicos configurados: {self.topics}")
                
                # Enviar confirmación
                self.publish_event(
                    self.topics['response'],
                    {
                        'esp32_id': self.esp32_id,
                        'status': 'CONFIG_ACCEPTED',
                        'client_id': self.client_id,
                        'panel_id': self.panel_id,
                        'timestamp': utime.ticks_ms(),
                        'message_id': f"{utime.ticks_ms()}-{random.randint(1000,9999)}"
                    }
                )
                
                # Publicar estado actual de los relays una sola vez al iniciar
                relay_states = {}
                if hasattr(self, 'relay_manager'):
                    relay_states = self.relay_manager.get_all_states()
                
                # Enviar estado inicial después de aceptar config
                self.publish_event(
                    self.topics['relays'],
                    {
                        'esp32_id': self.esp32_id,
                        'relay_states': relay_states,
                        'timestamp': utime.ticks_ms(),
                        'message_id': f"init-{utime.ticks_ms()}-{random.randint(1000,9999)}",
                        'type': 'initial_status'
                    },
                    qos=1
                )
                
                print("[MQTT] Configuración aplicada exitosamente")
                self.operation_mode = 'RUNNING'
                
            else:
                print(f"[MQTT] Estado no reconocido: {config['status']}")

        except Exception as e:
            print(f"[MQTT] Error procesando configuración: {e}")
            import sys
            sys.print_exception(e)

    def send_network_info(self):
        """Envía información de red al broker para iniciar el proceso de configuración"""
        try:
            if not self.esp32_id:
                print("[MQTT] Error: No hay ESP32_ID para enviar")
                return False
                    
            # Obtener IP actualizada
            ip = self.wifi_manager.get_current_ip()
            
            # Crear mensaje
            message = {
                "esp32_id": self.esp32_id,
                "MAC": self.mac_address,
                "IP": ip,
                "status": "ONLINE",  # Cambiar status a ONLINE para que la VM registre el cambio de estado
                "timestamp": utime.ticks_ms(),
                "version": "1.0.0"  # Añadir versión para trazabilidad
            }
            
            print(f"[MQTT] Enviando información de red: {json.dumps(message)}")
            
            # Enviar al tema de registro
            result = self.publish_event(
                "esp32/network_info",
                message,
                qos=1,
                retain=False
            )
            
            if result:
                print("[MQTT] Información de red enviada correctamente")
                return True
            else:
                print("[MQTT] Error al enviar información de red")
                return False
        
        except Exception as e:
            print(f"[MQTT] Error enviando información de red: {e}")
            import sys
            sys.print_exception(e)
            return False

    def subscribe(self, topic, callback=None):
        """Suscribe a tópico con verificación de conexión"""
        try:
            print(f"[MQTT] Intentando suscribirse a: {topic}")
            
            if not self.ensure_connection():
                print("[MQTT] Error: No hay conexión para suscribirse")
                return False
                
            if callback:
                print("[MQTT] Configurando callback")
                self.client.set_callback(callback)
                
            print("[MQTT] Ejecutando suscripción...")
            self.client.subscribe(topic.encode(), qos=1)
            print("[MQTT] Suscripción exitosa")
            return True
                
        except Exception as e:
            print(f"[MQTT] Error suscribiendo: {e}")
            return False

    def publish_event(self, topic, message, qos=1, retain=False):
        """Publica evento MQTT con mejor manejo de errores y logging"""
        try:
            print(f"[MQTT] Intentando publicar en tópico: {topic}")
            
            # Limpiar mensaje antes de publicar
            if isinstance(message, dict):
                # Eliminar campos no esenciales para reducir tamaño
                for key in ['debug_info', 'metadata', 'trace']:
                    message.pop(key, None)
                
                # Convertir timestamps a enteros si existen
                if 'timestamp' in message and isinstance(message['timestamp'], dict):
                    message['timestamp'] = message['timestamp'].get('value', 0)

            # Convertir mensaje a JSON minificado
            msg_str = json.dumps(message, separators=(',', ':'))

            # Verificar tamaño del mensaje
            if len(msg_str) > self.MSG_BUFFER_SIZE:
                print(f"[MQTT] Error: Mensaje excede el tamaño máximo: {len(msg_str)} > {self.MSG_BUFFER_SIZE}")
                return False

            # Verificar memoria disponible
            if gc.mem_free() < len(msg_str) + 1024:  # 1KB extra para operaciones
                gc.collect()
                utime.sleep_ms(100)
                if gc.mem_free() < len(msg_str) + 1024:
                    print("[MQTT] Memoria insuficiente para publicar mensaje")
                    return False

            # Verificar socket y conexión
            if not self.check_socket():
                print("[MQTT] Socket no válido para publicar")
                if not self.reconnect():
                    if len(self.message_queue) < self.MAX_QUEUE_SIZE:
                        self.message_queue.append((topic, message, qos, retain))
                        print("[MQTT] Mensaje agregado a cola de reintentos")
                    else:
                        print("[MQTT] Cola de mensajes llena")
                    return False

            try:
                # Codificar mensaje y topic
                msg_bytes = msg_str.encode()
                topic_bytes = topic.encode() if isinstance(topic, str) else topic
                
                # Publicar con manejo de errores mejorado
                gc.collect()  # GC antes de publicar
                
                try:
                    # CAMBIO IMPORTANTE: El método publish no retorna un valor booleano
                    # Solo lanzará una excepción si falla
                    self.client.publish(topic_bytes, msg_bytes, qos=qos, retain=retain)
                    
                    # Si llegamos aquí sin excepción, la publicación fue exitosa
                    print("[MQTT] Mensaje publicado exitosamente")
                    
                    # Actualizar timestamp solo si es mensaje de estado
                    if any(key in message for key in ['status', 'relay_states']):
                        self.last_status_report = utime.ticks_ms()
                    
                    # Procesar cola de mensajes pendientes si hay conexión estable
                    while self.message_queue and self.check_socket():
                        queued_topic, queued_msg, queued_qos, queued_retain = self.message_queue.pop(0)
                        self.publish_event(queued_topic, queued_msg, queued_qos, queued_retain)
                    
                    return True
                    
                except Exception as e:
                    print(f"[MQTT] Error al publicar: {e}")
                    return False

            except OSError as e:
                print(f"[MQTT] Error de red: {e}")
                if len(self.message_queue) < self.MAX_QUEUE_SIZE:
                    self.message_queue.append((topic, message, qos, retain))
                self.reconnect()
                return False

        except Exception as e:
            print(f"[MQTT] Error crítico: {e}")
            if len(self.message_queue) < self.MAX_QUEUE_SIZE:
                self.message_queue.append((topic, message, qos, retain))
            return False

        finally:
            gc.collect()  # Asegurar limpieza después de publicar

    def check_msg(self):
        """Verifica mensajes pendientes con mejor manejo de errores"""
        try:
            if not self.ensure_connection():
                print("[MQTT] Sin conexión al verificar mensajes")
                return False
                
            self.client.sock.setblocking(False)
            
            try:
                result = self.client.check_msg()
                return result
            except OSError as e:
                print(f"[MQTT] Error de red en check_msg: {e}")
                self.reconnect()
                return False
                
        except Exception as e:
            print(f"[MQTT] Error crítico en check_msg: {e}")
            self.reconnect()
            return False

    def check_status_report(self):
        """Verifica si es momento de enviar reporte de estado"""
        current_time = utime.ticks_ms()
        if utime.ticks_diff(current_time, self.last_status_report) >= self.STATUS_REPORT_INTERVAL:
            if self.publish_status({}):
                self.last_status_report = current_time

    def publish_status(self, relay_states):
        """Publica estado de relés y sistema"""
        if not self.client_id or not self.panel_id:
            return False
            
        try:
            gc.collect()
            message = {
                'esp32_id': self.esp32_id,
                'relay_states': relay_states,
                'system': {
                    'memory_free': gc.mem_free(),
                    'memory_alloc': gc.mem_alloc(),
                    'uptime': utime.ticks_ms() // 1000
                },
                'message_id': f"{utime.ticks_ms()}-{random.randint(1000,9999)}",
                'timestamp': utime.ticks_ms()
            }
            
            return self.publish_event(
                f"clients/{self.client_id}/panels/{self.panel_id}",
                message,
                qos=1
            )
            
        except Exception as e:
            print(f"[MQTT] Error publicando estado: {e}")
            return False

    def _setup_lwt(self):
        """Configura Last Will Testament con mejor manejo"""
        if not self.esp32_id:
            return

        try:
            # Configurar mensaje OFFLINE con el timestamp en 0 para que se asigne al momento de la desconexión
            offline_msg = {
                'esp32_id': self.esp32_id,
                'status': 'OFFLINE',
                'type': 'lwt',
                'client_id': self.client_id,
                'panel_id': self.panel_id,
                'message_id': f"lwt-{self.esp32_id}-{random.randint(1000,9999)}"
            }
            
            # Guardar mensaje LWT para uso posterior
            self.lwt_message = offline_msg
            self.lwt_topic = f"system/status/{self.esp32_id}"
            
            # Configurar LWT con QoS 2 para garantizar entrega
            self.client.set_last_will(
                self.lwt_topic,
                json.dumps(offline_msg),
                retain=False,
                qos=2
            )
            
        except Exception as e:
            print(f"[MQTT] Error en LWT: {e}")

    def reconnect(self):
        """Intenta reconexión con backoff exponencial mejorado"""
        if len(self.message_queue) >= self.MAX_QUEUE_SIZE:
            print("[MQTT] Cola de mensajes llena, limpiando mensajes antiguos")
            self.message_queue = self.message_queue[-self.MAX_QUEUE_SIZE:]
            
        try:
            if self.client:
                try:
                    self.client.disconnect()
                except:
                    pass
                    
            retry_count = 0
            retry_delay = self.mqtt_config.get_initial_retry_delay()
            max_retry_delay = self.mqtt_config.get_max_retry_delay()
            
            while retry_count < self.mqtt_config.get_max_retries():
                try:
                    print(f"[MQTT] Intento de reconexión {retry_count + 1}")
                    # Mantener was_previously_connected en True durante reconexiones
                    self.was_previously_connected = True
                    if self.connect():
                        while self.message_queue:
                            topic, msg, qos, retain = self.message_queue.pop(0)
                            if not self.publish_event(topic, msg, qos, retain):
                                self.message_queue.insert(0, (topic, msg, qos, retain))
                                break
                        return True
                        
                except Exception as e:
                    print(f"[MQTT] Error en intento {retry_count + 1}: {e}")
                    
                retry_count += 1
                utime.sleep_ms(retry_delay)
                retry_delay = min(retry_delay * 2, max_retry_delay)
                
            print("[MQTT] Máximo de reintentos alcanzado")
            return False
                
        except Exception as e:
            print(f"[MQTT] Error en reconnect: {e}")
            return False

    def disconnect(self):
        """Disconnects from WiFi and cleans up"""
        try:
            if self.client and self.client.isconnected():
                self.client.disconnect()
                self.client.active(False)
                utime.sleep_ms(500)
            # Resetear el estado de conexión previa
            self.was_previously_connected = False
            return True
        except Exception as e:
            print(f"[MQTT] Error disconnecting: {e}")
            return False

    def close(self):
        """Cierra conexión MQTT y limpia recursos"""
        try:
            if self.client:
                try:
                    # Publicar desconexión limpia
                    self.publish_event(
                        f"system/status/{self.esp32_id}",
                        {
                            "esp32_id": self.esp32_id,
                            "status": "OFFLINE",
                            "message_id": f"{utime.ticks_ms()}-{random.randint(1000,9999)}",
                            "timestamp": utime.ticks_ms()
                        },
                        qos=1
                    )
                    utime.sleep_ms(500)
                    self.client.disconnect()
                except:
                    pass
                self.client = None
            
            # Liberar del pool
            if self.esp32_id:
                self.device_pool.release_device(self.mac_address)
            
            self._processed_ids.clear()
            self.message_queue.clear()
            gc.collect()
            
            print("[MQTT] Recursos liberados correctamente")
            return True
            
        except Exception as e:
            print(f"[MQTT] Error en close: {e}")
            return False

    def get_mac(self):
        """Obtiene MAC address"""
        return self.mac_address

    def get_ip_address(self):
        """Obtiene IP actual"""
        return self.wifi_manager.current_ip if self.wifi_manager else None