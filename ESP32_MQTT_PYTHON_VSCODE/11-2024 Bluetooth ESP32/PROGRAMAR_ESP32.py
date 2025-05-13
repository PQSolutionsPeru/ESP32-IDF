import subprocess
import time
import sys
import os
import threading
import queue
import serial.tools.list_ports
import tkinter as tk
from tkinter import ttk, scrolledtext, messagebox
from typing import List, Dict, Union, Tuple
import logging
import io

class LogRedirector(io.StringIO):
    def __init__(self, text_widget, tag=None):
        super().__init__()
        self.text_widget = text_widget
        self.tag = tag

    def write(self, string):
        self.text_widget.configure(state="normal")
        self.text_widget.insert("end", string, self.tag)
        self.text_widget.see("end")
        self.text_widget.configure(state="disabled")
        
    def flush(self):
        pass

class ESP32Uploader:
    def __init__(self, port: str, log_queue=None):
        self.port = port
        self.log_queue = log_queue
        self.file_groups = {
            "configs_basic": [
                ("mkdir", "config"),
                ("put", "config/__init__.py", "config/__init__.py"),
                ("put", "config/base_config.py", "config/base_config.py"),
                ("put", "config/wifi_config.py", "config/wifi_config.py"),
                ("put", "config/mqtt_config.py", "config/mqtt_config.py"),
            ],
            "configs_additional_1": [
                ("put", "config/device_pool_config.py", "config/device_pool_config.py"),
                ("put", "config/watchdog_config.py", "config/watchdog_config.py"),
                ("put", "config/relay_config.py", "config/relay_config.py"),
            ],
            "configs_additional_2": [
                ("put", "config/system_config.py", "config/system_config.py"),
            ],
            "wifi_esp32": [
                ("put", "wifi_manager.py"),
                ("put", "esp32_id_manager.py"),
            ],
            "mqtt_files": [
                ("put", "mqtt_ssl_setup.py"),
                ("put", "robust.py"),
                ("put", "simple.py"),
            ],
            "mqtt_main": [
                ("put", "mqtt_manager.py"),
            ],
            "managers_1": [
                ("put", "relay_manager.py"),
                ("put", "time_manager.py"),
                ("put", "led_manager.py"),
            ],
            "managers_2": [
                ("put", "watchdog_manager.py"),
                ("put", "bluetooth_manager.py"),
            ],
            "ble_files": [
                ("put", "ble_advertising.py"),
                ("put", "ble_uart_peripheral.py"),
            ],
            "certs": [
                ("put", "combined_ca.crt"),
            ],
            "final_files": [
                ("put", "main.py"),
            ]
        }
        self.max_retries = 3
        self.delay_between_groups = 3
        self.delay_between_retries = 10
        self.command_timeout = 60
        self.log_prefix = f"[{self.port}] "
        self.progress = 0
        self.stop_requested = False

    def log_message(self, level, message):
        """Envia un mensaje al queue de logs"""
        if self.log_queue:
            formatted_msg = f"{time.strftime('%H:%M:%S')} - {level} - {self.log_prefix}{message}"
            self.log_queue.put((level, formatted_msg))
        
    def log_info(self, message):
        """Agrega prefijo del puerto al mensaje de log"""
        self.log_message("INFO", message)
        
    def log_error(self, message):
        """Agrega prefijo del puerto al mensaje de error"""
        self.log_message("ERROR", message)
        
    def log_warning(self, message):
        """Agrega prefijo del puerto al mensaje de advertencia"""
        self.log_message("WARNING", message)

    def reset_and_wait(self):
        """Resetea el ESP32 y espera a que esté listo"""
        if self.stop_requested:
            return False
            
        self.log_info("Reseteando ESP32...")
        try:
            subprocess.run(
                ["ampy", "-p", self.port, "reset"],
                capture_output=True,
                text=True,
                timeout=5
            )
            self.log_info("ESP32 reseteado")
        except:
            self.log_warning("No se pudo resetear el ESP32, probablemente ya está reiniciando")
            
        self.log_info("Esperando 5 segundos después del reset...")
        time.sleep(5)
        return True

    def execute_ampy_command(self, command: List[str], retry_count: int = 0) -> bool:
        """Ejecuta un comando ampy con reintentos"""
        if self.stop_requested:
            return False
            
        try:
            cmd = ["ampy", "-p", self.port] + command
            self.log_info(f"Ejecutando: {' '.join(cmd)}")
            
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=self.command_timeout
            )
            
            if result.returncode == 0:
                self.log_info("Comando ejecutado exitosamente")
                return True
            else:
                self.log_error(f"Error: {result.stderr}")
                if retry_count < self.max_retries and not self.stop_requested:
                    self.log_info(f"Reintentando en {self.delay_between_retries} segundos...")
                    time.sleep(self.delay_between_retries)
                    return self.execute_ampy_command(command, retry_count + 1)
                return False
                
        except subprocess.TimeoutExpired:
            self.log_error("Timeout ejecutando comando")
            if self.stop_requested:
                return False
                
            # Resetear el ESP32 si hay timeout
            self.reset_and_wait()
            if retry_count < self.max_retries and not self.stop_requested:
                self.log_info(f"Reintentando en {self.delay_between_retries} segundos...")
                time.sleep(self.delay_between_retries)
                return self.execute_ampy_command(command, retry_count + 1)
            return False
            
        except Exception as e:
            self.log_error(f"Error inesperado: {e}")
            return False

    def process_group(self, group_name: str, commands: List[tuple]) -> bool:
        """Procesa un grupo de comandos"""
        if self.stop_requested:
            return False
            
        self.log_info(f"\nProcesando grupo: {group_name}")
        success = True
        
        for cmd_type, *args in commands:
            if self.stop_requested:
                return False
                
            if cmd_type == "mkdir":
                cmd = ["mkdir"] + list(args)
            elif cmd_type == "put":
                cmd = ["put"] + list(args)
            else:
                self.log_error(f"Comando desconocido: {cmd_type}")
                continue
                
            if not self.execute_ampy_command(cmd):
                success = False
                self.log_error(f"Error en comando {cmd}")
                break
                
            # Pequeña pausa entre archivos del mismo grupo
            if not self.stop_requested:
                time.sleep(1)
                
        return success

    def upload_all(self, progress_callback=None):
        """Sube todos los archivos en grupos"""
        if self.stop_requested:
            return False
            
        total_groups = len(self.file_groups)
        current_group = 0
        self.progress = 0
        
        # Reset inicial
        if not self.reset_and_wait():
            return False
        
        for group_name, commands in self.file_groups.items():
            if self.stop_requested:
                return False
                
            current_group += 1
            self.log_info(f"\nGrupo {current_group}/{total_groups}: {group_name}")
            
            if not self.process_group(group_name, commands):
                self.log_error(f"Error en grupo {group_name}. Deteniendo proceso.")
                return False
            
            # Actualizar progreso    
            self.progress = (current_group / total_groups) * 100
            if progress_callback:
                progress_callback(self.port, self.progress)
                
            if self.stop_requested:
                return False
                
            self.log_info(f"Grupo {group_name} completado. Esperando {self.delay_between_groups} segundos...")
            time.sleep(self.delay_between_groups)
            
            # Reset después de grupos grandes
            if group_name in ["mqtt_main", "managers_1", "managers_2"] and not self.stop_requested:
                self.reset_and_wait()
            
        self.log_info("\nTodos los archivos han sido subidos exitosamente!")
        self.progress = 100
        if progress_callback:
            progress_callback(self.port, self.progress)
        return True
        
    def stop(self):
        """Solicita detener el proceso de carga"""
        self.stop_requested = True
        self.log_warning("Solicitud de detención recibida. Finalizando...")


