# HDD-Monitor VM Auto-Sustainability System

## 🎯 Overview

Complete auto-sustainability solution for the HDD-Monitor VM with professional monitoring, automated cleanup, resource alerts, and backups.

**System Status:** Ready for deployment ✅
**Target VM:** hddm.pqsolutionsperu.com (34.63.146.196)
**Criticality:** NFPA 72/UL 864 fire alarm monitoring system

---

## 📦 What's Included

### 1. Monitoring (Netdata)
- **Dashboard:** https://hddm.pqsolutionsperu.com/netdata/
- Real-time metrics (CPU, RAM, disk, network, services)
- Professional graphs and historical data
- Resource usage: 180MB RAM, 3% CPU, 150MB disk

### 2. Auto-Cleanup
- ESP32 logs: Keep only last 7 days
- System logs: Rotated daily, 30-day retention
- Backups: PostgreSQL 30 days, configs 90 days
- Runs automatically at 3 AM daily

### 3. Resource Monitoring
- Disk alerts: 80% warning, 90% critical
- RAM alerts: 85% warning, 95% critical
- CPU alerts: 90% warning, 95% critical
- Email alerts to pqsolutionsperu@gmail.com
- Healthchecks.io integration

### 4. Automated Backups
- PostgreSQL: Daily at 2 AM
- Configs: Weekly on Sundays at 3 AM
- Compressed, timestamped files
- Automatic cleanup of old backups

---

## 📁 Files Overview

### Installation & Configuration
| File | Purpose |
|------|---------|
| `install_netdata.sh` | Netdata installation script |
| `netdata.conf` | Netdata main configuration |
| `disk_space.conf` | Disk space alert rules |
| `ram.conf` | RAM usage alert rules |
| `cpu.conf` | CPU usage alert rules |
| `services.conf` | Service monitoring alert rules |
| `health_alarm_notify.conf` | Email notification config |
| `netdata.service.override.conf` | Netdata systemd environment variables |

### Auto-Cleanup
| File | Purpose |
|------|---------|
| `cleanup_esp32_logs.sh` | ESP32 log cleanup script |
| `cleanup-esp32-logs.service` | Systemd service for cleanup |
| `cleanup-esp32-logs.timer` | Daily timer (3 AM) |
| `hdd-monitor-logrotate` | Logrotate configuration |

### Resource Monitoring
| File | Purpose |
|------|---------|
| `resource_monitor.py` | Python resource monitor |
| `resource-monitor.service` | Systemd service for monitor |

### Backups
| File | Purpose |
|------|---------|
| `backup_postgresql.sh` | PostgreSQL backup script |
| `backup-postgresql.service` | Systemd service for DB backup |
| `backup-postgresql.timer` | Daily timer (2 AM) |
| `backup_configs.sh` | Configuration backup script |
| `backup-configs.service` | Systemd service for config backup |
| `backup-configs.timer` | Weekly timer (Sunday 3 AM) |

### Documentation
| File | Purpose |
|------|---------|
| `DEPLOYMENT_INSTRUCTIONS.md` | Complete deployment guide |
| `nginx_netdata_config.txt` | Nginx configuration snippet |
| `README.md` | This file |

---

## 🚀 Quick Start

### Prerequisites

1. Gmail App Password: https://myaccount.google.com/apppasswords
2. PostgreSQL password for `hdd_monitor_user`
3. SSH access to VM with sudo privileges

### Deployment Steps

1. **Upload all files to VM:**
   ```bash
   scp -r * pqsolutionsperu@hddm.pqsolutionsperu.com:/home/pqsolutionsperu/
   ```

2. **Follow deployment guide:**
   ```bash
   cat DEPLOYMENT_INSTRUCTIONS.md
   ```

3. **Estimated time:** 2-3 hours

---

## 📊 System Architecture

