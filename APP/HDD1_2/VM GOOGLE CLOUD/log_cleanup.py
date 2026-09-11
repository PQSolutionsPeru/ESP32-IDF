#!/usr/bin/env python3
"""
Sistema de limpieza cíclica de logs para HDD-Monitor VM
Elimina logs antiguos automáticamente para prevenir llenado de memoria
Rutas basadas en la configuración real de los servicios
"""

import os
import sys
import logging
import time
import glob
from datetime import datetime, timedelta
from pathlib import Path
import shutil
import argparse

# Configuración de limpieza
LOG_RETENTION_DAYS = 30  # Mantener logs por máximo 30 días
ESP32_LOG_BASE_DIR = '/home/pqsolutions/esp32_log'  # Confirmado en log_server.py
SYSTEM_LOG_DIR = '/var/log'
MIN_FREE_SPACE_GB = 5  # Espacio mínimo libre requerido en GB
MAX_STORAGE_USAGE_PERCENT = 80  # Máximo uso de almacenamiento permitido

# NOTA: Los logs del sistema ahora se manejan con logrotate (2 meses retención)
# Este script se enfoca solo en ESP32 logs que no están manejados por logrotate
SYSTEM_LOGS_TO_CLEAN = []  # Vacío porque logrotate maneja estos logs

# Configuración de logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler('/var/log/log_cleanup.log'),
        logging.StreamHandler(sys.stdout)
    ]
)
logger = logging.getLogger(__name__)

