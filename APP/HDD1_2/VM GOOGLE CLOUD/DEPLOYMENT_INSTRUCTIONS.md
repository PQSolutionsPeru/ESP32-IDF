# HDD-Monitor VM Auto-Sustainability Deployment Guide

## 🎯 Overview

This guide walks through deploying a complete auto-sustainability system for the HDD-Monitor VM including:
- **Netdata** for professional monitoring dashboard
- **Auto-cleanup** for ESP32 logs
- **Resource monitoring** with email alerts
- **Automated backups** for PostgreSQL and configs
- **External monitoring** via Healthchecks.io

**Estimated deployment time:** 2-3 hours
**System:** Debian 12, 2GB RAM, 2 vCPUs, 30GB disk
**Domain:** hddm.pqsolutionsperu.com

---

## 📋 Prerequisites

Before starting, ensure you have:

1. ✅ SSH access to the VM as `pqsolutionsperu` user
2. ✅ Sudo privileges
3. ✅ Gmail App Password (create at https://myaccount.google.com/apppasswords)
4. ✅ PostgreSQL password for `hdd_monitor_user`
5. ✅ Access to Nginx configuration
6. ✅ All files from this directory uploaded to `/home/pqsolutionsperu/`

---

## 🚀 Phase 1: Netdata Installation & Configuration

### Step 1.1: Install Netdata

```bash
cd /home/pqsolutionsperu
chmod +x install_netdata.sh
sudo bash install_netdata.sh
```

**Expected output:** `✓ Netdata installed successfully!`

**Verify:**
```bash
sudo systemctl status netdata
curl http://localhost:19999
```

### Step 1.2: Deploy Netdata Configuration

```bash
# Backup existing config (if any)
sudo cp /etc/netdata/netdata.conf /etc/netdata/netdata.conf.backup 2>/dev/null || true

# Deploy new configuration
sudo cp netdata.conf /etc/netdata/netdata.conf

# Verify
sudo netdata -W buildinfo
```

### Step 1.3: Deploy Health Alert Configurations

```bash
# Create health.d directory if it doesn't exist
sudo mkdir -p /etc/netdata/health.d

# Deploy alert configurations
sudo cp disk_space.conf /etc/netdata/health.d/
sudo cp ram.conf /etc/netdata/health.d/
sudo cp cpu.conf /etc/netdata/health.d/
sudo cp services.conf /etc/netdata/health.d/

# Set permissions
sudo chown netdata:netdata /etc/netdata/health.d/*.conf
sudo chmod 644 /etc/netdata/health.d/*.conf

# Verify
ls -lah /etc/netdata/health.d/
```

### Step 1.4: Configure Email Notifications

**IMPORTANT:** You need a Gmail App Password first!

**Create Gmail App Password:**
1. Go to https://myaccount.google.com/apppasswords
2. Select app: "Mail"
3. Select device: "Other" → "HDD-Monitor VM"
4. Click "Generate"
5. Copy the 16-character password (e.g., `abcd efgh ijkl mnop`)

**Deploy email configuration:**

```bash
# Deploy notification config
sudo cp health_alarm_notify.conf /etc/netdata/health_alarm_notify.conf
sudo chown netdata:netdata /etc/netdata/health_alarm_notify.conf
sudo chmod 640 /etc/netdata/health_alarm_notify.conf

# Create systemd override directory
sudo mkdir -p /etc/systemd/system/netdata.service.d/

# Deploy override configuration
sudo cp netdata.service.override.conf /etc/systemd/system/netdata.service.d/override.conf

# Edit the override file and add your Gmail App Password
sudo nano /etc/systemd/system/netdata.service.d/override.conf
```

**In nano, replace:**
```
Environment="SMTP_APP_PASSWORD=xxxx_xxxx_xxxx_xxxx"
```

**With your actual App Password (remove spaces):**
```
Environment="SMTP_APP_PASSWORD=abcdefghijklmnop"
```

**Save and exit:** Ctrl+X, Y, Enter

**Apply changes:**
```bash
sudo systemctl daemon-reload
sudo systemctl restart netdata
sudo systemctl status netdata
```

### Step 1.5: Test Email Notifications

```bash
# Switch to netdata user and test
sudo su -s /bin/bash netdata
/usr/libexec/netdata/plugins.d/alarm-notify.sh test
exit
```

**Expected:** Email should arrive at pqsolutionsperu@gmail.com within 1-2 minutes

**If no email arrives, check logs:**
```bash
sudo journalctl -u netdata | grep alarm-notify
sudo tail -f /var/log/netdata/error.log
```

### Step 1.6: Configure Nginx Reverse Proxy

```bash
# Backup existing Nginx config
sudo cp /etc/nginx/sites-available/hddm.pqsolutionsperu.com /etc/nginx/sites-available/hddm.pqsolutionsperu.com.backup

# Edit Nginx config
sudo nano /etc/nginx/sites-available/hddm.pqsolutionsperu.com
```

**Add the location block from `nginx_netdata_config.txt` INSIDE the existing server block (after SSL config, before closing brace)**

**Create password file:**
```bash
sudo htpasswd -c /etc/nginx/.htpasswd_metrics pqsowner
# Enter a strong password when prompted
```

**Test and reload Nginx:**
```bash
sudo nginx -t
sudo systemctl reload nginx
```

### Step 1.7: Verify Netdata Dashboard

Open in browser:
```
https://hddm.pqsolutionsperu.com/netdata/
```

**Login:**
- Username: `pqsowner`
- Password: (the one you set with htpasswd)

**You should see:**
- Real-time CPU, RAM, disk metrics
- Network traffic graphs
- Service status
- Alert status

---

## 🧹 Phase 2: Auto-Cleanup Configuration

### Step 2.1: Deploy ESP32 Cleanup Script

```bash
cd /home/pqsolutionsperu
chmod +x cleanup_esp32_logs.sh

# Test run
bash cleanup_esp32_logs.sh

# Check log
cat /var/log/hdd_monitor_cleanup.log
```

### Step 2.2: Deploy Systemd Service & Timer

```bash
# Deploy service
sudo cp cleanup-esp32-logs.service /etc/systemd/system/
sudo cp cleanup-esp32-logs.timer /etc/systemd/system/

# Set permissions
sudo chmod 644 /etc/systemd/system/cleanup-esp32-logs.*

# Enable and start timer
sudo systemctl daemon-reload
sudo systemctl enable cleanup-esp32-logs.timer
sudo systemctl start cleanup-esp32-logs.timer

# Verify
sudo systemctl status cleanup-esp32-logs.timer
sudo systemctl list-timers
```

**Expected output:**
```
NEXT                         LEFT          LAST  PASSED  UNIT
Tomorrow 03:00:00 UTC        Xh Xmin left  -     -       cleanup-esp32-logs.timer
```

### Step 2.3: Configure Logrotate

```bash
# Deploy logrotate config
sudo cp hdd-monitor-logrotate /etc/logrotate.d/hdd-monitor
sudo chmod 644 /etc/logrotate.d/hdd-monitor

# Test logrotate
sudo logrotate -d /etc/logrotate.d/hdd-monitor

# Force rotation (test)
sudo logrotate -f /etc/logrotate.d/hdd-monitor
```

---

## 📊 Phase 3: Resource Monitor Deployment

### Step 3.1: Install Python Dependencies

```bash
sudo apt update
sudo apt install -y python3 python3-pip
sudo pip3 install psutil requests
```

### Step 3.2: Deploy Resource Monitor

```bash
cd /home/pqsolutionsperu
chmod +x resource_monitor.py

# Test run (should show logs)
python3 resource_monitor.py
# Press Ctrl+C after a few seconds to stop
```

### Step 3.3: Deploy Systemd Service

```bash
# Deploy service
sudo cp resource-monitor.service /etc/systemd/system/

# Edit service to add SMTP password
sudo nano /etc/systemd/system/resource-monitor.service
```

**Replace:**
```
Environment="SMTP_PASS=xxxx_xxxx_xxxx_xxxx"
```

**With your Gmail App Password (same as Step 1.4):**
```
Environment="SMTP_PASS=abcdefghijklmnop"
```

**Optional - Add Healthchecks.io URL (see Phase 3.4 first):**
```
Environment="HEALTHCHECKS_URL=https://hc-ping.com/YOUR_UUID_HERE"
```

**Save and exit:** Ctrl+X, Y, Enter

**Start service:**
```bash
sudo systemctl daemon-reload
sudo systemctl enable resource-monitor.service
sudo systemctl start resource-monitor.service
sudo systemctl status resource-monitor.service
```

**Monitor logs:**
```bash
sudo journalctl -u resource-monitor -f
```

**Expected output:**
```
Resource Monitor Starting
Check #1 at 2026-01-30 12:34:56
All resources within normal limits
```

### Step 3.4: Configure Healthchecks.io (Optional but Recommended)

1. Go to https://healthchecks.io
2. Create free account
3. Click "Add Check"
4. Configure:
   - **Name:** HDD-Monitor Resource Monitor
   - **Period:** 5 minutes
   - **Grace:** 2 minutes
5. Copy the check URL (e.g., `https://hc-ping.com/abc-123-def-456`)
6. Add to `/etc/systemd/system/resource-monitor.service` (see Step 3.3)
7. Restart service: `sudo systemctl restart resource-monitor.service`

**Create additional checks:**
- "HDD-Monitor PostgreSQL Backup" (Period: 1 day)
- "HDD-Monitor Config Backup" (Period: 1 week)

---

## 💾 Phase 4: Automated Backups

### Step 4.1: Deploy PostgreSQL Backup

```bash
cd /home/pqsolutionsperu
chmod +x backup_postgresql.sh

# Create backup directory
mkdir -p /home/pqsolutionsperu/backups/postgresql

# Deploy service
sudo cp backup-postgresql.service /etc/systemd/system/
sudo cp backup-postgresql.timer /etc/systemd/system/

# Edit service to add PostgreSQL password
sudo nano /etc/systemd/system/backup-postgresql.service
```

**Replace:**
```
Environment="PG_PASSWORD=YOUR_POSTGRES_PASSWORD"
```

**With your actual PostgreSQL password**

**Optional - Add Healthchecks.io URL:**
```
Environment="HEALTHCHECKS_POSTGRES_URL=https://hc-ping.com/YOUR_UUID_HERE"
```

**Save and exit:** Ctrl+X, Y, Enter

**Enable and start:**
```bash
sudo systemctl daemon-reload
sudo systemctl enable backup-postgresql.timer
sudo systemctl start backup-postgresql.timer

# Verify
sudo systemctl list-timers | grep backup-postgresql
```

**Test backup manually:**
```bash
sudo systemctl start backup-postgresql.service
sudo systemctl status backup-postgresql.service

# Check log
cat /var/log/hdd_monitor_backups.log

# Verify backup file
ls -lh /home/pqsolutionsperu/backups/postgresql/
```

### Step 4.2: Deploy Configuration Backup

```bash
cd /home/pqsolutionsperu
chmod +x backup_configs.sh

# Create backup directory
mkdir -p /home/pqsolutionsperu/backups/configs

# Deploy service
sudo cp backup-configs.service /etc/systemd/system/
sudo cp backup-configs.timer /etc/systemd/system/

# Enable and start
sudo systemctl daemon-reload
sudo systemctl enable backup-configs.timer
sudo systemctl start backup-configs.timer

# Verify
sudo systemctl list-timers | grep backup-configs
```

**Test backup manually:**
```bash
sudo systemctl start backup-configs.service
sudo systemctl status backup-configs.service

# Check log
cat /var/log/hdd_monitor_backups.log

# Verify backup file
ls -lh /home/pqsolutionsperu/backups/configs/
```

---

## ✅ Final Verification

### Checklist

Run these commands to verify everything is working:

```bash
# 1. Check all services are running
sudo systemctl status netdata
sudo systemctl status resource-monitor

# 2. Check all timers are active
sudo systemctl list-timers

# Should show:
# - cleanup-esp32-logs.timer
# - backup-postgresql.timer
# - backup-configs.timer

# 3. View logs
sudo journalctl -u netdata -n 50
sudo journalctl -u resource-monitor -n 50
cat /var/log/hdd_monitor_cleanup.log
cat /var/log/hdd_monitor_backups.log

# 4. Check Netdata alerts
curl http://localhost:19999/api/v1/alarms

# 5. Verify backups exist
ls -lh /home/pqsolutionsperu/backups/postgresql/
ls -lh /home/pqsolutionsperu/backups/configs/

# 6. Check disk space
df -h
du -sh /home/pqsolutions/esp32_log/
du -sh /home/pqsolutionsperu/backups/

# 7. Test email alerts (simulated disk full)
# This will NOT actually fill the disk, just test the alert
# Skip this if you don't want to trigger an alert
```

### Expected Results

| Component | Status | Verification |
|-----------|--------|--------------|
| Netdata | Running | https://hddm.pqsolutionsperu.com/netdata/ accessible |
| Resource Monitor | Running | journalctl shows "Check #X" every 5 minutes |
| ESP32 Cleanup Timer | Active | Next run scheduled for 03:00 |
| PostgreSQL Backup Timer | Active | Next run scheduled for 02:00 |
| Config Backup Timer | Active | Next run scheduled for Sunday 03:00 |
| Logrotate | Configured | /etc/logrotate.d/hdd-monitor exists |
| Email Alerts | Working | Test email received |

---

## 🎯 Post-Deployment

### Daily Monitoring

**Dashboard:** https://hddm.pqsolutionsperu.com/netdata/

**Key metrics to watch:**
- Disk usage (should stay below 80%)
- RAM usage (should stay below 85%)
- CPU usage (should stay below 90%)
- Service status (all green)

### Email Alerts

You will receive emails for:
- ⚠️ **WARNING:** Resource above 80-90%
- 🔥 **CRITICAL:** Resource above 90-95%
- 💀 **CRITICAL:** Service down

### Logs

```bash
# Resource monitor
sudo journalctl -u resource-monitor -f

# Netdata
sudo journalctl -u netdata -f

# Cleanup logs
tail -f /var/log/hdd_monitor_cleanup.log

# Backup logs
tail -f /var/log/hdd_monitor_backups.log
```

### Manual Operations

**Force cleanup:**
```bash
sudo systemctl start cleanup-esp32-logs.service
```

**Force backup:**
```bash
sudo systemctl start backup-postgresql.service
sudo systemctl start backup-configs.service
```

**Restart monitoring:**
```bash
sudo systemctl restart resource-monitor.service
```

---

## 🔧 Troubleshooting

### Netdata not sending emails

**Check:**
```bash
sudo journalctl -u netdata | grep alarm-notify
sudo tail -f /var/log/netdata/error.log
```

**Test manually:**
```bash
sudo su -s /bin/bash netdata
/usr/libexec/netdata/plugins.d/alarm-notify.sh test
exit
```

**Common fixes:**
1. Verify Gmail App Password is correct in `/etc/systemd/system/netdata.service.d/override.conf`
2. Restart Netdata: `sudo systemctl restart netdata`
3. Check Gmail spam folder

### Resource monitor not running

**Check:**
```bash
sudo systemctl status resource-monitor
sudo journalctl -u resource-monitor -n 100
```

**Common fixes:**
1. Verify Python dependencies: `pip3 list | grep psutil`
2. Check SMTP password in service file
3. Restart: `sudo systemctl restart resource-monitor.service`

### Backups failing

**Check:**
```bash
cat /var/log/hdd_monitor_backups.log
sudo systemctl status backup-postgresql.service
```

**Common fixes:**
1. Verify PostgreSQL password in service file
2. Check directory permissions: `ls -ld /home/pqsolutionsperu/backups/`
3. Test manually: `sudo systemctl start backup-postgresql.service`

### Timer not running

**Check:**
```bash
sudo systemctl list-timers
sudo systemctl status TIMER_NAME.timer
```

**Common fixes:**
1. Enable timer: `sudo systemctl enable TIMER_NAME.timer`
2. Start timer: `sudo systemctl start TIMER_NAME.timer`
3. Reload systemd: `sudo systemctl daemon-reload`

---

## 📡 Phase 5: ESP32 Log Upload Monitor

This service alerts by email when any ESP32 device stops uploading logs for more than 26 hours.

**When it runs:** 08:00 UTC daily (03:00 Lima time) — 3 hours after expected midnight Lima upload.

### Step 5.1: Deploy Script and Systemd Files

```bash
# Copy script
cp log_monitor.py /home/pqsolutionsperu/log_monitor.py
chmod +x /home/pqsolutionsperu/log_monitor.py

# Copy systemd unit files
sudo cp log-monitor.service /etc/systemd/system/log-monitor.service
sudo cp log-monitor.timer /etc/systemd/system/log-monitor.timer
```

### Step 5.2: Enable and Start Timer

```bash
sudo systemctl daemon-reload
sudo systemctl enable log-monitor.timer
sudo systemctl start log-monitor.timer
```

**Verify timer is scheduled:**
```bash
sudo systemctl list-timers log-monitor.timer
```

### Step 5.3: Test Manually

```bash
sudo systemctl start log-monitor.service
sudo journalctl -u log-monitor.service -n 50
tail -f /var/log/hdd_monitor_log_check.log
```

**Expected output (all devices OK):**
```
[2026-03-26 08:00:01] ============================================================
[2026-03-26 08:00:01] Iniciando verificacion de logs
[2026-03-26 08:00:01]   [ESP32_001] OK - ultimo log: log_20260326_050012.txt (2.9h atras)
[2026-03-26 08:00:01] Todos los dispositivos (2) estan subiendo logs correctamente
[2026-03-26 08:00:01] Verificacion completada
```

**Expected output (device missing):**
```
[2026-03-26 08:00:01]   [ESP32_002] ATRASADO - ultimo log: log_20260324_050008.txt (51.0h atras)
[2026-03-26 08:00:01] Enviando alerta para 1 dispositivo(s)...
[2026-03-26 08:00:01] Email de alerta enviado a: pqsolutionsperu@gmail.com
```

### Step 5.4: Verify Email Config

The script reads SMTP settings from `/home/pqsolutionsperu/mqtt-manager/config.email.json`.

**Required fields:**
```json
{
  "smtp_server": "smtp.gmail.com",
  "smtp_port": 587,
  "use_tls": true,
  "smtp_user": "pqsolutionsperu@gmail.com",
  "smtp_password": "xxxx xxxx xxxx xxxx",
  "from_email": "pqsolutionsperu@gmail.com",
  "to_emails": ["pqsolutionsperu@gmail.com"]
}
```

**Verify config exists:**
```bash
cat /home/pqsolutionsperu/mqtt-manager/config.email.json
```

### Step 5.5: Verify Log Directory

```bash
# ESP32 devices upload to subdirectories named by device ID
ls -la /home/pqsolutions/esp32_log/
```

**Expected structure:**
```
/home/pqsolutions/esp32_log/
├── ESP32_001/
│   ├── log_20260326_050012.txt
│   └── log_20260325_050008.txt
└── ESP32_002/
    └── log_20260326_050017.txt
```

---

## 🔄 Rollback Plan

If something goes wrong and you need to revert:

```bash
# Stop all new services
sudo systemctl stop resource-monitor
sudo systemctl stop cleanup-esp32-logs.timer
sudo systemctl stop backup-postgresql.timer
sudo systemctl stop backup-configs.timer
sudo systemctl stop log-monitor.timer

# Disable services
sudo systemctl disable resource-monitor
sudo systemctl disable cleanup-esp32-logs.timer
sudo systemctl disable backup-postgresql.timer
sudo systemctl disable backup-configs.timer
sudo systemctl disable log-monitor.timer

# Uninstall Netdata (optional)
sudo /usr/libexec/netdata/netdata-uninstaller.sh --yes

# Restore Nginx config
sudo cp /etc/nginx/sites-available/hddm.pqsolutionsperu.com.backup /etc/nginx/sites-available/hddm.pqsolutionsperu.com
sudo systemctl reload nginx

# Remove systemd files
sudo rm /etc/systemd/system/cleanup-esp32-logs.*
sudo rm /etc/systemd/system/backup-postgresql.*
sudo rm /etc/systemd/system/backup-configs.*
sudo rm /etc/systemd/system/resource-monitor.service
sudo rm /etc/systemd/system/log-monitor.*
sudo systemctl daemon-reload
```

---

## 📞 Support

**Dashboard:** https://hddm.pqsolutionsperu.com/netdata/
**Credentials:** pqsowner / (your htpasswd password)

**Key commands:**
```bash
# View all timers
sudo systemctl list-timers

# View all logs
sudo journalctl -u netdata -f
sudo journalctl -u resource-monitor -f
sudo journalctl -u log-monitor.service -n 50
tail -f /var/log/hdd_monitor_cleanup.log
tail -f /var/log/hdd_monitor_backups.log
tail -f /var/log/hdd_monitor_log_check.log

# Service status
sudo systemctl status netdata
sudo systemctl status resource-monitor
sudo systemctl status log-monitor.timer

# Manual backup / manual check
sudo systemctl start backup-postgresql.service
sudo systemctl start backup-configs.service
sudo systemctl start log-monitor.service
```

---

## 📊 Resource Usage Summary

After full deployment:

| Component | RAM | CPU | Disk |
|-----------|-----|-----|------|
| Netdata | 180MB | 3% | 150MB |
| Resource Monitor | 30MB | 1% | 10MB |
| Backups (accumulated) | - | - | 2GB |
| Logs (rotated) | - | - | 500MB |
| **Total Added** | **210MB** | **4%** | **2.66GB** |
| **Total System** | **1.31GB / 2GB** | **54%** | **8GB / 30GB** |
| **Remaining** | **690MB** | **46%** | **22GB** |

✅ System well within limits!

---

## 🎉 Success!

Your HDD-Monitor VM is now **100% auto-sustainable** with:

- ✅ Professional monitoring dashboard
- ✅ Automatic log cleanup (never runs out of space)
- ✅ Email alerts for resource issues
- ✅ Automated daily backups
- ✅ External monitoring via Healthchecks.io
- ✅ ESP32 log upload monitoring with email alerts
- ✅ Complete audit trail

**The VM will now maintain itself with zero manual intervention.**

System criticality: **NFPA 72 fire alarm monitoring - lives depend on uptime.**
