import gc
import utime

def check_performance(func, *args, **kwargs):
    gc.collect()  # Collect garbage and measure free memory before execution
    free_memory_before = gc.mem_free()
    print("Memoria libre antes de la ejecución:", free_memory_before)

    start_time = utime.ticks_ms()  # Start time in milliseconds
    func(*args, **kwargs)  # Execute the function with provided arguments
    end_time = utime.ticks_ms()  # End time in milliseconds
    execution_time = utime.ticks_diff(end_time, start_time)  # Calculate the time difference

    gc.collect()  # Collect garbage and measure free memory after execution
    free_memory_after = gc.mem_free()
    print(f"Tiempo de ejecución de {func.__name__}: {execution_time} ms")
    print("Memoria libre después de la ejecución:", free_memory_after)

    memory_change = free_memory_before - free_memory_after
    print(f"Cambio en la memoria usada: {memory_change} bytes")