```
┌─────────────────────────────────────────────────────────┐
│                  HDD-Monitor VM                         │
│              (hddm.pqsolutionsperu.com)                 │
└─────────────────────────────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        │                   │                   │
    ┌───▼───┐          ┌────▼────┐        ┌────▼────┐
    │Netdata│          │Resource │        │ Backups │
    │Monitor│          │ Monitor │        │ System  │
    └───┬───┘          └────┬────┘        └────┬────┘
        │                   │                   │
        ├─► Dashboard       ├─► Email Alerts   ├─► PostgreSQL
        ├─► Metrics         ├─► Healthchecks   └─► Configs
        └─► Alerts          └─► Logs

    ┌──────────────────────────────────────────────┐
    │         Auto-Cleanup System                  │
    ├──────────────────────────────────────────────┤
    │ • ESP32 logs (7 days)                        │
    │ • System logs (30 days)                      │
    │ • Old backups (30-90 days)                   │
    └──────────────────────────────────────────────┘
```

---

## 🔔 Alert Thresholds

| Resource | Warning | Critical | Action |
|----------|---------|----------|--------|
| **Disk** | 80% | 90% | Auto-cleanup triggered |
| **RAM** | 85% | 95% | Service restart recommended |
| **CPU** | 90% | 95% | Process investigation needed |
| **Services** | - | Down | Immediate notification |

---

## 📅 Scheduled Tasks

| Task | Schedule | Purpose |
|------|----------|---------|
| ESP32 Cleanup | Daily 3:00 AM | Delete logs >7 days old |
| PostgreSQL Backup | Daily 2:00 AM | Database backup |
| Config Backup | Sunday 3:00 AM | System config backup |
| Log Rotation | Daily | Rotate system logs |
| Resource Check | Every 5 minutes | Monitor CPU/RAM/disk |
| Netdata Health | Every 1 minute | Check alert conditions |

---

## 📧 Email Notifications

Alerts sent to: **pqsolutionsperu@gmail.com**

**Alert types:**
- ⚠️ WARNING: Resource approaching limits (80-90%)
- 🔥 CRITICAL: Resource at critical level (>90%)
- 💀 CRITICAL: Service down
- ✅ CLEAR: Issue resolved

**Email includes:**
- Current resource usage
- Recommended actions
- Links to dashboard
- Command examples

---

## 🔐 Security

### Authentication
- Netdata: Basic auth (pqsowner)
- Gmail: App Password (not regular password)
- SSH: Key-based authentication

### Hardening
- Netdata: Localhost binding only (proxied via Nginx)
- Services: NoNewPrivileges, PrivateTmp
- Resource limits: MemoryMax, CPUQuota
- Protected paths: ReadWritePaths restrictions

---

## 📈 Resource Usage

### Before Deployment
| Resource | Usage | Available |
|----------|-------|-----------|
| RAM | 1.1GB | 2GB (55%) |
| CPU | 50% | 2 vCPUs |
| Disk | 5.4GB | 30GB (18%) |

### After Deployment
| Resource | Usage | Available |
|----------|-------|-----------|
| RAM | 1.31GB | 2GB (65%) |
| CPU | 54% | 2 vCPUs |
| Disk | 8.06GB | 30GB (27%) |

### Component Breakdown
| Component | RAM | CPU | Disk |
|-----------|-----|-----|------|
| Netdata | 180MB | 3% | 150MB |
| Resource Monitor | 30MB | 1% | 10MB |
| Backups (max) | - | - | 2GB |
| Logs (rotated) | - | - | 500MB |

✅ **All within safe limits with plenty of headroom**

---

## 🛠️ Maintenance

### Daily
- Check dashboard: https://hddm.pqsolutionsperu.com/netdata/
- Monitor email alerts

### Weekly
- Review backup logs: `cat /var/log/hdd_monitor_backups.log`
- Verify backups exist: `ls -lh /home/pqsolutionsperu/backups/`

