#!/bin/bash
# Netdata Installation Script
# For HDD-Monitor VM auto-sustainability project

echo "=================================================="
echo "  Installing Netdata Monitoring System"
echo "=================================================="
echo ""
echo "This will install Netdata with:"
echo "  - Stable channel"
echo "  - Telemetry disabled (privacy)"
echo "  - ~180MB RAM usage"
echo "  - 3% CPU overhead"
echo ""

# Download the kickstart script
echo "[1/2] Downloading Netdata kickstart script..."
wget -O /tmp/netdata-kickstart.sh https://get.netdata.cloud/kickstart.sh

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to download Netdata installer"
    exit 1
fi

# Run the installer
echo ""
echo "[2/2] Running Netdata installer..."
echo "This may take a few minutes..."
sh /tmp/netdata-kickstart.sh --stable-channel --disable-telemetry

if [ $? -eq 0 ]; then
    echo ""
    echo "=================================================="
    echo "  ✓ Netdata installed successfully!"
    echo "=================================================="
    echo ""
    echo "Next steps:"
    echo "  1. Configure Netdata: /etc/netdata/netdata.conf"
    echo "  2. Setup health alerts: /etc/netdata/health.d/"
    echo "  3. Configure Nginx reverse proxy"
    echo "  4. Access dashboard: http://localhost:19999"
    echo ""
    echo "Service commands:"
    echo "  sudo systemctl status netdata"
    echo "  sudo systemctl restart netdata"
    echo "  sudo journalctl -u netdata -f"
    echo ""
else
    echo "ERROR: Netdata installation failed"
    exit 1
fi