class LogCleanup:
    def __init__(self, retention_days=LOG_RETENTION_DAYS, dry_run=False):
        self.retention_days = retention_days
        self.dry_run = dry_run
        self.cutoff_date = datetime.now() - timedelta(days=retention_days)
        self.total_cleaned = 0
        self.total_size_cleaned = 0
        
    def get_disk_usage(self, path):
        """Obtiene información de uso del disco"""
        try:
            usage = shutil.disk_usage(path)
            return {
                'total': usage.total,
                'used': usage.used,
                'free': usage.free,
                'percent_used': (usage.used / usage.total) * 100
            }
        except Exception as e:
            logger.error(f"Error obteniendo uso de disco para {path}: {e}")
            return None
            
    def should_emergency_cleanup(self):
        """Verifica si se necesita limpieza de emergencia"""
        usage = self.get_disk_usage('/')
        if not usage:
            return False
            
        free_gb = usage['free'] / (1024**3)
        percent_used = usage['percent_used']
        
        if free_gb < MIN_FREE_SPACE_GB or percent_used > MAX_STORAGE_USAGE_PERCENT:
            logger.warning(f"LIMPIEZA DE EMERGENCIA: Espacio libre: {free_gb:.1f}GB, Uso: {percent_used:.1f}%")
            return True
        return False
        
    def clean_esp32_logs(self):
        """Limpia logs de ESP32 por fecha"""
        if not os.path.exists(ESP32_LOG_BASE_DIR):
            logger.info(f"Directorio ESP32 logs no existe: {ESP32_LOG_BASE_DIR}")
            return
            
        logger.info(f"Limpiando logs ESP32 anteriores a {self.cutoff_date.strftime('%Y-%m-%d')} en {ESP32_LOG_BASE_DIR}")
        
        for esp32_dir in Path(ESP32_LOG_BASE_DIR).iterdir():
            if not esp32_dir.is_dir():
                continue
                
            esp32_id = esp32_dir.name
            cleaned_files = 0
            cleaned_size = 0
            
            # Buscar archivos de log (basado en log_server.py que acepta cualquier archivo)
            log_files = list(esp32_dir.glob('*'))
            
            for log_file in log_files:
                if not log_file.is_file():
                    continue
                    
                try:
                    file_mtime = datetime.fromtimestamp(log_file.stat().st_mtime)
                    file_size = log_file.stat().st_size
                    
                    if file_mtime < self.cutoff_date:
                        if self.dry_run:
                            logger.info(f"[DRY RUN] Eliminaría: {log_file} ({file_size/1024:.1f}KB)")
                        else:
                            log_file.unlink()
                            logger.info(f"Eliminado: {log_file} ({file_size/1024:.1f}KB)")
                        
                        cleaned_files += 1
                        cleaned_size += file_size
                        
                except Exception as e:
                    logger.error(f"Error procesando archivo {log_file}: {e}")
                    
            if cleaned_files > 0:
                logger.info(f"ESP32 {esp32_id}: {cleaned_files} archivos eliminados, {cleaned_size/1024/1024:.2f}MB liberados")
                self.total_cleaned += cleaned_files
                self.total_size_cleaned += cleaned_size
                
    def rotate_system_log(self, log_path):
        """Rota un log específico del sistema"""
        try:
            if not os.path.exists(log_path):
                logger.debug(f"Log no existe: {log_path}")
                return 0
                
            log_file = Path(log_path)
            file_size = log_file.stat().st_size
            file_age_days = (datetime.now() - datetime.fromtimestamp(log_file.stat().st_mtime)).days
            
            # Rotar si el archivo es > 50MB o > 7 días
            should_rotate = file_size > 50 * 1024 * 1024 or file_age_days > 7
            
            if should_rotate:
                if self.dry_run:
                    logger.info(f"[DRY RUN] Rotaría log: {log_path} ({file_size/1024/1024:.1f}MB, {file_age_days} días)")
                    return file_size
                else:
                    # Crear nombre con timestamp
                    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
                    backup_path = f"{log_path}.{timestamp}"
                    
                    # Mover archivo actual a backup
                    shutil.move(str(log_file), backup_path)
                    
                    # Crear nuevo archivo vacío
                    log_file.touch()
                    
                    # Comprimir backup si es grande (> 10MB)
                    if file_size > 10 * 1024 * 1024:
                        try:
                            import gzip
                            with open(backup_path, 'rb') as f_in:
                                with gzip.open(f"{backup_path}.gz", 'wb') as f_out:
                                    shutil.copyfileobj(f_in, f_out)
                            os.remove(backup_path)
                            backup_path += ".gz"
                        except Exception as e:
                            logger.warning(f"No se pudo comprimir {backup_path}: {e}")
                    
                    logger.info(f"Log rotado: {log_path} -> {backup_path} ({file_size/1024/1024:.2f}MB)")
                    return file_size
            
            return 0
            
        except Exception as e:
            logger.error(f"Error rotando log {log_path}: {e}")
            return 0
            
    def clean_old_rotated_logs(self, base_log_path):
        """Elimina logs rotados antiguos"""
        try:
            # Buscar archivos rotados: log_path.YYYYMMDD_HHMMSS[.gz]
            pattern = f"{base_log_path}.*"
            rotated_files = glob.glob(pattern)
            
            # Filtrar solo archivos que parecen rotados
            actual_rotated = []
            for file_path in rotated_files:
                if file_path != base_log_path and ('.' in file_path.split('/')[-1]):
                    actual_rotated.append(file_path)
            
            # Ordenar por fecha de modificación (más reciente primero)
            actual_rotated.sort(key=lambda x: os.path.getmtime(x), reverse=True)
            
            # Mantener solo los 5 más recientes, eliminar el resto si son > retention_days
            for i, rotated_file in enumerate(actual_rotated):
                try:
                    file_age_days = (datetime.now() - datetime.fromtimestamp(os.path.getmtime(rotated_file))).days
                    
                    # Eliminar si es muy antiguo O si tenemos más de 5 backups
                    should_delete = file_age_days > self.retention_days or i >= 5
                    
                    if should_delete:
                        file_size = os.path.getsize(rotated_file)
                        if self.dry_run:
                            logger.info(f"[DRY RUN] Eliminaría rotado: {rotated_file} ({file_size/1024:.1f}KB)")
                        else:
                            os.remove(rotated_file)
                            logger.info(f"Eliminado rotado antiguo: {rotated_file} ({file_size/1024:.1f}KB)")
                            self.total_size_cleaned += file_size
                            self.total_cleaned += 1
                            
                except Exception as e:
                    logger.error(f"Error eliminando rotado {rotated_file}: {e}")
                    
        except Exception as e:
            logger.error(f"Error limpiando rotados de {base_log_path}: {e}")
                
    def clean_system_logs(self):
        """Limpia y rota logs del sistema"""
        logger.info("Procesando logs del sistema")
        
        for log_path in SYSTEM_LOGS_TO_CLEAN:
            try:
                # Rotar log actual si es necesario
                rotated_size = self.rotate_system_log(log_path)
                if rotated_size > 0:
                    self.total_size_cleaned += rotated_size
                    self.total_cleaned += 1
                
                # Limpiar logs rotados antiguos
                self.clean_old_rotated_logs(log_path)
                
            except Exception as e:
                logger.error(f"Error procesando log del sistema {log_path}: {e}")
                
    def emergency_cleanup(self):
        """Limpieza más agresiva en caso de emergencia"""
        logger.warning("Ejecutando limpieza de emergencia")
        
        # Reducir retención temporalmente a 15 días
        original_cutoff = self.cutoff_date
        self.cutoff_date = datetime.now() - timedelta(days=15)
        
        # Limpiar ESP32 logs con retención reducida
        self.clean_esp32_logs()
        
        # Truncar logs del sistema grandes inmediatamente
        for log_path in SYSTEM_LOGS_TO_CLEAN:
            try:
                if os.path.exists(log_path):
                    file_size = os.path.getsize(log_path)
                    if file_size > 10 * 1024 * 1024:  # > 10MB
                        if not self.dry_run:
                            # Mantener últimas 1000 líneas
                            with open(log_path, 'r') as f:
                                lines = f.readlines()
                            
                            if len(lines) > 1000:
                                with open(log_path, 'w') as f:
                                    f.writelines(lines[-1000:])
                                
                                saved_size = len(''.join(lines[-1000:]))
                                freed_size = file_size - saved_size
                                
                                logger.warning(f"Log truncado en emergencia: {log_path} ({freed_size/1024/1024:.1f}MB liberados)")
                                self.total_size_cleaned += freed_size
                        else:
                            logger.info(f"[DRY RUN] Truncaría en emergencia: {log_path} ({file_size/1024/1024:.1f}MB)")
                            
            except Exception as e:
                logger.error(f"Error en limpieza de emergencia para {log_path}: {e}")
                
        self.cutoff_date = original_cutoff
        
    def run_cleanup(self):
        """Ejecuta la limpieza completa"""
        start_time = datetime.now()
        logger.info(f"Iniciando limpieza de logs - Retención: {self.retention_days} días")
        logger.info(f"ESP32 logs: {ESP32_LOG_BASE_DIR}")
        logger.info("System logs: Manejados por logrotate (2 meses retención)")
        
        if self.dry_run:
            logger.info("MODO DRY RUN - No se eliminarán archivos realmente")
            
        # Verificar uso de disco antes
        initial_usage = self.get_disk_usage('/')
        if initial_usage:
            logger.info(f"Uso inicial de disco: {initial_usage['percent_used']:.1f}% "
                       f"({initial_usage['free']/1024**3:.1f}GB libres)")
        
        # Verificar si se necesita limpieza de emergencia
        emergency_needed = self.should_emergency_cleanup()
        
        if emergency_needed:
            self.emergency_cleanup()
        else:
            # Limpieza normal
            self.clean_esp32_logs()
            self.clean_system_logs()
            
        # Verificar uso de disco después
        final_usage = self.get_disk_usage('/')
        if final_usage and initial_usage:
            freed_gb = (final_usage['free'] - initial_usage['free']) / (1024**3)
            logger.info(f"Uso final de disco: {final_usage['percent_used']:.1f}% "
                       f"({final_usage['free']/1024**3:.1f}GB libres, {freed_gb:+.2f}GB)")
        
        duration = datetime.now() - start_time
        logger.info(f"Limpieza completada: {self.total_cleaned} archivos procesados, "
                   f"{self.total_size_cleaned/1024/1024:.2f}MB liberados en {duration.total_seconds():.1f}s")
        
        return {
            'files_cleaned': self.total_cleaned,
            'bytes_cleaned': self.total_size_cleaned,
            'duration_seconds': duration.total_seconds(),
            'emergency_cleanup': emergency_needed
        }

