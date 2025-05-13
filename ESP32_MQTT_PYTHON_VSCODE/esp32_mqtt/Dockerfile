# Imagen base de Python
FROM python:3.12

# Instalar las herramientas necesarias
RUN pip install platformio paho-mqtt adafruit-ampy

# Configurar el entorno de trabajo
WORKDIR /clean-hdd-monitor

# Copiar el código fuente al contenedor
COPY . /clean-hdd-monitor

# Mantener el contenedor en ejecución indefinidamente
CMD tail -f /dev/null