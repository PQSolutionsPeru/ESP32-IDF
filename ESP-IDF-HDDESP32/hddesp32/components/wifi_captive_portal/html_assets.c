#include <stdint.h>

// Página principal con CSS integrado
const char index_html[] = R"HTML(
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ESP32 Fire Panel WiFi Setup</title>
    <style>
        body {
            font-family: Arial, sans-serif;
            margin: 0;
            padding: 0;
            background-color: #f5f5f5;
            color: #333;
        }
        .container {
            max-width: 500px;
            margin: 0 auto;
            padding: 20px;
        }
        .header {
            text-align: center;
            padding: 10px;
            margin-bottom: 20px;
            background-color: #cc0000;
            color: white;
            border-radius: 5px;
        }
        .card {
            background: white;
            padding: 20px;
            border-radius: 5px;
            box-shadow: 0 2px 10px rgba(0, 0, 0, 0.1);
            margin-bottom: 20px;
        }
        h1 {
            margin: 0;
            font-size: 24px;
        }
        h2 {
            margin-top: 0;
            font-size: 18px;
        }
        .status {
            padding: 10px;
            margin-bottom: 20px;
            border-radius: 5px;
        }
        .status.connected {
            background-color: #d4edda;
            border: 1px solid #c3e6cb;
            color: #155724;
        }
        .status.disconnected {
            background-color: #f8d7da;
            border: 1px solid #f5c6cb;
            color: #721c24;
        }
        .form-group {
            margin-bottom: 15px;
        }
        label {
            display: block;
            margin-bottom: 5px;
            font-weight: bold;
        }
        input[type="text"], input[type="password"] {
            width: 100%;
            padding: 8px;
            border: 1px solid #ddd;
            border-radius: 4px;
            box-sizing: border-box;
        }
        button {
            background-color: #cc0000;
            color: white;
            border: none;
            padding: 10px 15px;
            border-radius: 4px;
            cursor: pointer;
            font-size: 16px;
            width: 100%;
        }
        button:hover {
            background-color: #a30000;
        }
        .wifi-list {
            margin-top: 20px;
            max-height: 300px;
            overflow-y: auto;
        }
        .wifi-network {
            padding: 10px;
            margin-bottom: 5px;
            border: 1px solid #ddd;
            border-radius: 4px;
            cursor: pointer;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .wifi-network:hover {
            background-color: #f0f0f0;
        }
        .signal-strength {
            display: inline-block;
            width: 20px;
            text-align: right;
        }
        .footer {
            text-align: center;
            padding: 10px;
            font-size: 12px;
            color: #777;
        }
        .scan-button {
            background-color: #6c757d;
            margin-bottom: 10px;
        }
        .scan-button:hover {
            background-color: #5a6268;
        }
        .loading {
            text-align: center;
            padding: 20px;
            font-style: italic;
            color: #777;
        }
        .hidden {
            display: none;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>ESP32 Fire Panel</h1>
        </div>

        <div id="status" class="status disconnected">
            <span id="status-text">Not connected to WiFi network</span>
        </div>

        <div class="card">
            <h2>WiFi Configuration</h2>
            <div id="wifi-form">
                <div class="form-group">
                    <label for="ssid">WiFi Network:</label>
                    <input type="text" id="ssid" name="ssid" placeholder="Enter network name">
                </div>
                <div class="form-group">
                    <label for="password">Password:</label>
                    <input type="password" id="password" name="password" placeholder="Enter password">
                </div>
                <button type="button" id="connect-button">Connect</button>
            </div>
        </div>

        <div class="card">
            <h2>Available Networks</h2>
            <button type="button" id="scan-button" class="scan-button">Scan for Networks</button>
            <div id="loading" class="loading hidden">Scanning...</div>
            <div id="wifi-list" class="wifi-list"></div>
        </div>

        <div class="footer">
            <p>ESP32 Fire Panel Monitor - HDD Sistemas</p>
        </div>
    </div>

    <script>
        // Update status if connected
        function updateStatus() {
            fetch('/api/status')
                .then(response => response.json())
                .then(data => {
                    if (data.connected) {
                        document.getElementById('status').className = 'status connected';
                        document.getElementById('status-text').innerText = 'Connected to: ' + data.ssid;
                    } else {
                        document.getElementById('status').className = 'status disconnected';
                        document.getElementById('status-text').innerText = 'Not connected to WiFi network';
                    }
                })
                .catch(error => console.error('Error fetching status:', error));
        }

        // Scan for networks
        function scanNetworks() {
            document.getElementById('loading').classList.remove('hidden');
            document.getElementById('wifi-list').innerHTML = '';
            
            fetch('/api/scan')
                .then(response => response.json())
                .then(data => {
                    document.getElementById('loading').classList.add('hidden');
                    const wifiList = document.getElementById('wifi-list');
                    wifiList.innerHTML = '';
                    
                    if (data.networks && data.networks.length > 0) {
                        data.networks.forEach(network => {
                            const signalStrength = getSignalIcon(network.rssi);
                            const div = document.createElement('div');
                            div.className = 'wifi-network';
                            div.innerHTML = `
                                <span>${network.ssid}</span>
                                <span class="signal-strength">${signalStrength}</span>
                            `;
                            div.addEventListener('click', () => {
                                document.getElementById('ssid').value = network.ssid;
                                document.getElementById('password').focus();
                            });
                            wifiList.appendChild(div);
                        });
                    } else {
                        wifiList.innerHTML = '<p>No networks found</p>';
                    }
                })
                .catch(error => {
                    document.getElementById('loading').classList.add('hidden');
                    console.error('Error scanning networks:', error);
                    document.getElementById('wifi-list').innerHTML = '<p>Error scanning networks</p>';
                });
        }

        // Get signal icon based on RSSI
        function getSignalIcon(rssi) {
            if (rssi >= -50) {
                return '●●●●';
            } else if (rssi >= -65) {
                return '●●●○';
            } else if (rssi >= -75) {
                return '●●○○';
            } else if (rssi >= -85) {
                return '●○○○';
            } else {
                return '○○○○';
            }
        }

        // Connect to WiFi
        function connectToWifi() {
            const ssid = document.getElementById('ssid').value;
            const password = document.getElementById('password').value;
            
            if (!ssid) {
                alert('Please enter a network name');
                return;
            }
            
            const connectButton = document.getElementById('connect-button');
            connectButton.innerText = 'Connecting...';
            connectButton.disabled = true;
            
            fetch('/api/connect', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    ssid: ssid,
                    password: password
                }),
            })
            .then(response => response.json())
            .then(data => {
                connectButton.innerText = 'Connect';
                connectButton.disabled = false;
                
                if (data.success) {
                    alert('Connected successfully! The device will restart in STA mode.');
                } else {
                    alert('Failed to connect: ' + data.message);
                }
            })
            .catch(error => {
                console.error('Error connecting to WiFi:', error);
                connectButton.innerText = 'Connect';
                connectButton.disabled = false;
                alert('Error connecting to WiFi');
            });
        }

        // Initialize page
        document.addEventListener('DOMContentLoaded', function() {
            updateStatus();
            
            document.getElementById('scan-button').addEventListener('click', scanNetworks);
            document.getElementById('connect-button').addEventListener('click', connectToWifi);
            
            // Scan networks on page load
            scanNetworks();
            
            // Update status every 5 seconds
            setInterval(updateStatus, 5000);
        });
    </script>
</body>
</html>
)HTML";