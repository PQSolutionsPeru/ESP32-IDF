#!/bin/bash
###############################################################################
# Deploy Script - HDD Monitor Dashboard
# Sube archivos modificados a la VM de GCP y reinicia el servicio
###############################################################################

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
SSH_KEY="$HOME/.ssh/google_compute_engine"
VM_USER="pqsolutionsperu"
VM_IP="34.63.146.196"
VM_PATH="/home/pqsolutions/mqtt-manager"
LOCAL_PATH="/mnt/e/PQSolutions/HDD-Monitor/ESP32-IDF/ESP32-IDF/APP/HDD1_2/VM GOOGLE CLOUD/mqtt-manager"

echo "================================================================"
echo "  HDD Monitor Dashboard - Deployment Script"
echo "================================================================"
echo ""

# Check if SSH key exists
if [ ! -f "$SSH_KEY" ]; then
    echo -e "${RED}ERROR: SSH key not found at $SSH_KEY${NC}"
    echo "Trying alternative key..."
    SSH_KEY="$HOME/.ssh/id_ed25519"
    if [ ! -f "$SSH_KEY" ]; then
        echo -e "${RED}ERROR: No SSH key found${NC}"
        exit 1
    fi
fi

echo -e "${GREEN}✓${NC} Using SSH key: $SSH_KEY"
echo ""

# Function to upload file
upload_file() {
    local file=$1
    local dest=$2
    echo -e "Uploading ${YELLOW}$file${NC}..."
    if scp -i "$SSH_KEY" "$LOCAL_PATH/$file" "$VM_USER@$VM_IP:$VM_PATH/$dest" 2>/dev/null; then
        echo -e "${GREEN}✓${NC} Uploaded $file"
    else
        echo -e "${RED}✗${NC} Failed to upload $file"
        return 1
    fi
}

# Upload modified files
echo "Step 1: Uploading modified files..."
echo "-----------------------------------"
upload_file "templates/base.html" "templates/"
upload_file "templates/login.html" "templates/"
upload_file "templates/mqtt_config.html" "templates/"
upload_file "app.py" ""
upload_file "diagnose.py" ""
upload_file "modules/firestore_client.py" "modules/"
upload_file "modules/firestore_realtime.py" "modules/"
upload_file "static/js/app.js" "static/js/"
upload_file "static/js/dashboard.js" "static/js/"
upload_file "static/js/esp32.js" "static/js/"
upload_file "static/js/events.js" "static/js/"
upload_file "static/js/realtime.js" "static/js/"
upload_file "install_realtime_deps.sh" ""
echo ""

# Make scripts executable
echo "Step 2: Setting permissions..."
echo "-----------------------------------"
ssh -i "$SSH_KEY" "$VM_USER@$VM_IP" "chmod +x $VM_PATH/diagnose.py $VM_PATH/install_realtime_deps.sh" 2>/dev/null
echo -e "${GREEN}✓${NC} Set executable permissions"
echo ""

# Install real-time dependencies
echo "Step 3: Installing real-time dependencies..."
echo "-----------------------------------"
echo "This may take a few minutes..."
ssh -i "$SSH_KEY" "$VM_USER@$VM_IP" "bash $VM_PATH/install_realtime_deps.sh" 2>&1 | grep -E "(✓|Step|Installing|Verifying)"
echo -e "${GREEN}✓${NC} Dependencies installed"
echo ""

# Run diagnostic
echo "Step 4: Running diagnostics..."
echo "-----------------------------------"
echo "Executing diagnostic script on VM..."
ssh -i "$SSH_KEY" "$VM_USER@$VM_IP" "cd $VM_PATH && /home/pqsolutions/mqtt-manager-venv/bin/python3 diagnose.py"
echo ""

# Restart service
echo "Step 5: Restarting mqtt-manager service..."
echo "-----------------------------------"
ssh -i "$SSH_KEY" "$VM_USER@$VM_IP" "sudo systemctl restart mqtt-manager"
echo -e "${GREEN}✓${NC} Service restarted"
echo ""

# Check service status
echo "Step 6: Checking service status..."
echo "-----------------------------------"
ssh -i "$SSH_KEY" "$VM_USER@$VM_IP" "sudo systemctl is-active mqtt-manager" > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓${NC} Service is running"
else
    echo -e "${RED}✗${NC} Service is not running!"
    echo "Check logs with: sudo journalctl -u mqtt-manager -n 50"
    exit 1
fi
echo ""

# Show recent logs
echo "Step 7: Recent logs (last 10 lines)..."
echo "-----------------------------------"
ssh -i "$SSH_KEY" "$VM_USER@$VM_IP" "sudo journalctl -u mqtt-manager -n 10 --no-pager"
echo ""

echo "================================================================"
echo -e "${GREEN}Deployment completed successfully!${NC}"
echo "================================================================"
echo ""
echo "🚀 Real-time updates are now ENABLED!"
echo ""
echo "Next steps:"
echo "1. Open https://hdd.pqsolutionsperu.com in your browser"
echo "2. Press Ctrl+Shift+R to hard refresh"
echo "3. Verify that:"
echo "   - Real-time indicator shows 'Live' status (green)"
echo "   - Dashboard updates automatically when data changes"
echo "   - No need to refresh manually anymore!"
echo ""
echo "To view live logs:"
echo "  ssh -i $SSH_KEY $VM_USER@$VM_IP 'sudo journalctl -u mqtt-manager -f'"
echo ""
