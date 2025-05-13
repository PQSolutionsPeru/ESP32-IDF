import gc
import utime

def check_performance(func, *args, **kwargs):
    """
    Función para medir y reportar el tiempo de ejecución de cualquier función,
    así como el uso de memoria antes y después de la ejecución.
    """
    # Recolectar basura y medir memoria libre antes de la ejecución
    gc.collect()
    free_memory_before = gc.mem_free()
    print("Memoria libre antes de la ejecución:", free_memory_before)

    # Medir el tiempo de ejecución
    start_time = utime.ticks_ms()  # Tiempo de inicio en milisegundos
    func(*args, **kwargs)  # Ejecutar la función con argumentos proporcionados
    end_time = utime.ticks_ms()  # Tiempo de fin en milisegundos
    execution_time = utime.ticks_diff(end_time, start_time)  # Calcula la diferencia de tiempo

    # Medir memoria libre después de la ejecución
    gc.collect()
    free_memory_after = gc.mem_free()
    print(f"Tiempo de ejecución de {func.__name__}: {execution_time} ms")
    print("Memoria libre después de la ejecución:", free_memory_after)

    # Calcular el cambio en la memoria
    memory_change = free_memory_before - free_memory_after
    print(f"Cambio en la memoria usada: {memory_change} bytes")

def sleep_device(seconds):
    # Configura el temporizador para despertar luego del tiempo en segundos
    deepsleep(seconds * 1000)