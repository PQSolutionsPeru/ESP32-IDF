# Imagen base de Python
FROM python:3.12

# Instalar las herramientas necesarias
RUN pip install platformio paho-mqtt adafruit-ampy

# Configurar el entorno de trabajo
WORKDIR /clean-hdd-monitor

# Copiar el código fuente al contenedor
COPY . /clean-hdd-monitor

# Copiar el archivo de punto de entrada
COPY entrypoint.py /clean-hdd-monitor/entrypoint.py

# Establecer el punto de entrada o comando por defecto
CMD ["python", "/clean-hdd-monitor/entrypoint.py"]