def main():
    parser = argparse.ArgumentParser(description='Sistema de limpieza cíclica de logs HDD-Monitor')
    parser.add_argument('--retention-days', type=int, default=LOG_RETENTION_DAYS,
                       help=f'Días de retención de logs (default: {LOG_RETENTION_DAYS})')
    parser.add_argument('--dry-run', action='store_true',
                       help='Mostrar qué archivos se procesarían sin hacerlo realmente')
    parser.add_argument('--force-emergency', action='store_true',
                       help='Forzar limpieza de emergencia')
    parser.add_argument('--esp32-only', action='store_true',
                       help='Limpiar solo logs de ESP32')
    parser.add_argument('--system-only', action='store_true',
                       help='Limpiar solo logs del sistema')
    
    args = parser.parse_args()
    
    try:
        cleanup = LogCleanup(retention_days=args.retention_days, dry_run=args.dry_run)
        
        if args.force_emergency:
            cleanup.emergency_cleanup()
        elif args.esp32_only:
            cleanup.clean_esp32_logs()
        elif args.system_only:
            cleanup.clean_system_logs()
        else:
            cleanup.run_cleanup()
            
        return 0
        
    except Exception as e:
        logger.error(f"Error durante limpieza: {e}", exc_info=True)
        return 1

if __name__ == '__main__':
    sys.exit(main())