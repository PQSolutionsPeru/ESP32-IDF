import machine

# Obtener fecha y hora actual
rtc = machine.RTC()
year, month, day, _, hour, minute, second, _ = rtc.datetime()

# Imprimir información
print("Fecha actual:", "{:02d}-{:02d}-{:02d}".format(year, month, day))
print("Hora actual:", "{:02d}:{:02d}:{:02d}".format(hour, minute, second))
