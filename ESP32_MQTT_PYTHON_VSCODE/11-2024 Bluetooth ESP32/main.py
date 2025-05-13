import machine
import utime
import gc
import random
import sys
from wifi_manager import WiFiManager
from mqtt_manager import MQTTManager
from relay_manager import RelayManager
from watchdog_manager import WatchdogManager
from esp32_id_manager import ESP32IdManager
from bluetooth_manager import BluetoothManager
from time_manager import TimeManager

# System Constants
MAX_LOOP_TIME = 1000           # 1000ms maximum per cycle
MIN_MEMORY_THRESHOLD = 15000   # 15KB minimum free memory
MQTT_RETRY_COUNT = 5           # 5 MQTT attempts
WIFI_RETRY_COUNT = 5           # 5 WiFi attempts
WATCHDOG_TIMEOUT = 60000       # 60 seconds watchdog
CHECK_INTERVAL = 2000          # 2 seconds between checks
STARTUP_DELAY = 5000           # 5 seconds at startup
BLE_CONFIG_TIMEOUT = 300000    # 5 minutes for BLE configuration

# Estado global para tracking
_last_state_check = 0

class SystemState:
    INITIAL = "initial"
    CONFIG = "config"
    RUNNING = "running"
    ERROR = "error"

def check_memory():
    """Checks system memory state"""
    free_mem = gc.mem_free()
    print(f"[MEMORY] Free memory: {free_mem} bytes")
    if free_mem < MIN_MEMORY_THRESHOLD:
        print("[MEMORY] Low memory, performing cleanup")
        gc.collect()
        utime.sleep_ms(100)
        free_mem = gc.mem_free()
        print(f"[MEMORY] Memory after cleanup: {free_mem} bytes")
        return free_mem >= MIN_MEMORY_THRESHOLD
    return True

def prepare_for_mqtt(managers):
    """
    Prepara la memoria para conexión MQTT con técnicas avanzadas
    de desfragmentación.
    """
    import gc
    import utime
    
    print("[MEMORY] Preparando memoria para MQTT...")
    
    # Limpiar módulo bluetooth del sistema si existe
    try:
        if 'bluetooth' in sys.modules:
            print("[MEMORY] Eliminando módulo bluetooth...")
            del sys.modules['bluetooth']
            gc.collect()
    except Exception as e:
        print(f"[MEMORY] Error eliminando módulo bluetooth: {e}")
    
    # Otros módulos BLE que pueden consumir memoria
    for module_name in ['ble_advertising', 'ble_uart_peripheral']:
        try:
            if module_name in sys.modules:
                print(f"[MEMORY] Eliminando módulo {module_name}...")
                del sys.modules[module_name]
        except:
            pass
    
    print(f"[MEMORY] Memoria inicial: {gc.mem_free()} bytes")
    
    # Ciclos de limpieza inicial
    for _ in range(5):
        gc.collect()
        utime.sleep_ms(100)
    
    # Desfragmentación con bloques de mayor a menor tamaño
    print("[MEMORY] Iniciando desfragmentación...")
    
    # Primer ciclo: bloques grandes (para SSL handshake)
    try:
        print("[MEMORY] Ciclo 1: Bloques grandes (25KB)")
        big_blocks = []
        for _ in range(3):
            try:
                big_blocks.append(bytearray(25 * 1024))  # 25KB
            except MemoryError:
                break
        
        print(f"[MEMORY] Creados {len(big_blocks)} bloques de 25KB")
        # Liberar explícitamente
        while big_blocks:
            del big_blocks[-1]
        big_blocks = None
        gc.collect()
        utime.sleep_ms(100)
    except Exception as e:
        print(f"[MEMORY] Error en ciclo 1: {e}")
    
    # Segundo ciclo: bloques medianos
    try:
        print("[MEMORY] Ciclo 2: Bloques medianos (10KB)")
        mid_blocks = []
        for _ in range(8):
            try:
                mid_blocks.append(bytearray(10 * 1024))  # 10KB
            except MemoryError:
                break
        
        print(f"[MEMORY] Creados {len(mid_blocks)} bloques de 10KB")
        # Liberar explícitamente
        while mid_blocks:
            del mid_blocks[-1]
        mid_blocks = None
        gc.collect()
        utime.sleep_ms(100)
    except Exception as e:
        print(f"[MEMORY] Error en ciclo 2: {e}")
    
    # Tercer ciclo: bloques pequeños
    try:
        print("[MEMORY] Ciclo 3: Bloques pequeños (1KB)")
        small_blocks = []
        for _ in range(40):
            try:
                small_blocks.append(bytearray(1024))  # 1KB
            except MemoryError:
                break
        
        print(f"[MEMORY] Creados {len(small_blocks)} bloques de 1KB")
        # Liberar explícitamente
        while small_blocks:
            del small_blocks[-1]
        small_blocks = None
        gc.collect()
        utime.sleep_ms(100)
    except Exception as e:
        print(f"[MEMORY] Error en ciclo 3: {e}")
    
    # Ciclo final: intentar un bloque SSL grande nuevamente
    ssl_block = None
    try:
        ssl_block = bytearray(25 * 1024)  # Típicamente necesario para SSL
        print("[MEMORY] ✓ Bloque SSL (25KB) creado exitosamente")
        del ssl_block
        ssl_block = None
    except MemoryError:
        print("[MEMORY] ✗ No se pudo crear bloque SSL de 25KB")
    
    # Limpieza final
    for _ in range(5):
        gc.collect()
        utime.sleep_ms(100)
    
    print(f"[MEMORY] Memoria final: {gc.mem_free()} bytes")
    return gc.mem_free()

