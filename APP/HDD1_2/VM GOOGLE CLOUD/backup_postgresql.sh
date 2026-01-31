#!/bin/bash
# ============================================
# PostgreSQL Database Backup Script
# ============================================
# For HDD-Monitor VM - Automated daily backups
# Maintains 30 days of backups
# Runs daily at 2 AM via systemd timer

# Configuration
BACKUP_DIR="/home/pqsolutionsperu/backups/postgresql"
RETENTION_DAYS=30
TIMESTAMP=$(date +'%Y%m%d_%H%M%S')
BACKUP_FILE="$BACKUP_DIR/hdd_monitor_${TIMESTAMP}.sql.gz"
LOGFILE="/var/log/hdd_monitor_backups.log"

# Database configuration (from environment variables)
DB_NAME="${PG_DATABASE:-hdd_monitor}"
DB_USER="${PG_USER:-hdd_monitor_user}"
DB_HOST="${PG_HOST:-localhost}"
DB_PASSWORD="${PG_PASSWORD}"

# Healthchecks.io (optional)
HEALTHCHECKS_POSTGRES_URL="${HEALTHCHECKS_POSTGRES_URL}"

# ============================================
# FUNCTIONS
# ============================================

log_message() {
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] $1" >> "$LOGFILE"
}

ping_healthcheck() {
    if [ -n "$HEALTHCHECKS_POSTGRES_URL" ]; then
        curl -fsS --retry 3 "$1" > /dev/null 2>&1 || true
    fi
}

# ============================================
# MAIN BACKUP PROCESS
# ============================================

log_message "========================================"
log_message "Starting PostgreSQL backup"

# Create backup directory if it doesn't exist
mkdir -p "$BACKUP_DIR"
if [ $? -ne 0 ]; then
    log_message "ERROR: Failed to create backup directory"
    ping_healthcheck "${HEALTHCHECKS_POSTGRES_URL}/fail"
    exit 1
fi

# Check if PostgreSQL is running
if ! systemctl is-active --quiet postgresql; then
    log_message "ERROR: PostgreSQL service is not running"
    ping_healthcheck "${HEALTHCHECKS_POSTGRES_URL}/fail"
    exit 1
fi

# Check if password is set
if [ -z "$DB_PASSWORD" ]; then
    log_message "ERROR: PG_PASSWORD environment variable not set"
    ping_healthcheck "${HEALTHCHECKS_POSTGRES_URL}/fail"
    exit 1
fi

# Perform backup
log_message "Backing up database: $DB_NAME"
log_message "Target file: $BACKUP_FILE"

PGPASSWORD="$DB_PASSWORD" pg_dump \
    -U "$DB_USER" \
    -h "$DB_HOST" \
    -d "$DB_NAME" \
    --verbose \
    2>> "$LOGFILE" | gzip > "$BACKUP_FILE"

# Check if backup was successful
if [ ${PIPESTATUS[0]} -eq 0 ]; then
    BACKUP_SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
    log_message "✓ Backup completed successfully: $BACKUP_SIZE"

    # Cleanup old backups
    log_message "Cleaning up backups older than $RETENTION_DAYS days"
    BEFORE=$(find "$BACKUP_DIR" -name "*.sql.gz" -type f | wc -l)

    find "$BACKUP_DIR" -name "*.sql.gz" -type f -mtime +$RETENTION_DAYS -delete

    AFTER=$(find "$BACKUP_DIR" -name "*.sql.gz" -type f | wc -l)
    DELETED=$((BEFORE - AFTER))

    log_message "Cleanup: $DELETED old backup(s) deleted ($BEFORE → $AFTER)"

    # Calculate total backup directory size
    TOTAL_SIZE=$(du -sh "$BACKUP_DIR" | cut -f1)
    log_message "Total backup directory size: $TOTAL_SIZE"

    # Success notification
    ping_healthcheck "$HEALTHCHECKS_POSTGRES_URL"
    log_message "Backup process completed successfully"

    exit 0
else
    log_message "✗ ERROR: Backup failed"
    log_message "Check PostgreSQL logs: sudo journalctl -u postgresql -n 50"

    # Failure notification
    ping_healthcheck "${HEALTHCHECKS_POSTGRES_URL}/fail"

    # Clean up partial backup file
    if [ -f "$BACKUP_FILE" ]; then
        rm -f "$BACKUP_FILE"
        log_message "Removed partial backup file"
    fi

    exit 1
fi
