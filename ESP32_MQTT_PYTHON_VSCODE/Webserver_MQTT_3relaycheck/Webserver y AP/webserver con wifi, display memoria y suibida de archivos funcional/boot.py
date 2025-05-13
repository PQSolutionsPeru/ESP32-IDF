# boot.py
import machine
import esp
esp.osdebug(None)
import uos

import network

# Importamos y ejecutamos el servidor web
import webserver
