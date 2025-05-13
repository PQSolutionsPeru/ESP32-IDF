import os

# Lista de directorios donde se encuentran tus archivos
directorios = [
    r'E:\PQSolutions\HDD-Monitor\HDD-Monitor-GitHub\APP\HDDMonitor',
    r'E:\PQSolutions\HDD-Monitor\HDD-Monitor-GitHub\APP\HDDMonitor\app',
    r'E:\PQSolutions\HDD-Monitor\HDD-Monitor-GitHub\APP\HDDMonitor\app\src\main',
    r'E:\PQSolutions\HDD-Monitor\HDD-Monitor-GitHub\APP\HDDMonitor\app\src\main\java\com\pqsolutions\hdd_monitor',
    r'E:\PQSolutions\HDD-Monitor\HDD-Monitor-GitHub\APP\HDDMonitor\app\src\main\res\drawable',
    r'E:\PQSolutions\HDD-Monitor\HDD-Monitor-GitHub\APP\HDDMonitor\app\src\main\res\layout',
    r'E:\PQSolutions\HDD-Monitor\HDD-Monitor-GitHub\APP\HDDMonitor\app\src\main\res\values'
]

# Lista de extensiones de los archivos a buscar
extensiones_archivos = ['.kt', '.xml', '.gradle', '.json']  # Asegúrate de usar minúsculas para JSON

# Nombre del archivo de salida donde se guardará todo el contenido
archivo_salida = 'contenido_combinado.txt'

# Crea una lista para almacenar todo el contenido
contenido_total = []

# Recorre cada directorio
for directorio in directorios:
    # Lista solo los archivos en el directorio especificado
    for archivo in os.listdir(directorio):
        # Recorre cada extensión de archivo
        for extension_archivo in extensiones_archivos:
            if archivo.endswith(extension_archivo):
                ruta_completa = os.path.join(directorio, archivo)
                if os.path.isfile(ruta_completa):  # Verifica que sea un archivo
                    # Añade el nombre del archivo al contenido
                    contenido_total.append(f"--- Contenido de {archivo} ---\n")
                    try:
                        # Abre el archivo y lee su contenido
                        with open(ruta_completa, 'r', encoding='utf-8') as f:
                            contenido_total.append(f.read() + '\n\n')
                    except PermissionError:
                        print(f"No se pudo leer el archivo {ruta_completa} debido a permisos insuficientes.")
                    except Exception as e:
                        print(f"Ocurrió un error al leer el archivo {ruta_completa}: {e}")

# Escribe todo el contenido recopilado en el archivo de salida
with open(os.path.join(directorios[0], archivo_salida), 'w', encoding='utf-8') as f:
    f.write('\n'.join(contenido_total))

print(f"Todo el contenido de los archivos con extensiones {extensiones_archivos} ha sido combinado en {archivo_salida}")
