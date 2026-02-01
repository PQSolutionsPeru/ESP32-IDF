#!/bin/bash
# Health Monitoring System Deployment Script
# Run this on the VM after pulling from Git

set -e  # Exit on error

echo "=================================================="
echo "ESP32 Health Monitoring System - Deployment"
echo "=================================================="
echo ""

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Check we're in the right directory
if [ ! -f "app.py" ]; then
    echo -e "${RED}Error: Run this script from /home/pqsolutions/mqtt-manager${NC}"
    exit 1
fi

echo -e "${YELLOW}Step 1: Create email configuration${NC}"
if [ ! -f "config.email.json" ]; then
    if [ -f "config.email.example.json" ]; then
        cp config.email.example.json config.email.json
        chmod 600 config.email.json
        echo -e "${GREEN}✓ config.email.json created${NC}"
        echo ""
        echo -e "${YELLOW}⚠️  IMPORTANT: Edit config.email.json and set your Gmail App Password${NC}"
        echo "   1. Go to: https://myaccount.google.com/apppasswords"
        echo "   2. Create password for 'HDD-Monitor Health'"
        echo "   3. Edit: nano config.email.json"
        echo "   4. Replace YOUR_APP_PASSWORD_HERE with your password"
        echo ""
        read -p "Press Enter when you've updated the password..."
    else
        echo -e "${RED}✗ config.email.example.json not found${NC}"
        exit 1
    fi
else
    echo -e "${GREEN}✓ config.email.json already exists${NC}"
fi

echo ""
echo -e "${YELLOW}Step 2: Update systemd service${NC}"
if ! grep -q "EMAIL_CONFIG_PATH" /etc/systemd/system/mqtt-manager.service; then
    echo "Adding EMAIL_CONFIG_PATH to systemd service..."
    sudo sed -i '/Environment="GOOGLE_APPLICATION_CREDENTIALS/a Environment="EMAIL_CONFIG_PATH=/home/pqsolutions/mqtt-manager/config.email.json"' /etc/systemd/system/mqtt-manager.service
    echo -e "${GREEN}✓ Systemd service updated${NC}"
else
    echo -e "${GREEN}✓ Systemd service already configured${NC}"
fi

echo ""
echo -e "${YELLOW}Step 3: Backup current app.py${NC}"
BACKUP_FILE="app.py.backup.$(date +%Y%m%d_%H%M%S)"
cp app.py "$BACKUP_FILE"
echo -e "${GREEN}✓ Backup created: $BACKUP_FILE${NC}"

echo ""
echo -e "${YELLOW}Step 4: Verify Python modules${NC}"
MODULES_OK=true
for module in email_alerter esp32_health_monitor health_system; do
    if [ -f "modules/${module}.py" ]; then
        echo -e "${GREEN}✓ modules/${module}.py${NC}"
    else
        echo -e "${RED}✗ modules/${module}.py NOT FOUND${NC}"
        MODULES_OK=false
    fi
done

if [ "$MODULES_OK" = false ]; then
    echo -e "${RED}Error: Missing required modules${NC}"
    exit 1
fi

echo ""
echo -e "${YELLOW}Step 5: Verify templates${NC}"
if [ -f "templates/health.html" ]; then
    echo -e "${GREEN}✓ templates/health.html${NC}"
else
    echo -e "${RED}✗ templates/health.html NOT FOUND${NC}"
    exit 1
fi

echo ""
echo -e "${YELLOW}Step 6: Restart services${NC}"
echo "Stopping mqtt-manager..."
sudo systemctl stop mqtt-manager

echo "Reloading systemd configuration..."
sudo systemctl daemon-reload

echo "Starting mqtt-manager..."
sudo systemctl start mqtt-manager

echo "Checking service status..."
sleep 2
if sudo systemctl is-active --quiet mqtt-manager; then
    echo -e "${GREEN}✓ mqtt-manager is running${NC}"
else
    echo -e "${RED}✗ mqtt-manager failed to start${NC}"
    echo "Check logs with: sudo journalctl -u mqtt-manager -n 50"
    exit 1
fi

echo ""
echo -e "${YELLOW}Step 7: Verify logs${NC}"
echo "Checking for health system initialization..."
sleep 3
if sudo journalctl -u mqtt-manager -n 20 | grep -q "Health monitoring system started"; then
    echo -e "${GREEN}✓ Health monitoring system started successfully${NC}"
else
    echo -e "${YELLOW}⚠️  Health system message not found in logs yet${NC}"
    echo "   Check logs manually: sudo journalctl -u mqtt-manager -f"
fi

echo ""
echo -e "${YELLOW}Step 8: Run verification tests (optional)${NC}"
read -p "Run test_health_system.py? (y/n): " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    python3 test_health_system.py
fi

echo ""
echo "=================================================="
echo -e "${GREEN}Deployment Complete!${NC}"
echo "=================================================="
echo ""
echo "Next steps:"
echo "  1. Access dashboard: https://hddm.pqsolutionsperu.com/health"
echo "  2. Flash ESP32 with updated firmware"
echo "  3. Monitor logs: sudo journalctl -u mqtt-manager -f"
echo ""
echo "Troubleshooting:"
echo "  - View logs: sudo journalctl -u mqtt-manager -n 50"
echo "  - Test email: python3 -c 'from modules.email_alerter import *; import json; c=EmailConfig(**json.load(open(\"config.email.json\"))); EmailAlerter(c).send_test_email()'"
echo "  - Restart service: sudo systemctl restart mqtt-manager"
echo ""