def setup_relay_monitoring(managers, esp32_id):
    try:
        print("\n[RELAY] Configuring relays...")
        managers["relay"] = RelayManager()
        managers["mqtt"].set_relay_manager(managers["relay"])
        relay_config = managers["relay"].config
        
        # Controladores de estado para evitar duplicados
        last_relay_states = {}  # Almacena el último estado enviado
        last_send_times = {}    # Almacena el último tiempo de envío por relé
        MIN_SEND_INTERVAL = 2000  # Intervalo mínimo entre envíos (2 segundos)
        
        def relay_callback(pin, pin_num):
            try:
                pin_names = relay_config.get_relay_pins()
                pin_name = pin_names.get(pin_num, str(pin_num))
                current_time = utime.ticks_ms()
                
                # Leer estado directamente del pin
                current_state = "OK" if pin.value() == 0 else "DISC"
                
                # Solo enviar si:
                # 1. Es el primer mensaje para este relé, o
                # 2. El estado ha cambiado desde el último envío, y
                # 3. Ha pasado suficiente tiempo desde el último envío
                
                if (
                    pin_name not in last_relay_states or 
                    (current_state != last_relay_states[pin_name] and 
                     utime.ticks_diff(current_time, last_send_times.get(pin_name, 0)) > MIN_SEND_INTERVAL)
                ):
                    print(f"[RELAY] Sending change notification for {pin_name}: {current_state}")
                    # Actualizar registros
                    last_relay_states[pin_name] = current_state
                    last_send_times[pin_name] = current_time
                    
                    if managers["mqtt"].client_id and managers["mqtt"].panel_id:
                        message = {
                            "esp32_id": esp32_id,
                            "relay": pin_name,
                            "state": current_state,
                            "timestamp": {
                                "value": current_time,
                                "type": "realtime"
                            }
                        }
                        
                        managers["mqtt"].publish_event(
                            f"clients/{managers['mqtt'].client_id}/panels/{managers['mqtt'].panel_id}",
                            message,
                            retain=False,
                            qos=1
                        )
                else:
                    # Registrar que se evitó un duplicado
                    if pin_name in last_relay_states and current_state == last_relay_states[pin_name]:
                        time_since_last = utime.ticks_diff(current_time, last_send_times.get(pin_name, 0))
                        print(f"[RELAY] Skipping duplicate notification for {pin_name} (same state: {current_state}, {time_since_last}ms since last)")
                    else:
                        time_since_last = utime.ticks_diff(current_time, last_send_times.get(pin_name, 0)) 
                        print(f"[RELAY] Debounce active for {pin_name}, waiting more time (elapsed: {time_since_last}ms)")
                        
            except Exception as e:
                print(f"[RELAY] Callback error: {e}")
                sys.print_exception(e)
        
        # Configure each relay using config
        pin_config = relay_config.get_relay_pins()
        for pin_num, pin_name in pin_config.items():
            relay_pin = managers["relay"].setup_relay(pin_num, relay_callback)
            print(f"[RELAY] Configured relay on pin {pin_num} ({pin_name})")
            
            # Get initial state
            pin_value = relay_pin.value()
            initial_state = "OK" if pin_value == 0 else "DISC"
            
            # Inicializar estados
            last_relay_states[pin_name] = initial_state
            last_send_times[pin_name] = utime.ticks_ms()
            
            print(f"[RELAY] Initial state of relay {pin_num} ({pin_name}): {initial_state}")
            
        return True
        
    except Exception as e:
        print(f"[RELAY] Setup error: {e}")
        sys.print_exception(e)
        return False

