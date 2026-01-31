#!/bin/bash
# ============================================
# ESP32 Logs Auto-Cleanup Script
# ============================================
# For HDD-Monitor VM - Prevents disk space exhaustion
# Maintains only the last 7 days of ESP32 logs
# Runs daily at 3 AM via systemd timer

# Configuration
LOG_DIR="/home/pqsolutions/esp32_log"
RETENTION_DAYS=7
LOGFILE="/var/log/hdd_monitor_cleanup.log"

# Ensure log directory exists
if [ ! -d "$LOG_DIR" ]; then
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] ERROR: Log directory $LOG_DIR does not exist" >> "$LOGFILE"
    exit 1
fi

# Start cleanup
echo "[$(date +'%Y-%m-%d %H:%M:%S')] ========================================" >> "$LOGFILE"
echo "[$(date +'%Y-%m-%d %H:%M:%S')] Starting ESP32 logs cleanup" >> "$LOGFILE"

# Count files before cleanup
BEFORE=$(find "$LOG_DIR" -type f -name "*.txt" 2>/dev/null | wc -l)
BEFORE_SIZE=$(du -sh "$LOG_DIR" 2>/dev/null | cut -f1)

echo "[$(date +'%Y-%m-%d %H:%M:%S')] Before: $BEFORE files, $BEFORE_SIZE" >> "$LOGFILE"

# Delete files older than retention period
DELETED_COUNT=0
while IFS= read -r -d '' file; do
    echo "[$(date +'%Y-%m-%d %H:%M:%S')]   Deleting: $(basename "$file")" >> "$LOGFILE"
    rm -f "$file"
    ((DELETED_COUNT++))
done < <(find "$LOG_DIR" -type f -name "*.txt" -mtime +$RETENTION_DAYS -print0 2>/dev/null)

# Count files after cleanup
AFTER=$(find "$LOG_DIR" -type f -name "*.txt" 2>/dev/null | wc -l)
AFTER_SIZE=$(du -sh "$LOG_DIR" 2>/dev/null | cut -f1)

echo "[$(date +'%Y-%m-%d %H:%M:%S')] After: $AFTER files, $AFTER_SIZE" >> "$LOGFILE"
echo "[$(date +'%Y-%m-%d %H:%M:%S')] Deleted: $DELETED_COUNT files" >> "$LOGFILE"

# Check disk usage
DISK_USAGE=$(df / | awk 'NR==2 {print $5}' | sed 's/%//')
echo "[$(date +'%Y-%m-%d %H:%M:%S')] Disk usage: ${DISK_USAGE}%" >> "$LOGFILE"

# Alert if disk usage is high
if [ "$DISK_USAGE" -gt 80 ]; then
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] ⚠️  WARNING: Disk usage is at ${DISK_USAGE}%" >> "$LOGFILE"
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] ⚠️  Manual intervention may be required" >> "$LOGFILE"
fi

if [ "$DISK_USAGE" -gt 90 ]; then
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] 🔥 CRITICAL: Disk usage is at ${DISK_USAGE}%" >> "$LOGFILE"
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] 🔥 IMMEDIATE ACTION REQUIRED" >> "$LOGFILE"
fi

echo "[$(date +'%Y-%m-%d %H:%M:%S')] Cleanup completed successfully" >> "$LOGFILE"
echo "[$(date +'%Y-%m-%d %H:%M:%S')] ========================================" >> "$LOGFILE"
echo "" >> "$LOGFILE"

exit 0