class MultiPortUploader:
    """Clase para gestionar la subida a múltiples puertos"""
    
    def __init__(self, ports: List[str], parallel: bool = False, log_queue=None, 
                 progress_callback=None, status_callback=None):
        self.ports = ports
        self.parallel = parallel
        self.log_queue = log_queue
        self.progress_callback = progress_callback
        self.status_callback = status_callback
        self.uploaders = {port: ESP32Uploader(port, log_queue) for port in ports}
        self.results = {port: None for port in ports}  # None = no iniciado, True = éxito, False = fallo
        self.threads = {}
        self.stop_requested = False
        
    def update_status(self, port, status):
        """Actualiza el estado de un puerto"""
        if self.status_callback:
            self.status_callback(port, status)
    
    def log_message(self, level, message):
        """Envia un mensaje al queue de logs"""
        if self.log_queue:
            formatted_msg = f"{time.strftime('%H:%M:%S')} - {level} - {message}"
            self.log_queue.put((level, formatted_msg))
        
    def upload_worker(self, port):
        """Función de trabajo para cada hilo"""
        self.update_status(port, "En progreso")
        uploader = self.uploaders[port]
        try:
            success = uploader.upload_all(self.progress_callback)
            self.results[port] = success
            
            if success:
                self.update_status(port, "Completado")
            else:
                if self.stop_requested or uploader.stop_requested:
                    self.update_status(port, "Detenido")
                else:
                    self.update_status(port, "Fallido")
                    
        except Exception as e:
            self.log_message("ERROR", f"Error en puerto {port}: {e}")
            self.results[port] = False
            self.update_status(port, "Error")
    
    def upload_sequential(self):
        """Sube archivos a cada puerto de forma secuencial"""
        self.log_message("INFO", f"Iniciando carga secuencial en {len(self.ports)} puertos: {', '.join(self.ports)}")
        
        for port in self.ports:
            if self.stop_requested:
                break
                
            self.log_message("INFO", f"\n{'='*20} INICIANDO CARGA EN {port} {'='*20}")
            self.upload_worker(port)
            
            if self.results[port]:
                self.log_message("INFO", f"Carga en {port} completada exitosamente")
            else:
                self.log_message("ERROR", f"Carga en {port} falló")
                
        return all(result for result in self.results.values() if result is not None)
    
    def upload_parallel(self):
        """Sube archivos a todos los puertos en paralelo usando hilos"""
        self.log_message("INFO", f"Iniciando carga en paralelo en {len(self.ports)} puertos: {', '.join(self.ports)}")
        
        # Crear y lanzar un hilo para cada puerto
        self.threads = {}
        for port in self.ports:
            thread = threading.Thread(target=self.upload_worker, args=(port,))
            self.threads[port] = thread
            thread.start()
            
        # Esperar a que todos los hilos terminen
        for port, thread in self.threads.items():
            thread.join()
            
        return all(result for result in self.results.values() if result is not None)
    
    def upload_all(self):
        """Inicia el proceso de carga según el modo seleccionado"""
        self.stop_requested = False
        if self.parallel:
            return self.upload_parallel()
        else:
            return self.upload_sequential()
    
    def stop_all(self):
        """Detiene todos los procesos de carga"""
        self.stop_requested = True
        for port, uploader in self.uploaders.items():
            uploader.stop()
        
        self.log_message("WARNING", "Deteniendo todos los procesos de carga...")
        
        # En modo paralelo, espera a que los hilos terminen
        if self.parallel and self.threads:
            for port, thread in self.threads.items():
                if thread.is_alive():
                    thread.join(timeout=2)
        
        self.log_message("WARNING", "Todos los procesos de carga han sido detenidos")
    
    def print_results(self):
        """Imprime un resumen de los resultados"""
        self.log_message("INFO", "\n" + "="*50)
        self.log_message("INFO", "RESUMEN DE RESULTADOS:")
        
        all_success = True
        for port, success in self.results.items():
            if success is None:
                status = "⚪ NO INICIADO"
            elif success:
                status = "✅ ÉXITO"
            else:
                status = "❌ FALLO"
                if not self.stop_requested:  # Solo consideramos un fallo verdadero si no fue por detención manual
                    all_success = False
                
            self.log_message("INFO", f"{port}: {status}")
                
        self.log_message("INFO", "="*50)
        return all_success


