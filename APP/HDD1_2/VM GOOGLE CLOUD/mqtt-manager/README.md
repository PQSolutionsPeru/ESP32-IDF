# HDD Monitor Unified Dashboard

A comprehensive web-based monitoring and management system for NFPA 72 compliant fire alarm systems with ESP32-based relay panels.

## Features

### 📊 Unified Dashboard
- Real-time system metrics overview
- ESP32 device status monitoring
- Panel health indicators
- MQTT broker status
- Auto-refresh with intelligent caching

### 💻 VM Monitoring
- Embedded Netdata for system metrics
- CPU, RAM, disk, network monitoring
- Real-time performance graphs

### 📡 ESP32 Device Management
- Live device status tracking
- Connection monitoring
- Firmware version tracking
- Device detail views
- Status filtering

### 🏢 Client & Panel Management
- Hierarchical client/panel view
- 6-relay panel monitoring
- Visual status indicators
- Panel configuration overview

### 📅 Event Management
- Scheduled event tracking
- Event status filtering (PROGRAMADO, ACEPTADO, FINALIZADO)
- Contact information management
- Event detail views

### ⚙️ MQTT Configuration
- ESP32 user management
- Mosquitto broker integration
- Secure credential handling

## Technology Stack

- **Backend**: Flask 3.0.0
- **Database**: Google Cloud Firestore
- **Real-time**: AJAX polling (30s intervals)
- **Caching**: In-memory with TTL
- **Authentication**: Session-based with Flask-WTF
- **Monitoring**: Netdata integration

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                  Web Dashboard                       │
│              (Flask + Firestore Client)              │
├─────────────────────────────────────────────────────┤
│  Dashboard  │  VM Mon  │  ESP32  │  Panels  │ Events│
├─────────────────────────────────────────────────────┤
│              Cache Layer (TTL: 10-60s)              │
├─────────────────────────────────────────────────────┤
│            Firestore Client (Singleton)             │
└─────────────────────────────────────────────────────┘
                           ↓
                    Google Firestore
                           ↓
         ┌─────────────────┴─────────────────┐
         ↓                                     ↓
   MQTT Broker                          ESP32 Devices
   (Mosquitto)                          (Fire Panels)
```

## Security Features

- ✅ Session-based authentication
- ✅ CSRF protection (Flask-WTF)
- ✅ Security headers (CSP, X-Frame-Options)
- ✅ Input validation and sanitization
- ✅ XSS prevention (Jinja2 auto-escaping)
- ✅ HTTPS enforcement (Nginx)

## Installation

### Requirements
- Python 3.8+
- Google Cloud service account with Firestore access
- Nginx (for production)
- Gunicorn (for production)

### Dependencies
```bash
pip3 install -r requirements.txt
```

### Environment Variables
```bash
export FLASK_SECRET_KEY="your-secret-key"
export ADMIN_PASSWORD_HASH="bcrypt-hash-of-password"
export GOOGLE_APPLICATION_CREDENTIALS="/path/to/service-account.json"
```

## Usage

### Development
```bash
python3 app.py
# Visit http://127.0.0.1:5000
```

### Production (with Gunicorn)
```bash
gunicorn -w 2 -b 127.0.0.1:5000 app:app
```

### Systemd Service
```ini
[Unit]
Description=MQTT Manager Dashboard
After=network.target

[Service]
Type=simple
User=pqsolutions
WorkingDirectory=/home/pqsolutions/mqtt-manager
Environment="FLASK_SECRET_KEY=your-secret-key"
Environment="ADMIN_PASSWORD_HASH=your-hash"
Environment="GOOGLE_APPLICATION_CREDENTIALS=/home/pqsolutionsperu/vm-service-key.json"
ExecStart=/usr/bin/gunicorn -w 2 -b 127.0.0.1:5000 app:app
Restart=always

[Install]
WantedBy=multi-user.target
```

## API Endpoints

### Authentication
- `POST /api/auth/login` - User login
- `GET /api/auth/check` - Check auth status

### Dashboard
- `GET /api/dashboard/metrics` - Overview metrics

### ESP32 Devices
- `GET /api/esp32/devices` - List all devices
- `GET /api/esp32/devices/<id>` - Device details

### Clients & Panels
- `GET /api/clients` - List all clients
- `GET /api/clients/<id>/panels` - Client panels
- `GET /api/panels/<client_id>/<panel_id>/relays` - Panel relays

### Events
- `GET /api/events/<client_id>` - Client events (with status filter)

### MQTT Management
- `GET /api/mqtt/users` - List MQTT users
- `POST /api/mqtt/users` - Create MQTT user
- `DELETE /api/mqtt/users/<username>` - Delete MQTT user
- `GET /api/mqtt/status` - Broker status

## Performance

- **Response Time**: <2 seconds
- **Memory Usage**: ~160 MB
- **Cache Hit Rate**: >50% (after warmup)
- **Auto-refresh**: 30 seconds
- **Concurrent Users**: 10+ (with 2 Gunicorn workers)

## NFPA 72 Compliance

This dashboard is a **monitoring and management tool** and does NOT affect the critical alarm notification path:

```
ESP32 Device → MQTT Broker → firestore_handler.py → FCM Push Notification
                                                    (Real-time, <10s)
```

The dashboard provides visibility and management capabilities without interfering with life-safety notifications.

## Browser Support

- Chrome/Edge 90+
- Firefox 88+
- Safari 14+
- Mobile responsive (320px+)

## Contributing

1. Test all changes locally
2. Follow existing code style
3. Update documentation
4. Create deployment backup before production changes

## Troubleshooting

### Firestore Connection Issues
```bash
# Verify service account
ls -l $GOOGLE_APPLICATION_CREDENTIALS
# Check permissions
chmod 600 /path/to/service-account.json
```

### Module Import Errors
```bash
# Reinstall dependencies
pip3 install --upgrade -r requirements.txt
```

### Cache Issues
```bash
# Clear cache (restart service)
sudo systemctl restart mqtt-manager
```

## License

Proprietary - PQ Solutions Peru
All rights reserved.

## Support

- Email: support@pqsolutionsperu.com
- Website: https://pqsolutionsperu.com

---

**Version**: 2.0.0
**Last Updated**: February 1, 2026
**Status**: Production Ready
