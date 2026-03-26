#!/bin/bash
###############################################################################
# Install Real-time Dependencies for HDD Monitor Dashboard
# Installs Flask-SocketIO and required dependencies for WebSocket support
###############################################################################

set -e

echo "================================================================"
echo "  Installing Real-time Dependencies"
echo "================================================================"
echo ""

# Activate virtual environment
VENV_PATH="/home/pqsolutions/mqtt-manager-venv"

if [ ! -d "$VENV_PATH" ]; then
    echo "✗ Virtual environment not found at $VENV_PATH"
    exit 1
fi

echo "Step 1: Activating virtual environment..."
source "$VENV_PATH/bin/activate"
echo "✓ Virtual environment activated"
echo ""

# Upgrade pip
echo "Step 2: Upgrading pip..."
python3 -m pip install --upgrade pip
echo "✓ pip upgraded"
echo ""

# Install dependencies
echo "Step 3: Installing Flask-SocketIO and dependencies..."
pip install flask-socketio==5.3.5
pip install python-socketio==5.10.0
pip install eventlet==0.33.3
pip install gevent==23.9.1
pip install gevent-websocket==0.10.1
echo "✓ Dependencies installed"
echo ""

# Verify installation
echo "Step 4: Verifying installation..."
python3 -c "import flask_socketio; print('Flask-SocketIO version:', flask_socketio.__version__)" || (echo "✗ Flask-SocketIO import failed"; exit 1)
python3 -c "import socketio; print('Python-SocketIO version:', socketio.__version__)" || (echo "✗ Python-SocketIO import failed"; exit 1)
python3 -c "import eventlet; print('Eventlet version:', eventlet.__version__)" || (echo "✗ Eventlet import failed"; exit 1)
echo "✓ All packages installed successfully"
echo ""

echo "================================================================"
echo "✓ Real-time dependencies installed!"
echo "================================================================"
echo ""
echo "Next steps:"
echo "1. Restart mqtt-manager service: sudo systemctl restart mqtt-manager"
echo "2. Check logs: sudo journalctl -u mqtt-manager -f"
echo ""
