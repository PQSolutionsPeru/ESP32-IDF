#!/bin/bash
# Deployment Script for HDD Monitor Dashboard v2.0.0
# Usage: ./deploy.sh

set -e  # Exit on error

VM_USER="pqsolutionsperu"
VM_HOST="34.63.146.196"
VM_APP_DIR="/home/pqsolutions/mqtt-manager"
LOCAL_PACKAGE="mqtt-manager-v2.0.0.tar.gz"

echo "=========================================="
echo "HDD Monitor Dashboard - Deployment Script"
echo "Version: 2.0.0"
echo "=========================================="
echo ""

# Step 1: Verify local package exists
echo "[1/7] Verifying deployment package..."
if [ ! -f "$LOCAL_PACKAGE" ]; then
    echo "❌ Error: $LOCAL_PACKAGE not found!"
    exit 1
fi
echo "✓ Package found: $(ls -lh $LOCAL_PACKAGE | awk '{print $5}')"
echo ""

# Step 2: Upload package to VM
echo "[2/7] Uploading package to VM..."
scp "$LOCAL_PACKAGE" "${VM_USER}@${VM_HOST}:/tmp/"
echo "✓ Package uploaded successfully"
echo ""

# Step 3: Connect to VM and execute deployment
echo "[3/7] Connecting to VM and executing deployment..."
ssh "${VM_USER}@${VM_HOST}" << 'ENDSSH'
set -e

echo "[3.1] Creating backup..."
cd /home/pqsolutions/mqtt-manager/
BACKUP_FILE="/home/pqsolutions/backups/mqtt-manager-backup-$(date +%Y%m%d-%H%M%S).tar.gz"
mkdir -p /home/pqsolutions/backups/
tar -czf "$BACKUP_FILE" . 2>/dev/null || echo "Warning: Backup may be incomplete"
echo "✓ Backup created: $BACKUP_FILE"

echo "[3.2] Stopping service..."
sudo systemctl stop mqtt-manager
echo "✓ Service stopped"

echo "[3.3] Extracting new version..."
cd /home/pqsolutions/mqtt-manager/
tar -xzf /tmp/mqtt-manager-v2.0.0.tar.gz
echo "✓ Files extracted"

echo "[3.4] Verifying files..."
if [ ! -d "modules" ] || [ ! -d "templates" ] || [ ! -d "static" ]; then
    echo "❌ Error: Required directories missing!"
    exit 1
fi
echo "✓ All required directories present"

echo "[3.5] Installing dependencies..."
pip3 install -r requirements.txt --quiet
echo "✓ Dependencies installed"

echo "[3.6] Starting service..."
sudo systemctl start mqtt-manager
sleep 5
echo "✓ Service started"

echo "[3.7] Checking service status..."
sudo systemctl status mqtt-manager --no-pager | head -10
ENDSSH

echo ""
echo "[4/7] Verifying deployment..."
sleep 5

# Test health endpoint
echo "[5/7] Testing health endpoint..."
HEALTH_RESPONSE=$(curl -s http://34.63.146.196:5000/api/health || echo "FAILED")
if echo "$HEALTH_RESPONSE" | grep -q "healthy"; then
    echo "✓ Health check passed"
else
    echo "⚠ Warning: Health check failed or service not responding"
fi
echo ""

echo "[6/7] Testing HTTPS endpoint..."
HTTPS_STATUS=$(curl -I -s https://hddm.pqsolutionsperu.com/login | head -1 || echo "FAILED")
if echo "$HTTPS_STATUS" | grep -q "200"; then
    echo "✓ HTTPS endpoint responding"
else
    echo "⚠ Warning: HTTPS endpoint check failed"
fi
echo ""

echo "[7/7] Deployment Summary"
echo "=========================================="
echo "✓ Package uploaded"
echo "✓ Backup created"
echo "✓ Service restarted"
echo "✓ Basic health checks completed"
echo ""
echo "📋 Next Steps:"
echo "   1. Login at: https://hddm.pqsolutionsperu.com/login"
echo "   2. Test all features (Dashboard, VM, ESP32, Clients, Events)"
echo "   3. Monitor logs: ssh $VM_USER@$VM_HOST 'sudo journalctl -u mqtt-manager -f'"
echo "   4. Check memory: ssh $VM_USER@$VM_HOST 'ps aux | grep gunicorn'"
echo ""
echo "🚨 Rollback if needed:"
echo "   ssh $VM_USER@$VM_HOST"
echo "   cd /home/pqsolutions/mqtt-manager/"
echo "   sudo systemctl stop mqtt-manager"
echo "   tar -xzf /home/pqsolutions/backups/mqtt-manager-backup-*.tar.gz"
echo "   sudo systemctl start mqtt-manager"
echo ""
echo "=========================================="
echo "✅ DEPLOYMENT COMPLETE!"
echo "=========================================="
