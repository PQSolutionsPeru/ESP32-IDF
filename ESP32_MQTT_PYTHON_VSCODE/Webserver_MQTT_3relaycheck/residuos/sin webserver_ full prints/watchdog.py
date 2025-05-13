import utime
from machine import WDT, reset # type: ignore
from log_manager import LogManager

log_manager = LogManager("logs.txt", max_size_kb=50)
wdt = WDT(timeout=30000)  # 30 segundos

def feed_watchdog():
    wdt.feed()
    log_manager.write_log("Watchdog alimentado a las " + str(utime.time()))

def restart_device():
    log_manager.write_log("Solicitando reinicio del dispositivo a las " + str(utime.time()))
    reset()

def handle_exception(e):
    log_manager.write_log("Ocurrio una excepción: " + str(e))
