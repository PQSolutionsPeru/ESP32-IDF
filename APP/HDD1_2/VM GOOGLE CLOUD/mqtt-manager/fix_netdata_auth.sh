#!/bin/bash
###############################################################################
# Fix Netdata Authentication - Remove HTTP Basic Auth
# This allows Netdata to be accessed using only Flask session
###############################################################################

set -e

echo "================================================================"
echo "  Fixing Netdata Authentication"
echo "================================================================"
echo ""

# Backup current config
echo "Step 1: Backing up current nginx config..."
sudo cp /etc/nginx/sites-available/mqtt-manager /etc/nginx/sites-available/mqtt-manager.backup.$(date +%Y%m%d_%H%M%S)
echo "✓ Backup created"
echo ""

# Update nginx config - remove auth_basic lines
echo "Step 2: Updating nginx configuration..."
sudo sed -i '/auth_basic "HDD Monitor - System Metrics";/d' /etc/nginx/sites-available/mqtt-manager
sudo sed -i '/auth_basic_user_file \/etc\/nginx\/.htpasswd_metrics;/d' /etc/nginx/sites-available/mqtt-manager
echo "✓ Removed HTTP Basic Authentication from Netdata location"
echo ""

# Test nginx config
echo "Step 3: Testing nginx configuration..."
if sudo nginx -t 2>&1; then
    echo "✓ Nginx configuration is valid"
else
    echo "✗ Nginx configuration has errors!"
    echo "Restoring backup..."
    sudo cp /etc/nginx/sites-available/mqtt-manager.backup.$(date +%Y%m%d_%H%M%S) /etc/nginx/sites-available/mqtt-manager
    exit 1
fi
echo ""

# Reload nginx
echo "Step 4: Reloading nginx..."
sudo systemctl reload nginx
echo "✓ Nginx reloaded successfully"
echo ""

echo "================================================================"
echo "✓ Netdata authentication fixed!"
echo "================================================================"
echo ""
echo "VM Monitoring should now work with your Flask session only."
echo "Try accessing https://hddm.pqsolutionsperu.com in your browser."
echo ""
