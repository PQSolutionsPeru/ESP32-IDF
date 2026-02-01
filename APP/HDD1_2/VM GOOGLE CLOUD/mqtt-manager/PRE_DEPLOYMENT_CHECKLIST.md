# Pre-Deployment Checklist - HDD Monitor Dashboard

**Version**: 2.0.0
**Date**: February 1, 2026
**Target**: Production VM (34.63.146.196)

## ✅ Local Testing Results

### Code Quality
- [x] Python syntax validation passed (0 errors)
- [x] All 16 required files present
- [x] All 6 templates valid
- [x] JavaScript files validated (3 files, 860 lines)
- [x] CSS files validated (508 lines)

### Code Analysis
- [x] 22/22 routes implemented correctly
- [x] 18/18 routes properly protected with @login_required
- [x] 5/5 security headers configured
- [x] 7/7 Firestore methods implemented
- [x] 6/6 cached methods configured
- [x] All templates extend base.html correctly
- [x] All JavaScript endpoints match backend APIs
- [x] Responsive design (320px, 768px, 1024px breakpoints)

### Security Audit
- [x] **PASSED** - No high/medium risk vulnerabilities
- [x] Authentication implemented correctly
- [x] CSRF protection enabled
- [x] XSS prevention in place
- [x] Security headers configured
- [x] Input validation implemented
- [x] Error handling secure
- [x] NFPA 72 compliance maintained

## 📋 Pre-Deployment Checklist

### 1. Development Machine Preparation
- [x] All code implemented and tested
- [x] Documentation complete (README, DEPLOYMENT, SECURITY_AUDIT)
- [x] Test scripts created (test_local.py, test_code_analysis.py)
- [ ] Create deployment package

```bash
cd "/mnt/e/PQSolutions/HDD-Monitor/ESP32-IDF/ESP32-IDF/APP/HDD1_2/VM GOOGLE CLOUD/mqtt-manager/"
tar -czf mqtt-manager-v2.0.0.tar.gz \
  modules/ templates/ static/ app.py requirements.txt \
  README.md DEPLOYMENT.md SECURITY_AUDIT.md
```

### 2. Backup Current Production
- [ ] SSH to VM
- [ ] Stop mqtt-manager service
- [ ] Create backup with timestamp
- [ ] Verify backup file created

```bash
ssh pqsolutionsperu@34.63.146.196
sudo systemctl stop mqtt-manager
cd /home/pqsolutions/mqtt-manager/
tar -czf /home/pqsolutions/backups/mqtt-manager-backup-$(date +%Y%m%d-%H%M%S).tar.gz .
ls -lh /home/pqsolutions/backups/
```

### 3. Upload New Version
- [ ] Upload deployment package to VM
- [ ] Verify file integrity

```bash
# On local machine
scp mqtt-manager-v2.0.0.tar.gz pqsolutionsperu@34.63.146.196:/tmp/

# On VM
ssh pqsolutionsperu@34.63.146.196
ls -lh /tmp/mqtt-manager-v2.0.0.tar.gz
md5sum /tmp/mqtt-manager-v2.0.0.tar.gz
```

### 4. Environment Verification (VM)
- [ ] Verify Python version (3.8+)
- [ ] Verify Firestore service account exists
- [ ] Verify environment variables set
- [ ] Verify Nginx configuration

```bash
python3 --version
ls -l /home/pqsolutionsperu/vm-service-key.json
echo $FLASK_SECRET_KEY
echo $ADMIN_PASSWORD_HASH
echo $GOOGLE_APPLICATION_CREDENTIALS
sudo nginx -t
```

### 5. Deploy New Version
- [ ] Extract files to application directory
- [ ] Install/update dependencies
- [ ] Verify file permissions
- [ ] Update systemd service (if needed)

```bash
cd /home/pqsolutions/mqtt-manager/
tar -xzf /tmp/mqtt-manager-v2.0.0.tar.gz
pip3 install -r requirements.txt
ls -la modules/ templates/ static/
sudo nano /etc/systemd/system/mqtt-manager.service  # Add GOOGLE_APPLICATION_CREDENTIALS
sudo systemctl daemon-reload
```

### 6. Service Configuration
- [ ] Verify systemd service file
- [ ] Check environment variables in service
- [ ] Verify working directory

Expected `/etc/systemd/system/mqtt-manager.service`:
```ini
[Unit]
Description=MQTT Manager Dashboard
After=network.target

[Service]
Type=simple
User=pqsolutions
WorkingDirectory=/home/pqsolutions/mqtt-manager
Environment="FLASK_SECRET_KEY=<your-secret-key>"
Environment="ADMIN_PASSWORD_HASH=<your-hash>"
Environment="GOOGLE_APPLICATION_CREDENTIALS=/home/pqsolutionsperu/vm-service-key.json"
ExecStart=/usr/bin/gunicorn -w 2 -b 127.0.0.1:5000 app:app
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

### 7. Start Service
- [ ] Start mqtt-manager service
- [ ] Check service status
- [ ] Monitor logs for errors

```bash
sudo systemctl start mqtt-manager
sudo systemctl status mqtt-manager
sudo journalctl -u mqtt-manager -f --lines=50
```

### 8. Initial Verification (VM)
- [ ] Health endpoint responds
- [ ] Service running without errors
- [ ] Firestore connection successful

```bash
# Wait 10 seconds for startup
sleep 10

