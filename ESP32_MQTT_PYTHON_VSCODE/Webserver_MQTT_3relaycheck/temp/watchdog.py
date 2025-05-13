from machine import WDT, reset # type: ignore

wdt = WDT(timeout=30000)  # 30 segundos

def feed_watchdog():
    wdt.feed()

def restart_device():
    reset()
