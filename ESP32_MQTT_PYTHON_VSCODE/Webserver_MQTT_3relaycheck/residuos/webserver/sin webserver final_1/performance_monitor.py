import gc
import utime

def check_performance(func, *args, **kwargs):
    gc.collect()  # Recolecta basura y mide memoria libre antes de la ejecución
    free_memory_before = gc.mem_free()
    print("Memoria libre antes de la ejecución:", free_memory_before)

    start_time = utime.ticks_ms()  # Tiempo de inicio en milisegundos
    func(*args, **kwargs)  # Ejecutar la función con argumentos proporcionados
    end_time = utime.ticks_ms()  # Tiempo de fin en milisegundos
    execution_time = utime.ticks_diff(end_time, start_time)  # Calcula la diferencia de tiempo

    gc.collect()  # Recolecta basura y mide memoria libre después de la ejecución
    free_memory_after = gc.mem_free()
    print(f"Tiempo de ejecución de {func.__name__}: {execution_time} ms")
    print("Memoria libre después de la ejecución:", free_memory_after)

    memory_change = free_memory_before - free_memory_after
    print(f"Cambio en la memoria usada: {memory_change} bytes")
