import network
import utime
import machine
import gc
import json
import os
from config.wifi_config import WiFiConfig

class WiFiManager:
    _instance = None
    _initialized = False
    
    def __new__(cls):
        if cls._instance is None:
            print("[WIFI] Creating WiFiManager singleton instance")
            cls._instance = super(WiFiManager, cls).__new__(cls)
        return cls._instance

    def __init__(self):
        if WiFiManager._initialized:
            return
            
        try:
            print("[WIFI] First-time WiFiManager initialization")
            WiFiManager._initialized = True
            
            # Clean up any existing WiFi state
            self._clean_wifi_state()
            
            # Load configuration
            self.config = WiFiConfig()
            
            # Obtener parámetros de configuración
            self.ssid = None
            self.password = None
            
            # Connection parameters from config
            self.CONNECT_TIMEOUT = self.config.DEFAULT_CONFIG['connect_timeout']
            self.MAX_RETRIES = self.config.DEFAULT_CONFIG['max_retries']
            self.RETRY_DELAY = self.config.DEFAULT_CONFIG['retry_delay']
            self.CHECK_INTERVAL = self.config.DEFAULT_CONFIG['check_interval']
            
            self.last_check = utime.ticks_ms()
            self.last_error = None
            self.current_ip = None
            self.sta_if = None
            
            # Initialize interface safely
            self._safe_init_interface()
            
            # Cargar configuración guardada
            saved_config = self.config.get_credentials()
            self.ssid = saved_config['ssid']
            self.password = saved_config['password']
            
        except Exception as e:
            print(f"[WIFI] Initialization error: {e}")
            WiFiManager._initialized = False
            raise

    def get_wifi_config(self):
        """Gets saved WiFi configuration"""
        return self.config.get_credentials()

    def save_wifi_config(self, ssid, password):
        """Saves WiFi configuration"""
        success = self.config.save_credentials(ssid, password)
        if success:
            self.ssid = ssid
            self.password = password
        return success

    def _clean_wifi_state(self):
        """Cleans existing WiFi state"""
        try:
            wlan = network.WLAN(network.STA_IF)
            if wlan:
                wlan.active(False)
                utime.sleep_ms(1000)
                del wlan
            
            gc.collect()
            utime.sleep_ms(1000)
            
        except Exception as e:
            print(f"[WIFI] Error cleaning state: {e}")

    def _safe_init_interface(self):
        """Safely initializes WiFi interface with retries"""
        for attempt in range(3):
            try:
                print(f"[WIFI] Interface initialization attempt {attempt + 1}/3")
                gc.collect()
                utime.sleep_ms(1000)

                try:
                    wlan = network.WLAN(network.STA_IF)
                    if wlan:
                        wlan.active(False)
                        utime.sleep_ms(1000)
                        del wlan
                    gc.collect()
                    utime.sleep_ms(1000)
                except:
                    pass

                self.sta_if = network.WLAN(network.STA_IF)
                if not self.sta_if:
                    print("[WIFI] Error: Could not create interface")
                    utime.sleep_ms(1000)
                    continue

                self.sta_if.active(False)
                utime.sleep_ms(1000)
                self.sta_if.active(True)
                utime.sleep_ms(1000)

                if self.sta_if.active():
                    print("[WIFI] Interface initialized successfully")
                    return True
                else:
                    print("[WIFI] Interface failed to activate")
                    continue

            except Exception as e:
                print(f"[WIFI] Error in attempt {attempt + 1}: {e}")
                gc.collect()
                utime.sleep_ms(2000)
                continue

        raise Exception("Could not initialize WiFi interface after 3 attempts")

    def connect_wifi(self, ssid, password):
        """Connects to WiFi with improved error handling"""
        try:
            if not ssid or not password:
                print("[WIFI] Error: Missing credentials")
                self.last_error = "invalid_credentials"
                return False

            print(f"[WIFI] Attempting connection to: {ssid}")
            gc.collect()

            # Handle interface activation
            if not self.sta_if or not hasattr(self.sta_if, 'active'):
                self._safe_init_interface()
                
            # Ensure interface is active
            if not self.sta_if.active():
                self.sta_if.active(True)
                utime.sleep_ms(1000)

            # Disconnect if already connected
            if self.sta_if.isconnected():
                self.sta_if.disconnect()
                utime.sleep_ms(1000)  # Increased delay after disconnect

            retry_count = 0
            while retry_count < self.MAX_RETRIES:
                print(f"[WIFI] Attempt {retry_count + 1}/{self.MAX_RETRIES}")
                
                try:
                    # Limpiar estado antes de cada intento
                    gc.collect()
                    if retry_count > 0:
                        self.sta_if.disconnect()
                        utime.sleep_ms(2000)  # Delay más largo entre intentos
                        self.sta_if.active(False)
                        utime.sleep_ms(1000)
                        self.sta_if.active(True)
                        utime.sleep_ms(1000)
                    
                    self.sta_if.connect(ssid, password)
                except Exception as e:
                    print(f"[WIFI] Connection attempt error: {type(e).__name__} - {str(e)}")
                    retry_count += 1
                    if retry_count < self.MAX_RETRIES:
                        gc.collect()
                        utime.sleep_ms(self.RETRY_DELAY * (retry_count + 1))
                        continue
                    return False
                
                start_time = utime.ticks_ms()
                while utime.ticks_diff(utime.ticks_ms(), start_time) < self.CONNECT_TIMEOUT:
                    if self.sta_if.isconnected():
                        utime.sleep_ms(1000)  # Wait longer for connection to stabilize
                        self.current_ip = self.sta_if.ifconfig()[0]
                        print(f"[WIFI] Successfully connected - IP: {self.current_ip}")
                        
                        # Save configuration
                        if self.save_wifi_config(ssid, password):
                            print("[WIFI] Configuration saved successfully")
                        else:
                            print("[WIFI] Warning: Could not save configuration")
                            
                        self.ssid = ssid
                        self.password = password
                        self.last_error = None
                        return True
                    
                    status = self.sta_if.status()
                    if status == network.STAT_CONNECTING:
                        utime.sleep_ms(100)
                        continue
                    elif status == network.STAT_WRONG_PASSWORD:
                        print("[WIFI] Error: Wrong password")
                        self.last_error = "wrong_password"
                        utime.sleep_ms(1000)
                        break
                    elif status == network.STAT_NO_AP_FOUND:
                        print("[WIFI] Error: Network not found")
                        self.last_error = "network_not_found"
                        break
                    
                    utime.sleep_ms(100)
                
                retry_count += 1
                if retry_count < self.MAX_RETRIES:
                    print(f"[WIFI] Retrying connection... ({retry_count + 1})")
                    gc.collect()
                    utime.sleep_ms(self.RETRY_DELAY * (retry_count + 1))

            self.last_error = "connection_failed"
            print("[WIFI] Could not establish connection after all attempts")
            return False

        except Exception as e:
            print(f"[WIFI] Connection error: {e}")
            self.last_error = str(e)
            return False

    def check_connection(self):
        """Verifies and maintains WiFi connection"""
        try:
            if not self.sta_if:
                return False
                
            current_time = utime.ticks_ms()
            if utime.ticks_diff(current_time, self.last_check) >= self.CHECK_INTERVAL:
                self.last_check = current_time
                
                if not self.sta_if.isconnected():
                    print("[WIFI] Connection lost, attempting reconnection...")
                    if self.ssid and self.password:
                        return self.connect_wifi(self.ssid, self.password)
                    return False
                    
                self.current_ip = self.sta_if.ifconfig()[0]
                return True
                
            return self.sta_if.isconnected()
                
        except Exception as e:
            print(f"[WIFI] Error checking connection: {e}")
            return False

    def get_current_ip(self):
        """Gets current IP address"""
        try:
            if self.sta_if and self.sta_if.isconnected():
                self.current_ip = self.sta_if.ifconfig()[0]
                return self.current_ip
            return None
        except Exception as e:
            print(f"[WIFI] Error getting IP: {e}")
            return None

    def get_error(self):
        """Gets last error message"""
        return self.last_error

    def is_connected(self):
        """Checks if currently connected"""
        try:
            return self.sta_if and self.sta_if.isconnected()
        except:
            return False

    def get_signal_strength(self):
        """Gets current signal strength if connected"""
        try:
            if self.sta_if and self.sta_if.isconnected():
                return self.sta_if.status('rssi')
            return None
        except:
            return None

    def get_saved_networks(self):
        """Gets list of saved networks"""
        credentials = self.config.get_credentials()
        return [credentials['ssid']] if credentials['ssid'] else []

    def forget_network(self):
        """Forgets current network configuration"""
        try:
            # Usar el método de la clase config para limpiar
            self.config.save_credentials(None, None)
            self.ssid = None
            self.password = None
            self.current_ip = None
            if self.sta_if and self.sta_if.isconnected():
                self.sta_if.disconnect()
            return True
        except Exception as e:
            print(f"[WIFI] Error forgetting network: {e}")
            return False

    def reset_config(self):
        """Resetea la configuración completamente"""
        try:
            self.forget_network()
            self._clean_wifi_state()
            self.sta_if = None
            self._safe_init_interface()
            return True
        except Exception as e:
            print(f"[WIFI] Error resetting config: {e}")
            return False

    def disconnect(self):
        """Disconnects from WiFi and cleans up"""
        try:
            if self.sta_if and self.sta_if.isconnected():
                self.sta_if.disconnect()
                self.sta_if.active(False)
                utime.sleep_ms(500)
            return True
        except Exception as e:
            print(f"[WIFI] Error disconnecting: {e}")
            return False