class UploaderGUI:
    def __init__(self, root):
        self.root = root
        self.root.title("ESP32 Uploader GUI")
        self.root.geometry("800x600")
        self.root.minsize(700, 500)
        
        # Variables
        self.port_vars = {}
        self.progress_bars = {}
        self.status_labels = {}
        self.uploader = None
        self.log_queue = queue.Queue()
        self.available_ports = []
        self.is_uploading = False
        self.upload_thread = None
        
        # Estilos
        self.style = ttk.Style()
        self.style.configure("TButton", font=("Arial", 10))
        self.style.configure("TCheckbutton", font=("Arial", 10))
        self.style.configure("TLabel", font=("Arial", 10))
        
        # Crear interfaz
        self.create_widgets()
        
        # Iniciar procesador de log
        self.process_logs()
        
        # Actualizar puertos disponibles cada 2 segundos
        self.update_ports()
        
    def create_widgets(self):
        # Frame principal
        main_frame = ttk.Frame(self.root, padding=10)
        main_frame.pack(fill=tk.BOTH, expand=True)
        
        # Título
        title_label = ttk.Label(main_frame, text="ESP32 Uploader", font=("Arial", 16, "bold"))
        title_label.pack(pady=(0, 10))
        
        # Frame superior para puertos y opciones
        top_frame = ttk.Frame(main_frame)
        top_frame.pack(fill=tk.X, pady=5)
        
        # Botón de actualizar puertos
        refresh_btn = ttk.Button(top_frame, text="Actualizar Puertos", command=self.refresh_ports)
        refresh_btn.pack(side=tk.LEFT, padx=5)
        
        # Checkbox para modo paralelo
        self.parallel_var = tk.BooleanVar(value=False)
        parallel_check = ttk.Checkbutton(top_frame, text="Carga en Paralelo", variable=self.parallel_var)
        parallel_check.pack(side=tk.LEFT, padx=20)
        
        # Botones de Iniciar/Detener
        self.btn_frame = ttk.Frame(top_frame)
        self.btn_frame.pack(side=tk.RIGHT, padx=5)
        
        self.start_btn = ttk.Button(self.btn_frame, text="Iniciar Carga", command=self.start_upload)
        self.start_btn.pack(side=tk.LEFT, padx=5)
        
        self.stop_btn = ttk.Button(self.btn_frame, text="Detener", command=self.stop_upload, state=tk.DISABLED)
        self.stop_btn.pack(side=tk.LEFT, padx=5)
        
        # Separator
        ttk.Separator(main_frame, orient=tk.HORIZONTAL).pack(fill=tk.X, pady=10)
        
        # Frame para la lista de puertos
        port_list_frame = ttk.LabelFrame(main_frame, text="Puertos Disponibles", padding=10)
        port_list_frame.pack(fill=tk.X, pady=5)
        
        # Frame interno para los checkboxes
        self.port_frame = ttk.Frame(port_list_frame)
        self.port_frame.pack(fill=tk.X, pady=5)
        
        # Botones para seleccionar/deseleccionar todos
        select_frame = ttk.Frame(port_list_frame)
        select_frame.pack(fill=tk.X, pady=5)
        
        select_all_btn = ttk.Button(select_frame, text="Seleccionar Todos", command=self.select_all_ports)
        select_all_btn.pack(side=tk.LEFT, padx=5)
        
        deselect_all_btn = ttk.Button(select_frame, text="Deseleccionar Todos", command=self.deselect_all_ports)
        deselect_all_btn.pack(side=tk.LEFT, padx=5)
        
        # Separator
        ttk.Separator(main_frame, orient=tk.HORIZONTAL).pack(fill=tk.X, pady=10)
        
        # Frame para progreso de carga
        progress_frame = ttk.LabelFrame(main_frame, text="Progreso de Carga", padding=10)
        progress_frame.pack(fill=tk.X, pady=5)
        
        # Scrolled frame para barras de progreso
        self.progress_canvas = tk.Canvas(progress_frame)
        self.progress_scrollbar = ttk.Scrollbar(progress_frame, orient="vertical", command=self.progress_canvas.yview)
        self.progress_scrollable_frame = ttk.Frame(self.progress_canvas)
        
        self.progress_scrollable_frame.bind(
            "<Configure>",
            lambda e: self.progress_canvas.configure(
                scrollregion=self.progress_canvas.bbox("all")
            )
        )
        
        self.progress_canvas.create_window((0, 0), window=self.progress_scrollable_frame, anchor="nw")
        self.progress_canvas.configure(yscrollcommand=self.progress_scrollbar.set)
        
        self.progress_canvas.pack(side="left", fill="both", expand=True)
        self.progress_scrollbar.pack(side="right", fill="y")
        
        # Frame para log
        log_frame = ttk.LabelFrame(main_frame, text="Log", padding=10)
        log_frame.pack(fill=tk.BOTH, expand=True, pady=5)
        
        # Área de texto con scrollbar para log
        self.log_text = scrolledtext.ScrolledText(log_frame, wrap=tk.WORD, height=10)
        self.log_text.pack(fill=tk.BOTH, expand=True)
        self.log_text.tag_configure("INFO", foreground="black")
        self.log_text.tag_configure("ERROR", foreground="red")
        self.log_text.tag_configure("WARNING", foreground="orange")
        self.log_text.configure(state="disabled")
        
        # Botón para limpiar log
        clear_log_btn = ttk.Button(log_frame, text="Limpiar Log", command=self.clear_log)
        clear_log_btn.pack(pady=5)
        
    def refresh_ports(self):
        """Actualiza la lista de puertos manualmente"""
        self.update_ports(force=True)
        
    def update_ports(self, force=False):
        """Actualiza la lista de puertos disponibles"""
        ports = [port.device for port in serial.tools.list_ports.comports()]
        
        # Verificar si hay cambios
        if ports != self.available_ports or force:
            self.available_ports = ports
            
            # Limpiar frame de puertos
            for widget in self.port_frame.winfo_children():
                widget.destroy()
                
            self.port_vars = {}
            
            if not ports:
                no_ports_label = ttk.Label(self.port_frame, text="No se encontraron puertos COM disponibles")
                no_ports_label.pack(pady=10)
            else:
                # Crear checkbuttons para cada puerto
                for i, port in enumerate(ports):
                    var = tk.BooleanVar(value=False)
                    self.port_vars[port] = var
                    
                    # Calcular fila y columna (2 columnas)
                    row = i // 2
                    col = i % 2
                    
                    frame = ttk.Frame(self.port_frame)
                    frame.grid(row=row, column=col, sticky="w", padx=10, pady=2)
                    
                    check = ttk.Checkbutton(frame, text=port, variable=var)
                    check.pack(side=tk.LEFT)
        
        # Programar próxima actualización si no estamos en proceso de carga
        if not self.is_uploading:
            self.root.after(2000, self.update_ports)
            
    def update_progress(self, port, progress):
        """Actualiza la barra de progreso de un puerto"""
        if port in self.progress_bars:
            self.progress_bars[port]["value"] = progress
            # Actualizar el porcentaje
            if port in self.progress_labels:
                self.progress_labels[port].config(text=f"{progress:.1f}%")
            self.root.update_idletasks()
            
    def update_status(self, port, status):
        """Actualiza el estado de un puerto"""
        if port in self.status_labels:
            self.status_labels[port].config(text=status)
            
            # Cambiar color según estado
            color = "black"
            if status == "Completado":
                color = "green"
            elif status == "Fallido":
                color = "red"
            elif status == "Detenido":
                color = "orange"
            elif status == "Error":
                color = "red"
                
            self.status_labels[port].config(foreground=color)
            self.root.update_idletasks()
            
    def select_all_ports(self):
        """Selecciona todos los puertos disponibles"""
        for var in self.port_vars.values():
            var.set(True)
            
    def deselect_all_ports(self):
        """Deselecciona todos los puertos disponibles"""
        for var in self.port_vars.values():
            var.set(False)
            
    def process_logs(self):
        """Procesa los mensajes en la cola de logs"""
        try:
            while not self.log_queue.empty():
                level, message = self.log_queue.get(block=False)
                self.log_text.configure(state="normal")
                self.log_text.insert(tk.END, message + "\n", level)
                self.log_text.see(tk.END)
                self.log_text.configure(state="disabled")
        except queue.Empty:
            pass
            
        # Programar próxima actualización
        self.root.after(100, self.process_logs)
        
    def clear_log(self):
        """Limpia el área de log"""
        self.log_text.configure(state="normal")
        self.log_text.delete(1.0, tk.END)
        self.log_text.configure(state="disabled")
        
    def start_upload(self):
        """Inicia el proceso de carga"""
        # Obtener puertos seleccionados
        selected_ports = [port for port, var in self.port_vars.items() if var.get()]
        
        if not selected_ports:
            messagebox.showwarning("Advertencia", "No hay puertos seleccionados")
            return
            
        # Cambiar estado de botones
        self.start_btn.config(state=tk.DISABLED)
        self.stop_btn.config(state=tk.NORMAL)
        self.is_uploading = True
        
        # Limpiar barras de progreso existentes
        for widget in self.progress_scrollable_frame.winfo_children():
            widget.destroy()
            
        self.progress_bars = {}
        self.status_labels = {}
        self.progress_labels = {}
        
        # Crear barras de progreso para cada puerto seleccionado
        for i, port in enumerate(selected_ports):
            port_frame = ttk.Frame(self.progress_scrollable_frame)
            port_frame.pack(fill=tk.X, pady=5)
            
            port_label = ttk.Label(port_frame, text=f"{port}:")
            port_label.pack(side=tk.LEFT, padx=(0, 10))
            
            progress_bar = ttk.Progressbar(port_frame, length=300, mode="determinate")
            progress_bar.pack(side=tk.LEFT, padx=5)
            self.progress_bars[port] = progress_bar
            
            progress_label = ttk.Label(port_frame, text="0.0%")
            progress_label.pack(side=tk.LEFT, padx=5)
            self.progress_labels[port] = progress_label
            
            status_label = ttk.Label(port_frame, text="Pendiente")
            status_label.pack(side=tk.LEFT, padx=5)
            self.status_labels[port] = status_label
        
        # Iniciar proceso de carga en un hilo separado
        self.uploader = MultiPortUploader(
            selected_ports, 
            self.parallel_var.get(), 
            self.log_queue,
            self.update_progress,
            self.update_status
        )
        
        self.upload_thread = threading.Thread(target=self.run_upload)
        self.upload_thread.start()
        
    def run_upload(self):
        """Ejecuta el proceso de carga en un hilo separado"""
        try:
            self.uploader.upload_all()
            self.uploader.print_results()
        except Exception as e:
            self.log_queue.put(("ERROR", f"Error inesperado: {e}"))
        finally:
            self.root.after(0, self.upload_completed)
            
    def upload_completed(self):
        """Callback cuando se completa la carga"""
        self.start_btn.config(state=tk.NORMAL)
        self.stop_btn.config(state=tk.DISABLED)
        self.is_uploading = False
        
        # Reiniciar la actualización de puertos
        self.update_ports()
            
    def stop_upload(self):
        """Detiene el proceso de carga"""
        if self.uploader:
            self.uploader.stop_all()
            
    def on_closing(self):
        """Manejador para cuando se cierra la ventana"""
        if self.is_uploading:
            if messagebox.askyesno("Confirmar Salida", "Hay una carga en progreso. ¿Desea detenerla y salir?"):
                self.stop_upload()
                self.root.destroy()
        else:
            self.root.destroy()


if __name__ == "__main__":
    # Verificar que ampy esté instalado
    try:
        subprocess.run(["ampy", "--version"], capture_output=True, text=True)
    except FileNotFoundError:
        print("Error: No se encontró el comando 'ampy'. Asegúrate de tenerlo instalado:")
        print("pip install adafruit-ampy")
        sys.exit(1)
        
    # Verificar que pyserial esté instalado (para list_ports)
    try:
        import serial.tools.list_ports
    except ImportError:
        print("Error: No se encontró el módulo 'pyserial'. Asegúrate de tenerlo instalado:")
        print("pip install pyserial")
        sys.exit(1)
        
    root = tk.Tk()
    app = UploaderGUI(root)
    root.protocol("WM_DELETE_WINDOW", app.on_closing)
    root.mainloop()