def pre_init_cleanup():
    """Limpieza inicial del sistema antes de cualquier inicialización"""
    try:
        # Forzar recolección de basura inicial
        gc.collect()
        utime.sleep_ms(1000)
        
        # Intentar liberar recursos WiFi previos
        try:
            import network
            sta_if = network.WLAN(network.STA_IF)
            if sta_if:
                sta_if.active(False)
                utime.sleep_ms(1000)
                del sta_if
        except:
            pass
        
        # Segunda recolección después de liberar WiFi
        gc.collect()
        utime.sleep_ms(1000)
        
        print(f"[INIT] Memoria disponible después de limpieza: {gc.mem_free()} bytes")
        return True
    except Exception as e:
        print(f"[INIT] Error en limpieza inicial: {e}")
        return False

def initialize_system():
    """Inicializa el sistema con manejo mejorado de recursos"""
    managers = {}
    
    try:
        print("\n[INIT] Iniciando sistema...")
        print(f"[INIT] Memoria inicial: {gc.mem_free()} bytes")
        
        # Limpieza previa
        if not pre_init_cleanup():
            raise Exception("Fallo en limpieza inicial")
            
        # 1. Iniciar WatchdogManager primero
        print("[INIT] Iniciando WatchdogManager...")
        managers["watchdog"] = WatchdogManager()
        managers["watchdog"].feed()
        
        # 2. Iniciar WiFiManager
        print("[INIT] Iniciando WiFiManager...")
        for attempt in range(3):
            try:
                managers["watchdog"].feed()
                managers["wifi"] = WiFiManager()
                if managers["wifi"].sta_if:
                    print("[INIT] WiFiManager iniciado correctamente")
                    break
            except Exception as e:
                print(f"[INIT] Error en intento WiFi {attempt + 1}: {e}")
                gc.collect()
                utime.sleep_ms(2000)
                
        if "wifi" not in managers:
            raise Exception("No se pudo iniciar WiFiManager")
        
        # 3. Iniciar ESP32IdManager y obtener ID
        print("[INIT] Iniciando ESP32IdManager...")
        managers["esp32_id"] = ESP32IdManager()
        esp32_id = managers["esp32_id"].get_id()
        if not esp32_id:
            raise Exception("No se pudo obtener ESP32_ID")
        print(f"[INIT] ESP32_ID obtenido: {esp32_id}")
        
        # 4. Iniciar MQTTManager y configurar credenciales
        print("[INIT] Iniciando MQTTManager...")
        managers["mqtt"] = MQTTManager(managers["wifi"])
        if not managers["mqtt"].set_esp32_id(esp32_id):
            raise Exception("No se pudieron configurar credenciales MQTT")
        print("[INIT] Credenciales MQTT configuradas")
        
        # 5. Resto de managers
        managers["time"] = TimeManager(managers["wifi"])
        
        print("[INIT] Inicialización completada")
        return managers
        
    except Exception as e:
        print(f"[INIT] Error fatal en inicialización: {e}")
        if "watchdog" in managers:
            try:
                managers["watchdog"].force_reset("init_error")
            except:
                pass
        machine.reset()

