import os

# Establece el directorio donde se encuentran tus archivos .py
directorio = r'E:\PQSolutions\HDD-Monitor\HDD-Monitor-GitHub\ESP32_MQTT_PYTHON_VSCODE\Webserver_MQTT_3relaycheck\Webserver y AP'  # Cambia esto por la ruta de tu carpeta

# Nombre del archivo de salida donde se guardará todo el contenido
archivo_salida = 'contenido_combinado.txt'

# Crea una lista para almacenar todo el contenido
contenido_total = []

# Recorre todos los archivos en el directorio especificado
for archivo in os.listdir(directorio):
    if archivo.endswith('.py'):
        ruta_completa = os.path.join(directorio, archivo)
        # Añade el nombre del archivo al contenido
        contenido_total.append(f"--- Contenido de {archivo} ---\n")
        # Abre el archivo y lee su contenido
        with open(ruta_completa, 'r', encoding='utf-8') as f:
            contenido_total.append(f.read() + '\n\n')

# Escribe todo el contenido recopilado en el archivo de salida
with open(os.path.join(directorio, archivo_salida), 'w', encoding='utf-8') as f:
    f.write('\n'.join(contenido_total))

print(f"Todo el contenido de los archivos .py ha sido combinado en {archivo_salida}")
