import network
import ntptime
import machine
import utime
import gc

class TimeManager:
    def __init__(self, wifi_manager):
        """Inicializa el gestor de tiempo"""
        self.wifi_manager = wifi_manager
        self.rtc = machine.RTC()
        
        # Zona horaria Perú
        self.TIMEZONE_OFFSET = -5 * 3600  # GMT-5 (Perú)
        
        # Control de sincronización
        self.last_sync = 0
        self.SYNC_INTERVAL = 3600000  # 1 hora
        self.SYNC_RETRY_DELAY = 60000  # 1 minuto entre reintentos
        self.MAX_SYNC_RETRIES = 3
        self.sync_retries = 0
        self.is_synced = False
        
        print("[TIME] Gestor de tiempo iniciado")

    def sync_time(self):
        """Sincroniza hora con la red WiFi y ajusta a GMT-5"""
        try:
            if not self.wifi_manager.check_connection():
                print("[TIME] Sin conexión WiFi para sincronizar")
                return False

            print("[TIME] Intentando sincronización NTP...")
            ntptime.settime()  # Obtiene UTC
            
            # Ajustar a GMT-5 (Perú)
            current_time = utime.time() + self.TIMEZONE_OFFSET
            self.rtc.datetime(self._epoch_to_datetime(current_time))
            
            self.last_sync = utime.ticks_ms()
            self.is_synced = True
            print(f"[TIME] Hora sincronizada y ajustada a GMT-5: {self.get_datetime_str()}")
            return True
                
        except Exception as e:
            print(f"[TIME] Error en sync_time: {e}")
            self.is_synced = False
            return False

    def check_sync(self):
        """Verifica si la hora está sincronizada y válida"""
        try:
            current_time = utime.ticks_ms()
            
            # Si ya está sincronizado, solo verificar intervalo
            if self.is_synced:
                if utime.ticks_diff(current_time, self.last_sync) >= self.SYNC_INTERVAL:
                    print("[TIME] Intervalo de sync alcanzado")
                    self.sync_retries = 0
                    return self.sync_time()
                return True
                
            # Si no está sincronizado, verificar intentos
            if self.sync_retries >= self.MAX_SYNC_RETRIES:
                print("[TIME] Máximo de reintentos alcanzado")
                return False
                
            # Verificar delay entre intentos
            if utime.ticks_diff(current_time, self.last_sync) < self.SYNC_RETRY_DELAY:
                return False
                
            # Intentar sincronizar
            self.sync_retries += 1
            return self.sync_time()
                
        except Exception as e:
            print(f"[TIME] Error verificando sync: {e}")
            return False

    def get_timestamp(self):
        """Obtiene timestamp actual en GMT-5"""
        try:
            if not self.is_synced:
                return None
            return utime.time() + self.TIMEZONE_OFFSET
        except:
            return None

    def get_datetime_str(self):
        """Obtiene fecha y hora en formato YYYY/MM/DD HH:mm (GMT-5)"""
        try:
            if not self.is_synced:
                return None
                
            dt = self.rtc.datetime()
            return "{:04d}/{:02d}/{:02d} {:02d}:{:02d}".format(
                dt[0], dt[1], dt[2],    # año, mes, día
                dt[4], dt[5]            # hora, minuto
            )
        except:
            return None

    def get_full_datetime_str(self):
        """Obtiene fecha y hora completa YYYY/MM/DD HH:mm:ss (GMT-5)"""
        try:
            if not self.is_synced:
                return None
                
            dt = self.rtc.datetime()
            return "{:04d}/{:02d}/{:02d} {:02d}:{:02d}:{:02d}".format(
                dt[0], dt[1], dt[2],    # año, mes, día
                dt[4], dt[5], dt[6]     # hora, minuto, segundo
            )
        except:
            return None

    def _epoch_to_datetime(self, epoch):
        """Convierte timestamp a tupla datetime (ya en GMT-5)"""
        try:
            year, month, day, hour, minute, second, weekday, yearday = utime.localtime(epoch)
            return (year, month, day, weekday, hour, minute, second, 0)
        except Exception as e:
            print(f"[TIME] Error convirtiendo epoch: {e}")
            return (2024, 1, 1, 0, 0, 0, 0, 0)  # fecha por defecto

    def get_timezone_offset(self):
        """Retorna el offset de zona horaria en segundos"""
        return self.TIMEZONE_OFFSET

    def is_peru_time_synced(self):
        """Verifica que la hora esté sincronizada"""
        return self.is_synced