def setup_wifi_mode(managers, bluetooth_manager):
    """Sets up WiFi configuration mode using BLE"""
    try:
        print("[CONFIG] Starting BLE configuration mode")

        ble_timeout = BLE_CONFIG_TIMEOUT
        start_time = utime.ticks_ms()
        
        while utime.ticks_diff(utime.ticks_ms(), start_time) < ble_timeout:
            managers["watchdog"].feed()
            
            if bluetooth_manager.check_timeout():
                print("[CONFIG] BLE timeout reached")
                break
            
            data = bluetooth_manager.wait_for_data()
            if data and 'ssid' in data and 'password' in data:
                ssid = data['ssid'].strip()
                password = data['password'].strip()
                
                print(f"[CONFIG] Received WiFi credentials. SSID: {ssid}")
                
                if not ssid or not password or len(password) < 8:
                    bluetooth_manager.write_data("error:invalid_credentials")
                    continue
                    
                print(f"[CONFIG] Attempting to connect to WiFi: {ssid}")
                if managers["wifi"].connect_wifi(ssid, password):
                    print(f"[CONFIG] Successfully connected to WiFi: {ssid}")
                    managers["wifi"].save_wifi_config(ssid, password)
                    bluetooth_manager.write_data("status:wifi_connected")
                    utime.sleep_ms(1000)
                    return True
                else:
                    print(f"[CONFIG] Failed to connect to WiFi: {ssid}")
                    bluetooth_manager.write_data("error:wifi_failed")
            
            utime.sleep_ms(100)
            
        print("[CONFIG] BLE configuration mode exited")
        return False
        
    except Exception as e:
        print(f"[CONFIG] Error in BLE configuration: {e}")
        sys.print_exception(e)
        return False

def setup_mqtt_connection(managers):
    """Configura la conexión MQTT usando las credenciales del ESP32_ID"""
    try:
        managers["watchdog"].feed()
        esp32_id = managers["esp32_id"].get_id()
        
        if not esp32_id:
            print("[MQTT] Error: No ESP32 ID available")
            return False
            
        print(f"[MQTT] Configurando conexión para ESP32 ID: {esp32_id}")
        
        # IMPORTANTE: Preparación de memoria antes de MQTT
        import gc
        import utime
        print(f"[MQTT] Memoria antes de preparación: {gc.mem_free()} bytes")
        prepare_for_mqtt(managers)
        print(f"[MQTT] Memoria después de preparación: {gc.mem_free()} bytes")
        
        # Intentar conexión MQTT con las nuevas credenciales
        if not managers["mqtt"].connect():
            print("[MQTT] Error: No se pudo establecer conexión MQTT")
            return False
            
        # Suscripción al tópico de configuración
        config_topic = f"esp32/config/{esp32_id}"
        if not managers["mqtt"].subscribe(config_topic):
            print("[MQTT] Error: Falló la suscripción")
            return False
        
        print("[MQTT] Setup completado. Esperando configuración de la VM...")
        
        # Esperar configuración
        start_time = utime.ticks_ms()
        max_wait = 300000  # 5 minutos
        
        # Contador para envíos periódicos de registro
        last_info_time = utime.ticks_ms()
        info_interval = 30000  # 30 segundos entre envíos
        
        while utime.ticks_diff(utime.ticks_ms(), start_time) < max_wait:
            managers["watchdog"].feed()
            managers["mqtt"].check_msg()
            
            if not managers["wifi"].check_connection():
                print("[MQTT] Se perdió conexión WiFi")
                return False
                
            # Verificar si recibimos configuración
            if managers["mqtt"].client_id and managers["mqtt"].panel_id:
                print("[MQTT] Configuración recibida exitosamente")
                return True
            
            # Reenviar información periódicamente mientras esperamos
            current_time = utime.ticks_ms()
            if utime.ticks_diff(current_time, last_info_time) >= info_interval:
                print("[MQTT] Re-enviando información de red...")
                managers["mqtt"].send_network_info()
                last_info_time = current_time
                
            utime.sleep_ms(100)
            
        print("[MQTT] Timeout esperando configuración")
        return False
            
    except Exception as e:
        print(f"[MQTT] Error en setup_mqtt_connection: {e}")
        import sys
        sys.print_exception(e)
        return False

# Variables globales para tracking de estados
_running_mode_error_count = 0
_running_mode_last_mqtt_check = 0

