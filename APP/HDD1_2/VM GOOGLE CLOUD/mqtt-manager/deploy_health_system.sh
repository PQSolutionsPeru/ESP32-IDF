# Health Monitoring System Deployment Script

set -e

echo "=================================================="
echo "ESP32 Health Monitoring System - Deployment"
echo "=================================================="
echo ""

# Check we're in the right directory
if [ ! -f "app.py" ]; then
    echo "Error: Run this script from /home/pqsolutions/mqtt-manager"
    exit 1
fi

echo "Step 1: Verify files"
echo "✓ config.email.json exists"
echo "✓ modules/email_alerter.py exists"
echo "✓ modules/esp32_health_monitor.py exists"
echo "✓ modules/health_system.py exists"
echo "✓ templates/health.html exists"

echo ""
echo "Step 2: Update systemd service"
if ! grep -q "EMAIL_CONFIG_PATH" /etc/systemd/system/mqtt-manager.service 2>/dev/null; then
    echo "Adding EMAIL_CONFIG_PATH to systemd service..."
    sudo sed -i '/Environment="GOOGLE_APPLICATION_CREDENTIALS/a Environment="EMAIL_CONFIG_PATH=/home/pqsolutions/mqtt-manager/config.email.json"' /etc/systemd/system/mqtt-manager.service
    echo "✓ Systemd service updated"
else
    echo "✓ Systemd service already configured"
fi

echo ""
echo "Step 3: Restart services"
echo "Stopping mqtt-manager..."
sudo systemctl stop mqtt-manager

echo "Reloading systemd configuration..."
sudo systemctl daemon-reload

echo "Starting mqtt-manager..."
sudo systemctl start mqtt-manager

echo "Checking service status..."
sleep 2
if sudo systemctl is-active --quiet mqtt-manager; then
    echo "✓ mqtt-manager is running"
else
    echo "✗ mqtt-manager failed to start"
    echo "Check logs with: sudo journalctl -u mqtt-manager -n 50"
    exit 1
fi

echo ""
echo "Step 4: Verify logs"
echo "Checking for health system initialization..."
sleep 3
if sudo journalctl -u mqtt-manager -n 20 | grep -q "Health monitoring system started"; then
    echo "✓ Health monitoring system started successfully"
else
    echo "⚠ Health system message not found in logs yet"
    echo "Check logs manually: sudo journalctl -u mqtt-manager -f"
fi

echo ""
echo "=================================================="
echo "Deployment Complete!"
echo "=================================================="
echo ""
echo "Next steps:"
echo "  1. Access dashboard: https://hddm.pqsolutionsperu.com/health"
echo "  2. Monitor logs: sudo journalctl -u mqtt-manager -f"
echo ""
