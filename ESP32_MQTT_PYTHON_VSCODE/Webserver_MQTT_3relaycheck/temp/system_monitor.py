import gc
import os
from machine import freq # type: ignore

class SystemMonitor:
    def __init__(self):
        pass

    def read_memory(self):
        # Leer la cantidad de memoria RAM libre y total
        free_memory = gc.mem_free()
        total_memory = gc.mem_alloc() + free_memory
        return {
            'free_memory': free_memory,
            'total_memory': total_memory,
            'memory_usage_percent': 100 * gc.mem_alloc() / total_memory
        }

    def read_storage(self):
        # Revisar el espacio disponible en el sistema de archivos
        statvfs = os.statvfs('/')
        free_space = statvfs[0] * statvfs[3]  # f_bsize * f_bfree
        total_space = statvfs[0] * statvfs[2]  # f_bsize * f_blocks
        return {
            'free_space': free_space,
            'total_space': total_space,
            'storage_usage_percent': 100 * (total_space - free_space) / total_space
        }

    def monitor_system(self):
        # Ejecutar todas las lecturas y mostrar los resultados
        print("Memory Info:", self.read_memory())
        #print("Storage Info:", self.read_storage())

# Crear instancia y monitorear el sistema
""" 
    if __name__ == '__main__':
    monitor = SystemMonitor()
    monitor.monitor_system()
"""