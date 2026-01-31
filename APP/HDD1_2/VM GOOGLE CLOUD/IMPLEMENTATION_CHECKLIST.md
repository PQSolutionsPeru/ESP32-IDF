# HDD-Monitor VM Auto-Sustainability - Implementation Checklist

## 📋 Pre-Deployment Checklist

### Prerequisites

- [ ] SSH access to VM (pqsolutionsperu@hddm.pqsolutionsperu.com)
- [ ] Sudo privileges confirmed
- [ ] Gmail account: pqsolutionsperu@gmail.com accessible
- [ ] Gmail App Password created (https://myaccount.google.com/apppasswords)
- [ ] PostgreSQL credentials for `hdd_monitor_user` available
- [ ] All files uploaded to `/home/pqsolutionsperu/`
- [ ] Backup of current Nginx config created
- [ ] VM snapshot created (optional but recommended)

---

## Phase 1: Netdata Monitoring (30 min)

### 1.1 Installation
- [ ] Run `sudo bash install_netdata.sh`
- [ ] Verify: `sudo systemctl status netdata`
- [ ] Test: `curl http://localhost:19999`
- [ ] Expected: Netdata running on port 19999

### 1.2 Configuration
- [ ] Deploy `netdata.conf` to `/etc/netdata/netdata.conf`
- [ ] Set ownership: `sudo chown netdata:netdata /etc/netdata/netdata.conf`
- [ ] Restart: `sudo systemctl restart netdata`
- [ ] Verify: `sudo systemctl status netdata`

### 1.3 Health Alerts
- [ ] Create `/etc/netdata/health.d/` directory
- [ ] Deploy `disk_space.conf`
- [ ] Deploy `ram.conf`
- [ ] Deploy `cpu.conf`
- [ ] Deploy `services.conf`
- [ ] Set ownership: `sudo chown netdata:netdata /etc/netdata/health.d/*.conf`
- [ ] Verify: `ls -lh /etc/netdata/health.d/`

### 1.4 Email Configuration
- [ ] Gmail App Password obtained (16 characters)
- [ ] Deploy `health_alarm_notify.conf` to `/etc/netdata/`
- [ ] Set ownership: `sudo chown netdata:netdata /etc/netdata/health_alarm_notify.conf`
- [ ] Create `/etc/systemd/system/netdata.service.d/` directory
- [ ] Deploy `netdata.service.override.conf`
- [ ] Edit override file with actual Gmail App Password
- [ ] Run: `sudo systemctl daemon-reload`
- [ ] Restart: `sudo systemctl restart netdata`

### 1.5 Email Test
- [ ] Run: `sudo su -s /bin/bash netdata`
- [ ] Run: `/usr/libexec/netdata/plugins.d/alarm-notify.sh test`
- [ ] Exit netdata user: `exit`
- [ ] Check email inbox (may take 1-2 minutes)
- [ ] Email received at pqsolutionsperu@gmail.com ✅
- [ ] If no email, check: `sudo journalctl -u netdata | grep alarm-notify`

### 1.6 Nginx Configuration
- [ ] Backup existing config: `sudo cp /etc/nginx/sites-available/hddm.pqsolutionsperu.com{,.backup}`
- [ ] Edit config: `sudo nano /etc/nginx/sites-available/hddm.pqsolutionsperu.com`
- [ ] Add location block from `nginx_netdata_config.txt`
- [ ] Create password: `sudo htpasswd -c /etc/nginx/.htpasswd_metrics pqsowner`
- [ ] Test config: `sudo nginx -t`
- [ ] Reload: `sudo systemctl reload nginx`

### 1.7 Dashboard Verification
- [ ] Open: https://hddm.pqsolutionsperu.com/netdata/
- [ ] Login with credentials
- [ ] See real-time metrics (CPU, RAM, disk)
- [ ] Navigate through different sections
- [ ] Verify charts updating in real-time

**Phase 1 Complete:** ✅ Monitoring dashboard operational

---

## Phase 2: Auto-Cleanup (30 min)

### 2.1 ESP32 Cleanup Script
- [ ] Make executable: `chmod +x cleanup_esp32_logs.sh`
- [ ] Test run: `bash cleanup_esp32_logs.sh`
- [ ] Check log: `cat /var/log/hdd_monitor_cleanup.log`
- [ ] Verify log messages present

### 2.2 Systemd Service & Timer
- [ ] Deploy `cleanup-esp32-logs.service` to `/etc/systemd/system/`
- [ ] Deploy `cleanup-esp32-logs.timer` to `/etc/systemd/system/`
- [ ] Set permissions: `sudo chmod 644 /etc/systemd/system/cleanup-esp32-logs.*`
- [ ] Run: `sudo systemctl daemon-reload`
- [ ] Enable: `sudo systemctl enable cleanup-esp32-logs.timer`
- [ ] Start: `sudo systemctl start cleanup-esp32-logs.timer`
- [ ] Verify: `sudo systemctl status cleanup-esp32-logs.timer`
- [ ] Check schedule: `sudo systemctl list-timers | grep cleanup`

### 2.3 Logrotate
- [ ] Deploy `hdd-monitor-logrotate` to `/etc/logrotate.d/hdd-monitor`
- [ ] Set permissions: `sudo chmod 644 /etc/logrotate.d/hdd-monitor`
- [ ] Test: `sudo logrotate -d /etc/logrotate.d/hdd-monitor`
- [ ] Force rotation (test): `sudo logrotate -f /etc/logrotate.d/hdd-monitor`

**Phase 2 Complete:** ✅ Auto-cleanup configured

---

## Phase 3: Resource Monitoring (1 hour)

### 3.1 Python Dependencies
- [ ] Update packages: `sudo apt update`
- [ ] Install Python: `sudo apt install -y python3 python3-pip`
- [ ] Install psutil: `sudo pip3 install psutil`
- [ ] Install requests: `sudo pip3 install requests`
- [ ] Verify: `pip3 list | grep psutil`
- [ ] Verify: `pip3 list | grep requests`

### 3.2 Test Resource Monitor
- [ ] Make executable: `chmod +x resource_monitor.py`
- [ ] Test run: `python3 resource_monitor.py` (Ctrl+C after a few seconds)
- [ ] Verify output shows resource checks
- [ ] No errors in output

### 3.3 Systemd Service
- [ ] Deploy `resource-monitor.service` to `/etc/systemd/system/`
- [ ] Edit service: `sudo nano /etc/systemd/system/resource-monitor.service`
- [ ] Add Gmail App Password (same as Phase 1.4)
- [ ] Save and exit
- [ ] Run: `sudo systemctl daemon-reload`
- [ ] Enable: `sudo systemctl enable resource-monitor.service`
- [ ] Start: `sudo systemctl start resource-monitor.service`
- [ ] Verify: `sudo systemctl status resource-monitor.service`
- [ ] Check logs: `sudo journalctl -u resource-monitor -f` (Ctrl+C after seeing checks)

### 3.4 Healthchecks.io (Optional)
- [ ] Create account at https://healthchecks.io
- [ ] Create check: "HDD-Monitor Resource Monitor" (5 min period)
- [ ] Copy check URL
- [ ] Edit service file with URL
- [ ] Restart: `sudo systemctl restart resource-monitor.service`
- [ ] Verify ping received on Healthchecks.io dashboard

**Phase 3 Complete:** ✅ Resource monitoring active

---

## Phase 4: Automated Backups (1 hour)

### 4.1 PostgreSQL Backup
- [ ] Make executable: `chmod +x backup_postgresql.sh`
- [ ] Create directory: `mkdir -p /home/pqsolutionsperu/backups/postgresql`
- [ ] Deploy `backup-postgresql.service` to `/etc/systemd/system/`
- [ ] Deploy `backup-postgresql.timer` to `/etc/systemd/system/`
- [ ] Edit service: `sudo nano /etc/systemd/system/backup-postgresql.service`
- [ ] Add PostgreSQL password
- [ ] Add Healthchecks.io URL (optional)
- [ ] Save and exit
- [ ] Run: `sudo systemctl daemon-reload`
- [ ] Enable timer: `sudo systemctl enable backup-postgresql.timer`
- [ ] Start timer: `sudo systemctl start backup-postgresql.timer`
- [ ] Verify: `sudo systemctl list-timers | grep backup-postgresql`
- [ ] Test manually: `sudo systemctl start backup-postgresql.service`
- [ ] Check status: `sudo systemctl status backup-postgresql.service`
- [ ] Check log: `cat /var/log/hdd_monitor_backups.log`
- [ ] Verify backup: `ls -lh /home/pqsolutionsperu/backups/postgresql/`

### 4.2 Configuration Backup
- [ ] Make executable: `chmod +x backup_configs.sh`
- [ ] Create directory: `mkdir -p /home/pqsolutionsperu/backups/configs`
- [ ] Deploy `backup-configs.service` to `/etc/systemd/system/`
- [ ] Deploy `backup-configs.timer` to `/etc/systemd/system/`
- [ ] Run: `sudo systemctl daemon-reload`
- [ ] Enable timer: `sudo systemctl enable backup-configs.timer`
- [ ] Start timer: `sudo systemctl start backup-configs.timer`
- [ ] Verify: `sudo systemctl list-timers | grep backup-configs`
- [ ] Test manually: `sudo systemctl start backup-configs.service`
- [ ] Check status: `sudo systemctl status backup-configs.service`
- [ ] Check log: `cat /var/log/hdd_monitor_backups.log`
- [ ] Verify backup: `ls -lh /home/pqsolutionsperu/backups/configs/`

### 4.3 Healthchecks.io for Backups (Optional)
- [ ] Create check: "HDD-Monitor PostgreSQL Backup" (1 day period)
- [ ] Copy check URL
- [ ] Edit backup-postgresql.service with URL
- [ ] Restart timer: `sudo systemctl restart backup-postgresql.timer`

**Phase 4 Complete:** ✅ Automated backups operational

---

## Final Verification

### Service Status
- [ ] Netdata: `sudo systemctl status netdata` → active (running)
- [ ] Resource Monitor: `sudo systemctl status resource-monitor` → active (running)
- [ ] All timers active: `sudo systemctl list-timers`
  - [ ] cleanup-esp32-logs.timer → Next: Tomorrow 03:00
  - [ ] backup-postgresql.timer → Next: Tomorrow 02:00
  - [ ] backup-configs.timer → Next: Sunday 03:00

### Functionality Tests
- [ ] Dashboard accessible: https://hddm.pqsolutionsperu.com/netdata/
- [ ] Dashboard shows real-time metrics
- [ ] Email alert test passed
- [ ] Resource monitor logging every 5 minutes
- [ ] PostgreSQL backup created successfully
- [ ] Config backup created successfully
- [ ] Healthchecks.io receiving pings (if configured)

### Log Verification
- [ ] Netdata logs: `sudo journalctl -u netdata -n 50` (no errors)
- [ ] Resource monitor: `sudo journalctl -u resource-monitor -n 50` (shows checks)
- [ ] Cleanup log: `cat /var/log/hdd_monitor_cleanup.log` (shows cleanup run)
- [ ] Backup log: `cat /var/log/hdd_monitor_backups.log` (shows backups)

### Disk Space Check
- [ ] Overall: `df -h` → Root partition < 30% used
- [ ] ESP32 logs: `du -sh /home/pqsolutions/esp32_log/` (reasonable size)
- [ ] Backups: `du -sh /home/pqsolutionsperu/backups/` (< 2GB)

### Alert Configuration
- [ ] Netdata alerts configured: `curl http://localhost:19999/api/v1/alarms`
- [ ] Should show disk, RAM, CPU, service alerts
- [ ] Email recipient: pqsolutionsperu@gmail.com

---

## Post-Deployment Tasks

### Immediate (Day 1)
- [ ] Monitor dashboard for 1 hour to ensure metrics flowing
- [ ] Check email for any unexpected alerts
- [ ] Verify resource monitor logs updating every 5 minutes
- [ ] Review all service logs for errors

### First Week
- [ ] Day 2: Verify cleanup timer ran at 3 AM (check log)
- [ ] Day 2: Verify backup timer ran at 2 AM (check log)
- [ ] Day 3-7: Monitor dashboard daily
- [ ] Day 7: Verify no disk space issues

### First Month
- [ ] Week 2: Check backup directory sizes
- [ ] Week 3: Verify old backups being deleted (30 days for PostgreSQL)
- [ ] Week 4: Review alert history on Netdata
- [ ] Month end: Full system health review

---

## Rollback Checklist (If Needed)

### Stop Services
- [ ] `sudo systemctl stop resource-monitor`
- [ ] `sudo systemctl stop cleanup-esp32-logs.timer`
- [ ] `sudo systemctl stop backup-postgresql.timer`
- [ ] `sudo systemctl stop backup-configs.timer`

### Disable Services
- [ ] `sudo systemctl disable resource-monitor`
- [ ] `sudo systemctl disable cleanup-esp32-logs.timer`
- [ ] `sudo systemctl disable backup-postgresql.timer`
- [ ] `sudo systemctl disable backup-configs.timer`

### Remove Files
- [ ] Remove systemd files from `/etc/systemd/system/`
- [ ] `sudo systemctl daemon-reload`
- [ ] Restore Nginx config from backup
- [ ] `sudo systemctl reload nginx`

### Uninstall Netdata (Optional)
- [ ] `sudo /usr/libexec/netdata/netdata-uninstaller.sh --yes`

---

## Success Metrics

### System Health
- [ ] CPU usage < 60%
- [ ] RAM usage < 70%
- [ ] Disk usage < 30%
- [ ] All services running
- [ ] No critical alerts

### Monitoring
- [ ] Dashboard accessible 24/7
- [ ] Metrics updating in real-time
- [ ] Alerts configured correctly
- [ ] Email notifications working

### Automation
- [ ] Cleanup running daily
- [ ] Backups running on schedule
- [ ] Old files being deleted
- [ ] Logs rotating properly

### Documentation
- [ ] All passwords documented securely
- [ ] Dashboard credentials known
- [ ] Support contacts available
- [ ] Troubleshooting guide accessible

---

## Emergency Contacts

**Primary Admin:** pqsolutionsperu@gmail.com
**Dashboard:** https://hddm.pqsolutionsperu.com/netdata/
**VM IP:** 34.63.146.196
**Domain:** hddm.pqsolutionsperu.com

---

## Sign-Off

**Deployment Date:** _______________

**Deployed By:** _______________

**Verified By:** _______________

**Status:**
- [ ] All phases completed successfully
- [ ] All tests passed
- [ ] Documentation reviewed
- [ ] System operational

**Notes:**
```
[Add any deployment-specific notes here]
```

---

## 🎉 Deployment Complete!

Your HDD-Monitor VM is now 100% auto-sustainable with:
- ✅ Professional monitoring
- ✅ Automated cleanup
- ✅ Resource alerts
- ✅ Automated backups
- ✅ External monitoring

**The system will now maintain itself with zero manual intervention.**

**Critical System:** NFPA 72 fire alarm monitoring - lives depend on uptime ⚠️
