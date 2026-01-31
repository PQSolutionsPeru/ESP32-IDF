#!/bin/bash
# ============================================
# Configuration Files Backup Script
# ============================================
# For HDD-Monitor VM - Weekly configuration backups
# Maintains 90 days of backups
# Runs weekly on Sundays at 3 AM via systemd timer

# Configuration
BACKUP_DIR="/home/pqsolutionsperu/backups/configs"
RETENTION_DAYS=90
TIMESTAMP=$(date +'%Y%m%d_%H%M%S')
BACKUP_FILE="$BACKUP_DIR/configs_${TIMESTAMP}.tar.gz"
LOGFILE="/var/log/hdd_monitor_backups.log"

# ============================================
# FUNCTIONS
# ============================================

log_message() {
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] $1" >> "$LOGFILE"
}

# ============================================
# MAIN BACKUP PROCESS
# ============================================

log_message "========================================"
log_message "Starting configuration backup"

# Create backup directory if it doesn't exist
mkdir -p "$BACKUP_DIR"
if [ $? -ne 0 ]; then
    log_message "ERROR: Failed to create backup directory"
    exit 1
fi

# List of files and directories to backup
log_message "Backing up configuration files..."

# Create temporary file list
TEMP_LIST=$(mktemp)

# Add files that exist to the list
# Application scripts
[ -f "/home/pqsolutionsperu/main.py" ] && echo "/home/pqsolutionsperu/main.py" >> "$TEMP_LIST"
[ -f "/home/pqsolutionsperu/mqtt_client.py" ] && echo "/home/pqsolutionsperu/mqtt_client.py" >> "$TEMP_LIST"
[ -f "/home/pqsolutionsperu/firestore_handler.py" ] && echo "/home/pqsolutionsperu/firestore_handler.py" >> "$TEMP_LIST"
[ -f "/home/pqsolutionsperu/notification_handler.py" ] && echo "/home/pqsolutionsperu/notification_handler.py" >> "$TEMP_LIST"
[ -f "/home/pqsolutionsperu/config.py" ] && echo "/home/pqsolutionsperu/config.py" >> "$TEMP_LIST"

# Monitoring scripts
[ -f "/home/pqsolutionsperu/resource_monitor.py" ] && echo "/home/pqsolutionsperu/resource_monitor.py" >> "$TEMP_LIST"
[ -f "/home/pqsolutionsperu/cleanup_esp32_logs.sh" ] && echo "/home/pqsolutionsperu/cleanup_esp32_logs.sh" >> "$TEMP_LIST"
[ -f "/home/pqsolutionsperu/backup_postgresql.sh" ] && echo "/home/pqsolutionsperu/backup_postgresql.sh" >> "$TEMP_LIST"
[ -f "/home/pqsolutionsperu/backup_configs.sh" ] && echo "/home/pqsolutionsperu/backup_configs.sh" >> "$TEMP_LIST"

# Nginx configuration
[ -f "/etc/nginx/sites-available/hddm.pqsolutionsperu.com" ] && echo "/etc/nginx/sites-available/hddm.pqsolutionsperu.com" >> "$TEMP_LIST"
[ -f "/etc/nginx/nginx.conf" ] && echo "/etc/nginx/nginx.conf" >> "$TEMP_LIST"

# Mosquitto MQTT configuration
[ -d "/etc/mosquitto/conf.d" ] && echo "/etc/mosquitto/conf.d" >> "$TEMP_LIST"
[ -f "/etc/mosquitto/mosquitto.conf" ] && echo "/etc/mosquitto/mosquitto.conf" >> "$TEMP_LIST"

# Netdata configuration
[ -f "/etc/netdata/netdata.conf" ] && echo "/etc/netdata/netdata.conf" >> "$TEMP_LIST"
[ -d "/etc/netdata/health.d" ] && echo "/etc/netdata/health.d" >> "$TEMP_LIST"
[ -f "/etc/netdata/health_alarm_notify.conf" ] && echo "/etc/netdata/health_alarm_notify.conf" >> "$TEMP_LIST"

# Systemd services
[ -f "/etc/systemd/system/backup-postgresql.service" ] && echo "/etc/systemd/system/backup-postgresql.service" >> "$TEMP_LIST"
[ -f "/etc/systemd/system/backup-postgresql.timer" ] && echo "/etc/systemd/system/backup-postgresql.timer" >> "$TEMP_LIST"
[ -f "/etc/systemd/system/backup-configs.service" ] && echo "/etc/systemd/system/backup-configs.service" >> "$TEMP_LIST"
[ -f "/etc/systemd/system/backup-configs.timer" ] && echo "/etc/systemd/system/backup-configs.timer" >> "$TEMP_LIST"
[ -f "/etc/systemd/system/cleanup-esp32-logs.service" ] && echo "/etc/systemd/system/cleanup-esp32-logs.service" >> "$TEMP_LIST"
[ -f "/etc/systemd/system/cleanup-esp32-logs.timer" ] && echo "/etc/systemd/system/cleanup-esp32-logs.timer" >> "$TEMP_LIST"
[ -f "/etc/systemd/system/resource-monitor.service" ] && echo "/etc/systemd/system/resource-monitor.service" >> "$TEMP_LIST"

# Logrotate configuration
[ -f "/etc/logrotate.d/hdd-monitor" ] && echo "/etc/logrotate.d/hdd-monitor" >> "$TEMP_LIST"

# Count files to backup
FILE_COUNT=$(wc -l < "$TEMP_LIST")
log_message "Found $FILE_COUNT items to backup"

if [ "$FILE_COUNT" -eq 0 ]; then
    log_message "WARNING: No configuration files found to backup"
    rm -f "$TEMP_LIST"
    exit 1
fi

# Perform backup using tar
tar -czf "$BACKUP_FILE" \
    --files-from="$TEMP_LIST" \
    --ignore-failed-read \
    2>> "$LOGFILE"

# Clean up temp file
rm -f "$TEMP_LIST"

# Check if backup was successful
if [ -f "$BACKUP_FILE" ]; then
    BACKUP_SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
    log_message "✓ Backup completed successfully: $BACKUP_SIZE"

    # Verify backup integrity
    if tar -tzf "$BACKUP_FILE" > /dev/null 2>&1; then
        log_message "✓ Backup integrity verified"
    else
        log_message "✗ WARNING: Backup file may be corrupted"
    fi

    # Cleanup old backups
    log_message "Cleaning up backups older than $RETENTION_DAYS days"
    BEFORE=$(find "$BACKUP_DIR" -name "*.tar.gz" -type f | wc -l)

    find "$BACKUP_DIR" -name "*.tar.gz" -type f -mtime +$RETENTION_DAYS -delete

    AFTER=$(find "$BACKUP_DIR" -name "*.tar.gz" -type f | wc -l)
    DELETED=$((BEFORE - AFTER))

    log_message "Cleanup: $DELETED old backup(s) deleted ($BEFORE → $AFTER)"

    # Calculate total backup directory size
    TOTAL_SIZE=$(du -sh "$BACKUP_DIR" | cut -f1)
    log_message "Total backup directory size: $TOTAL_SIZE"

    log_message "Configuration backup completed successfully"
    exit 0
else
    log_message "✗ ERROR: Backup file was not created"
    exit 1
fi