### Monthly
- Review disk usage trends
- Check log file sizes
- Verify all timers active: `systemctl list-timers`

### Commands
```bash
# Check all services
sudo systemctl status netdata
sudo systemctl status resource-monitor

# View logs
sudo journalctl -u netdata -f
sudo journalctl -u resource-monitor -f
tail -f /var/log/hdd_monitor_cleanup.log
tail -f /var/log/hdd_monitor_backups.log

# View timers
sudo systemctl list-timers

# Manual backup
sudo systemctl start backup-postgresql.service
sudo systemctl start backup-configs.service

# Manual cleanup
sudo systemctl start cleanup-esp32-logs.service

# Check disk usage
df -h
du -sh /home/pqsolutions/esp32_log/
```

---

## 🐛 Troubleshooting

### Netdata Dashboard Not Accessible
1. Check Netdata running: `sudo systemctl status netdata`
2. Check Nginx config: `sudo nginx -t`
3. Verify password file: `ls -l /etc/nginx/.htpasswd_metrics`
4. Check logs: `sudo journalctl -u netdata -n 50`

### No Email Alerts
1. Verify Gmail App Password in `/etc/systemd/system/netdata.service.d/override.conf`
2. Test manually: `sudo su -s /bin/bash netdata; /usr/libexec/netdata/plugins.d/alarm-notify.sh test`
3. Check spam folder
4. Review logs: `sudo journalctl -u netdata | grep alarm-notify`

### Backups Failing
1. Check logs: `cat /var/log/hdd_monitor_backups.log`
2. Verify PostgreSQL password in service file
3. Check permissions: `ls -ld /home/pqsolutionsperu/backups/`
4. Test manually: `sudo systemctl start backup-postgresql.service`

### Resource Monitor Not Running
1. Check status: `sudo systemctl status resource-monitor`
2. View logs: `sudo journalctl -u resource-monitor -n 100`
3. Verify dependencies: `pip3 list | grep psutil`
4. Restart: `sudo systemctl restart resource-monitor.service`

---

## 📚 Additional Resources

### Netdata
- Documentation: https://learn.netdata.cloud/
- Health API: http://localhost:19999/api/v1/alarms
- Configuration: /etc/netdata/netdata.conf

### Healthchecks.io
- Dashboard: https://healthchecks.io/checks/
- API: https://healthchecks.io/docs/http_api/

### System Logs
- Netdata: `/var/log/netdata/`
- Cleanup: `/var/log/hdd_monitor_cleanup.log`
- Backups: `/var/log/hdd_monitor_backups.log`
- Resource Monitor: `/var/log/resource_monitor.log`

---

## 🎉 Success Criteria

After deployment, you should have:

- ✅ Dashboard accessible at https://hddm.pqsolutionsperu.com/netdata/
- ✅ Test email alert received
- ✅ All 3 timers active in `systemctl list-timers`
- ✅ Resource monitor logging every 5 minutes
- ✅ Initial backups created in `/home/pqsolutionsperu/backups/`
- ✅ Netdata showing real-time metrics
- ✅ All services status "active (running)"

---

## 📞 Support

**Project:** HDD-Monitor VM Auto-Sustainability
**System:** NFPA 72 Fire Alarm Monitoring
**Criticality:** LIVES DEPEND ON UPTIME

**Key Contacts:**
- Admin: pqsolutionsperu@gmail.com
- Dashboard: https://hddm.pqsolutionsperu.com/netdata/
- Domain: hddm.pqsolutionsperu.com
- IP: 34.63.146.196

---

## 📝 Version History

- **v1.0** (2026-01-30): Initial implementation
  - Netdata monitoring
  - Auto-cleanup system
  - Resource monitoring
  - Automated backups
  - Email alerts
  - Healthchecks.io integration

---

## ⚖️ License

Proprietary - PQ Solutions Peru
System for critical fire alarm monitoring - NFPA 72/UL 864 compliance