def handle_running_mode(managers):
    """Handles system in running mode with state verification and improved stability"""
    global _running_mode_error_count, _running_mode_last_mqtt_check
    
    try:
        # Parámetros de configuración
        mqtt_check_interval = 2000  # 2 segundos entre verificaciones MQTT
        max_errors = 3  # Permitir hasta 3 errores antes de cambiar estado
        
        current_time = utime.ticks_ms()
        
        # Verificar si es momento de revisar MQTT (reduce frecuencia)
        if utime.ticks_diff(current_time, _running_mode_last_mqtt_check) >= mqtt_check_interval:
            _running_mode_last_mqtt_check = current_time
            
            # Process MQTT messages y verificar salud de conexión (pero con tolerancia)
            if managers["mqtt"].client:
                try:
                    managers["mqtt"].check_msg()
                    
                    # Verificar conexión MQTT
                    if not managers["mqtt"].check_connection():
                        print("[RUNNING] MQTT connection unhealthy")
                        _running_mode_error_count += 1
                        print(f"[RUNNING] Error count: {_running_mode_error_count}/{max_errors}")
                    else:
                        # Resetear contador si hay éxito
                        if _running_mode_error_count > 0:
                            print(f"[RUNNING] Reseteo contador de errores: {_running_mode_error_count} -> 0")
                        _running_mode_error_count = 0
                except Exception as e:
                    print(f"[RUNNING] Error en check_msg: {e}")
                    _running_mode_error_count += 1
                    print(f"[RUNNING] Error count: {_running_mode_error_count}/{max_errors}")
                    
            # Check WiFi en cada ciclo (pero con tolerancia a fallos)
            if not managers["wifi"].check_connection():
                print("[RUNNING] WiFi connection issue detected")
                _running_mode_error_count += 1
                print(f"[RUNNING] Error count: {_running_mode_error_count}/{max_errors}")
            else:
                # No resetear contador por WiFi OK si MQTT tiene problemas
                pass
                    
        # Solo cambiar estado si alcanzamos el máximo de errores
        if _running_mode_error_count >= max_errors:
            print(f"[RUNNING] Error threshold reached ({max_errors}), changing state")
            _running_mode_error_count = 0  # Resetear para el próximo ciclo
            managers["mqtt"].was_previously_connected = False
            return False
            
        return True
        
    except Exception as e:
        print(f"[RUNNING] Error general: {e}")
        import sys
        sys.print_exception(e)
        _running_mode_error_count += 1
        return _running_mode_error_count < max_errors