# Test health endpoint
curl http://127.0.0.1:5000/api/health

# Check for errors in logs
sudo journalctl -u mqtt-manager --since "5 minutes ago" | grep -i error
```

### 9. Web Interface Testing
- [ ] Login page loads: https://hddm.pqsolutionsperu.com/login
- [ ] Login with valid credentials works
- [ ] Dashboard page loads: https://hddm.pqsolutionsperu.com/dashboard
- [ ] Navigation sidebar displays correctly
- [ ] All 6 menu items accessible

### 10. Feature Testing (Dashboard)
- [ ] Dashboard metrics display
- [ ] Auto-refresh works (30s)
- [ ] Quick action buttons work
- [ ] No JavaScript errors in console

### 11. Feature Testing (VM Monitoring)
- [ ] Netdata iframe loads
- [ ] Real-time metrics display
- [ ] "Open in New Tab" button works
- [ ] No console errors

### 12. Feature Testing (ESP32 Devices)
- [ ] Device list loads
- [ ] Status badges show correct colors
- [ ] Filter by status works
- [ ] Device detail modal opens
- [ ] Auto-refresh working

### 13. Feature Testing (Clients & Panels)
- [ ] Client list loads
- [ ] Accordion expand/collapse works
- [ ] Panel cards display
- [ ] Relay grid modal opens
- [ ] 6 relays displayed correctly

### 14. Feature Testing (Events)
- [ ] Client dropdown populates
- [ ] Events load for selected client
- [ ] Status filter works
- [ ] Event detail modal opens
- [ ] Date formatting correct

### 15. Feature Testing (MQTT Config)
- [ ] MQTT config page loads
- [ ] Existing functionality works
- [ ] No regressions detected
- [ ] Can add/delete MQTT users

### 16. Responsive Design Testing
- [ ] Desktop (1920px) - all features work
- [ ] Tablet (768px) - sidebar collapses, all features work
- [ ] Mobile (375px) - hamburger menu, all features work

### 17. Performance Verification
- [ ] Memory usage <200MB
- [ ] Response time <2s for all pages
- [ ] No memory leaks after 10 minutes
- [ ] CPU usage <50%

```bash
# Check memory and CPU
ps aux | grep gunicorn
top -bn1 | grep gunicorn

# Monitor for 10 minutes
watch -n 30 'ps aux | grep gunicorn'
```

### 18. Security Verification
- [ ] HTTPS enforced
- [ ] Session cookies secure
- [ ] CSRF tokens working
- [ ] Unauthorized access blocked
- [ ] Security headers present

```bash
# Check security headers
curl -I https://hddm.pqsolutionsperu.com/dashboard

# Test unauthorized access
curl -I https://hddm.pqsolutionsperu.com/api/dashboard/metrics
```

### 19. Log Monitoring
- [ ] No errors in application logs
- [ ] No warnings in Nginx logs
- [ ] Firestore connections successful
- [ ] No authentication failures

```bash
# Application logs
sudo journalctl -u mqtt-manager --since "30 minutes ago"

# Nginx logs
sudo tail -f /var/log/nginx/error.log
```

### 20. Final Verification
- [ ] All features working correctly
- [ ] No errors or warnings
- [ ] Performance acceptable
- [ ] Security verified
- [ ] NFPA 72 compliance maintained

## 🚨 Rollback Criteria

**Rollback immediately if:**
- Service fails to start
- Critical errors in logs
- Firestore connection fails
- Authentication broken
- Memory usage >500MB
- Response time >5s
- Security vulnerability discovered

**Rollback Procedure:**
```bash
sudo systemctl stop mqtt-manager
cd /home/pqsolutions/mqtt-manager/
rm -rf *
tar -xzf /home/pqsolutions/backups/mqtt-manager-backup-TIMESTAMP.tar.gz
sudo systemctl start mqtt-manager
sudo journalctl -u mqtt-manager -f
```

## 📊 Success Metrics

**Deployment is successful when:**
- ✅ Service running stable for 30+ minutes
- ✅ All 6 sections accessible and functional
- ✅ Memory usage <200MB
- ✅ Response time <2s
- ✅ No errors in logs
- ✅ User can login and navigate all pages
- ✅ Existing MQTT functionality preserved
- ✅ NFPA 72 compliance maintained

## 📞 Support Contacts

**Technical Issues:**
- Logs: `sudo journalctl -u mqtt-manager -f`
- Service: `sudo systemctl status mqtt-manager`
- Nginx: `sudo nginx -t`

**Emergency Rollback:**
- See "Rollback Procedure" above
- Contact system administrator

## ✅ Final Sign-Off

- [ ] All checklist items completed
- [ ] No critical issues found
- [ ] Performance acceptable
- [ ] Security verified
- [ ] Documentation updated
- [ ] Deployment approved

**Deployed By**: _________________
**Date**: _________________
**Time**: _________________
**Status**: _________________

---

**Next Steps After Deployment:**
1. Monitor for 24 hours
2. Collect user feedback
3. Document any issues
4. Plan for next iteration
