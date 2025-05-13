from ble_uart_peripheral import BLEUART
import bluetooth
import json
import gc
import utime
import ubinascii
import machine

class BluetoothManager:
    def __init__(self, esp32_id=None):
        """Initializes BLE for WiFi configuration only"""
        print("[BLE] Starting BLE...")
        
        # Force garbage collection before BLE initialization
        gc.collect()
        utime.sleep_ms(1000)
        
        try:
            self.ble = bluetooth.BLE()
            self.ble.active(True)
            
            # Generate ID from MAC if not provided
            if not esp32_id:
                mac = ubinascii.hexlify(machine.unique_id()).decode().upper()
                esp32_id = mac[-4:] + "AC" + mac[:2]
                print(f"[BLE] ID generated from MAC: {esp32_id}")
                # Save ID for ESP32IdManager to use later
                try:
                    with open("esp32_id.json", 'w') as f:
                        json.dump({
                            'esp32_id': esp32_id,
                            'mac': mac,
                            'timestamp': utime.ticks_ms(),
                            'history': [{'id': esp32_id, 'event': 'generated_by_ble', 'timestamp': utime.ticks_ms()}]
                        }, f)
                except Exception as e:
                    print(f"[BLE] Error saving ID: {e}")
            
            # Device identification
            self.esp32_id = esp32_id
            self.device_name = f"ESP32-{esp32_id}"
            
            print(f"[BLE] Initializing UART with name: {self.device_name}")
            self.uart = BLEUART(self.ble, name=self.device_name)
            
            # Control and state
            self.is_configured = False
            self.current_state = 'waiting'
            self.last_activity = utime.ticks_ms()
            
            # Constants
            self.TIMEOUT = 300000           # 5 minutes timeout
            self.CONNECT_RETRY_DELAY = 30000   # 30 seconds between retries
            self.MAX_RETRIES = 5               # 5 maximum attempts
            self.MAX_MSG_SIZE = 1024           # 1KB maximum message

            # Status for the APP
            self.device_state = 'CONFIG'  # Estados posibles: CONFIG, RUNNING
            
            print(f"[BLE] BLE active - Name: {self.device_name}")
            
        except Exception as e:
            print(f"[BLE] Initialization error: {e}")
            self.cleanup()
            raise

    def _extract_json(self, data):
        """Extracts JSON content between {START} and {END} markers"""
        try:
            if "{START}" not in data or "{END}" not in data:
                return None
                
            # Extract content between markers
            start_idx = data.find("{START}") + 7
            end_idx = data.find("{END}")
            if start_idx >= end_idx:
                return None
                
            json_str = data[start_idx:end_idx].strip()
            return json.loads(json_str)
            
        except Exception as e:
            print(f"[BLE] JSON extraction error: {e}")
            return None

    def wait_for_data(self):
        """Waits for and processes incoming data"""
        try:
            # Detener el escaneo al recibir datos
            if self.uart.any():
                data = self.uart.read().decode().strip()
                if not data:
                    return None
                    
                print(f"[BLE] Data received: {data}")
                self.last_activity = utime.ticks_ms()  # Actualizar tiempo de actividad inmediatamente
                
                # Check maximum size
                if len(data) > self.MAX_MSG_SIZE:
                    self.write_data("error:message_too_large")
                    return None
                
                # Process JSON data
                try:
                    config = self._extract_json(data)
                    if config and 'ssid' in config and 'password' in config:
                        # Responder de inmediato para confirmar recepción
                        self.write_data("status:processing_config")
                        return config
                    else:
                        self.write_data("error:invalid_format")
                        return None
                except Exception as e:
                    print(f"[BLE] JSON parsing error: {e}")
                    self.write_data("error:invalid_format")
                    return None
                    
            return None
                
        except Exception as e:
            print(f"[BLE] Error in data processing: {e}")
            self.write_data("error:processing_failed")
            return None

    def write_data(self, message):
        """Safely sends data through BLE"""
        try:
            if not message.endswith('\n'):
                message += '\n'
                
            print(f"[BLE] Sending response: {message.strip()}")
            self.uart.write(message.encode())
                
        except Exception as e:
            print(f"[BLE] Error sending response: {e}")

    def update_state(self, new_state):
        """Actualiza y notifica el estado del dispositivo"""
        self.device_state = new_state
        self.write_data(f"state:{new_state}")

    def cleanup(self):
        """Realiza limpieza agresiva de los recursos BLE"""
        try:
            print("[BLE] Iniciando limpieza profunda de recursos BLE...")
            
            # 1. Cerrar UART si existe
            if hasattr(self, 'uart') and self.uart:
                try:
                    # Intentar enviar mensaje de despedida
                    try:
                        self.write_data("bye:closing_connection")
                        utime.sleep_ms(300)
                    except:
                        pass
                        
                    # Cerrar UART
                    self.uart.close()
                    utime.sleep_ms(300)
                except Exception as e:
                    print(f"[BLE] Error cerrando UART: {e}")
                finally:
                    self.uart = None
            
            # 2. Desactivar BLE
            if hasattr(self, 'ble') and self.ble:
                try:
                    self.ble.active(False)
                    utime.sleep_ms(300)
                except Exception as e:
                    print(f"[BLE] Error desactivando BLE: {e}")
                finally:
                    self.ble = None
            
            # 3. Liberar memoria y variables de estado
            import gc
            
            # Liberar todas las variables de estado
            self.device_name = None
            self.esp32_id = None
            self.current_state = None
            self.is_configured = None
            
            # 4. Liberación de módulos
            try:
                import sys
                for module_name in ['bluetooth', 'ble_advertising', 'ble_uart_peripheral']:
                    if module_name in sys.modules:
                        print(f"[BLE] Eliminando módulo {module_name} del sistema")
                        del sys.modules[module_name]
            except Exception as e:
                print(f"[BLE] Error eliminando módulos: {e}")
            
            # 5. Recolección agresiva de basura
            for _ in range(5):
                gc.collect()
                utime.sleep_ms(100)
            
            print(f"[BLE] Limpieza BLE completada. Memoria disponible: {gc.mem_free()} bytes")
            
            return True
                
        except Exception as e:
            print(f"[BLE] Error en cleanup: {e}")
            import sys
            sys.print_exception(e)
            return False

    def check_timeout(self):
        """Checks for BLE timeout"""
        try:
            if not hasattr(self, 'last_activity'):
                self.last_activity = utime.ticks_ms()
                return False
                
            current_time = utime.ticks_ms()
            if utime.ticks_diff(current_time, self.last_activity) > self.TIMEOUT:
                print("[BLE] Activity timeout - Closing BLE")
                return True
            return False
        except Exception as e:
            print(f"[BLE] Error checking timeout: {e}")
            return True  # Return True to force cleanup