def main():
    """Main program execution"""
    # Variables de control global
    current_state = SystemState.INITIAL
    managers = {}
    bluetooth_manager = None
    last_ble_attempt = 0
    BLE_RETRY_DELAY = 5000  # 5 segundos entre intentos de BLE
    
    try:
        # Initialize system
        print("[MAIN] Iniciando sistema...")
        managers = initialize_system()
        if not managers:
            raise Exception("System initialization failed")
        
        print("[MAIN] Sistema inicializado correctamente")
            
        # Verificar configuración WiFi existente
        wifi_config = managers["wifi"].get_wifi_config()
        has_wifi_config = wifi_config.get('ssid') and wifi_config.get('password')
        print(f"[MAIN] Configuración WiFi existente: {has_wifi_config}")
        
        if has_wifi_config:
            print(f"[MAIN] SSID configurado: {wifi_config.get('ssid')}")
            print(f"[MAIN] Intentando conectar a WiFi configurado...")
            if managers["wifi"].connect_wifi(wifi_config.get('ssid'), wifi_config.get('password')):
                print(f"[MAIN] Conectado a WiFi: {wifi_config.get('ssid')}")
                current_state = SystemState.CONFIG
                
        # Main loop
        print("[MAIN] Iniciando bucle principal...")
        while True:
            try:
                # Alimentar watchdog en cada ciclo
                if "watchdog" in managers:
                    managers["watchdog"].feed()
                    
                # Recolección de basura periódica
                gc.collect()
                
                # Obtener tiempo actual
                current_time = utime.ticks_ms()
                
                # Mostrar estado actual al inicio de cada ciclo
                print(f"[MAIN] Estado actual: {current_state}")
                
                # Proceso según estado
                if current_state == SystemState.INITIAL:
                    # Si no hay WiFi configurado o no se puede conectar, usar BLE
                    if not has_wifi_config or not managers["wifi"].check_connection():
                        print("[MAIN] Sin conexión WiFi, verificando BLE...")
                        
                        # Inicializar BLE si no existe y pasó tiempo suficiente
                        if bluetooth_manager is None and utime.ticks_diff(current_time, last_ble_attempt) >= BLE_RETRY_DELAY:
                            print("[MAIN] Iniciando BluetoothManager para configuración...")
                            try:
                                bluetooth_manager = BluetoothManager(managers["esp32_id"].get_id())
                                last_ble_attempt = current_time
                                print("[MAIN] BluetoothManager iniciado")
                            except Exception as e:
                                print(f"[MAIN] Error iniciando BluetoothManager: {e}")
                                bluetooth_manager = None
                                
                        # Procesar BluetoothManager si existe
                        if bluetooth_manager is not None:
                            # Verificar timeout
                            if bluetooth_manager.check_timeout():
                                print("[MAIN] Timeout BLE alcanzado, limpiando...")
                                bluetooth_manager.cleanup()
                                bluetooth_manager = None
                                last_ble_attempt = current_time
                            # Intentar configuración WiFi
                            elif setup_wifi_mode(managers, bluetooth_manager):
                                print("[MAIN] Configuración WiFi exitosa vía BLE")
                                has_wifi_config = True
                                current_state = SystemState.CONFIG
                                if bluetooth_manager:
                                    bluetooth_manager.update_state('RUNNING')
                        
                elif current_state == SystemState.CONFIG:
                    # Verificar conexión WiFi antes de MQTT
                    if managers["wifi"].check_connection():
                        print("[MAIN] WiFi conectado, procediendo a MQTT...")
                        
                        # Liberar BLE definitivamente antes de MQTT
                        if bluetooth_manager:
                            print("[MAIN] Liberando recursos BLE antes de MQTT...")
                            try:
                                bluetooth_manager.cleanup()
                            except Exception as e:
                                print(f"[MAIN] Error liberando BLE: {e}")
                            bluetooth_manager = None
                            gc.collect()
                            utime.sleep_ms(500)
                        
                        # Intentar conexión MQTT
                        print("[MAIN] Verificando conexión MQTT...")
                        if managers["mqtt"].check_connection() or setup_mqtt_connection(managers):
                            print("[MAIN] Conexión MQTT establecida, configurando relays...")
                            if setup_relay_monitoring(managers, managers["esp32_id"].get_id()):
                                print("[MAIN] Relays configurados, pasando a RUNNING")
                                current_state = SystemState.RUNNING
                            else:
                                print("[MAIN] Error configurando relays")
                                raise Exception("Relay setup failed")
                        else:
                            print("[MAIN] No se pudo establecer conexión MQTT")
                    else:
                        print("[MAIN] Sin conexión WiFi, volviendo a INITIAL")
                        current_state = SystemState.INITIAL
                        if bluetooth_manager:
                            bluetooth_manager.update_state('CONFIG')
                
                elif current_state == SystemState.RUNNING:
                    # Verificar MQTT y WiFi
                    if not handle_running_mode(managers):
                        print("[MAIN] Problema en modo RUNNING, volviendo a CONFIG")
                        current_state = SystemState.CONFIG
                
                # Memory check and cleanup
                if not check_memory():
                    print("[MAIN] Advertencia: Memoria baja")
                    gc.collect()
                    utime.sleep_ms(100)
                
                # Pausa entre ciclos
                utime.sleep_ms(CHECK_INTERVAL)
                
            except Exception as e:
                print(f"[MAIN] Error en bucle principal: {str(e)}")
                sys.print_exception(e)
                
                # Intentar mantener sistema funcionando
                if bluetooth_manager:
                    try:
                        bluetooth_manager.update_state('CONFIG')
                    except:
                        pass
                
                # Si hay watchdog, resetear
                if "watchdog" in managers:
                    try:
                        managers["watchdog"].force_reset("loop_error")
                    except:
                        machine.reset()
                else:
                    machine.reset()
                    
    except KeyboardInterrupt:
        print("\n[MAIN] Programa interrumpido por usuario")
    except Exception as e:
        print(f"\n[MAIN] Error fatal: {e}")
        sys.print_exception(e)
    finally:
        # Limpieza final
        print("[MAIN] Realizando limpieza final...")
        
        if bluetooth_manager:
            try:
                bluetooth_manager.cleanup()
            except:
                pass
                
        if "mqtt" in managers:
            try:
                managers["mqtt"].close()
            except:
                pass
                
        if "wifi" in managers:
            try:
                managers["wifi"].disconnect()
            except:
                pass
                
        gc.collect()
        machine.reset()

# Punto de entrada principal
if __name__ == "__main__":
    main()