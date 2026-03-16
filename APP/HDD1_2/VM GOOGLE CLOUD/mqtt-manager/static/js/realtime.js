/**
 * Real-time WebSocket Client for HDD Monitor Dashboard
 * Handles Socket.IO connections and real-time updates
 */

// Global socket instance
let socket = null;
let reconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 5;

/**
 * Initialize Socket.IO connection
 */
function initializeRealtime() {
    console.log('[Realtime] Initializing WebSocket connection...');

    // Connect to Socket.IO server
    socket = io({
        transports: ['websocket', 'polling'],
        reconnection: true,
        reconnectionDelay: 1000,
        reconnectionDelayMax: 5000,
        reconnectionAttempts: MAX_RECONNECT_ATTEMPTS
    });

    // Connection established
    socket.on('connect', function() {
        console.log('[Realtime] Connected to server');
        reconnectAttempts = 0;
        showConnectionStatus('connected');

        // Request initial data
        socket.emit('request_initial_data');
    });

    // Connection error
    socket.on('connect_error', function(error) {
        console.error('[Realtime] Connection error:', error);
        reconnectAttempts++;

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            showConnectionStatus('failed');
            console.error('[Realtime] Max reconnection attempts reached');
        } else {
            showConnectionStatus('reconnecting');
        }
    });

    // Disconnection
    socket.on('disconnect', function(reason) {
        console.log('[Realtime] Disconnected:', reason);
        showConnectionStatus('disconnected');
    });

    // Connection established confirmation
    socket.on('connection_established', function(data) {
        console.log('[Realtime] Connection confirmed:', data.message);
    });

    // ========================================================================
    // ESP32 DEVICE EVENTS
    // ========================================================================

    socket.on('esp32_added', function(device) {
        console.log('[Realtime] ESP32 added:', device.id);
        if (typeof onESP32Added === 'function') {
            onESP32Added(device);
        }
    });

    socket.on('esp32_updated', function(device) {
        console.log('[Realtime] ESP32 updated:', device.id, 'status:', device.status);
        if (typeof onESP32Updated === 'function') {
            onESP32Updated(device);
        }
    });

    socket.on('esp32_removed', function(data) {
        console.log('[Realtime] ESP32 removed:', data.id);
        if (typeof onESP32Removed === 'function') {
            onESP32Removed(data.id);
        }
    });

    socket.on('esp32_offline', function(data) {
        console.log('[Realtime] ESP32 went offline:', data.esp32_id);
        if (typeof showToast === 'function') {
            showToast(`ESP32 ${data.esp32_id} went offline`, 'warning');
        }
        if (typeof onESP32Offline === 'function') {
            onESP32Offline(data);
        }
    });

    // ========================================================================
    // PANEL EVENTS
    // ========================================================================

    socket.on('panel_updated', function(panel) {
        console.log('[Realtime] Panel updated:', panel.id);
        if (typeof onPanelUpdated === 'function') {
            onPanelUpdated(panel);
        }
    });

    socket.on('panel_needs_refresh', function(data) {
        console.log('[Realtime] Panel needs refresh:', data.panel_id);
        if (typeof onPanelNeedsRefresh === 'function') {
            onPanelNeedsRefresh(data);
        }
    });

    // ========================================================================
    // RELAY EVENTS
    // ========================================================================

    socket.on('relay_status_changed', function(relay) {
        console.log('[Realtime] Relay status changed:', relay.relay_id, 'status:', relay.status);

        if (typeof showToast === 'function' && relay.status === 'DISC') {
            showToast(`Relay ${relay.relay_id} disconnected`, 'warning');
        }

        if (typeof onRelayStatusChanged === 'function') {
            onRelayStatusChanged(relay);
        }
    });

    // ========================================================================
    // EVENT EVENTS
    // ========================================================================

    socket.on('event_added', function(event) {
        console.log('[Realtime] Event added:', event.title || event.id);
        if (typeof onEventAdded === 'function') {
            onEventAdded(event);
        }
        if (typeof showToast === 'function') {
            showToast(`New event: ${event.title || 'Untitled'}`, 'info');
        }
    });

    socket.on('event_updated', function(event) {
        console.log('[Realtime] Event updated:', event.title || event.id);
        if (typeof onEventUpdated === 'function') {
            onEventUpdated(event);
        }
    });

    socket.on('event_removed', function(data) {
        console.log('[Realtime] Event removed:', data.id);
        if (typeof onEventRemoved === 'function') {
            onEventRemoved(data.id);
        }
    });

    // ========================================================================
    // METRICS REFRESH
    // ========================================================================

    socket.on('metrics_refresh_needed', function(data) {
        console.log('[Realtime] Metrics refresh needed');
        if (typeof onMetricsRefreshNeeded === 'function') {
            onMetricsRefreshNeeded();
        }
    });

    // ========================================================================
    // INITIAL DATA
    // ========================================================================

    socket.on('initial_metrics', function(metrics) {
        console.log('[Realtime] Received initial metrics:', metrics);
        if (typeof onInitialMetrics === 'function') {
            onInitialMetrics(metrics);
        }
    });

    socket.on('initial_esp32_devices', function(data) {
        console.log('[Realtime] Received initial ESP32 devices:', data.devices.length);
        if (typeof onInitialESP32Devices === 'function') {
            onInitialESP32Devices(data.devices);
        }
    });

    // ========================================================================
    // ERROR HANDLING
    // ========================================================================

    socket.on('error', function(data) {
        console.error('[Realtime] Error:', data.message);
        if (typeof showToast === 'function') {
            showToast(`Error: ${data.message}`, 'error');
        }
    });
}

/**
 * Show connection status indicator
 */
function showConnectionStatus(status) {
    const indicator = document.getElementById('realtimeIndicator');
    if (!indicator) return;

    const statusConfig = {
        'connected': {
            color: '#10b981',
            text: 'Live',
            icon: '●'
        },
        'disconnected': {
            color: '#ef4444',
            text: 'Offline',
            icon: '●'
        },
        'reconnecting': {
            color: '#f59e0b',
            text: 'Reconnecting...',
            icon: '◌'
        },
        'failed': {
            color: '#ef4444',
            text: 'Connection Failed',
            icon: '✗'
        }
    };

    const config = statusConfig[status] || statusConfig['disconnected'];

    indicator.innerHTML = `
        <span style="color: ${config.color}; margin-right: 4px;">${config.icon}</span>
        <span style="font-size: 0.813rem;">${config.text}</span>
    `;
    indicator.style.color = config.color;
}

/**
 * Disconnect from Socket.IO
 */
function disconnectRealtime() {
    if (socket) {
        socket.disconnect();
        console.log('[Realtime] Disconnected');
    }
}

// Heartbeat: mantiene la sesión activa en background cada 10 minutos
// Garantiza que la sesión nunca expire mientras el app está abierto en el smartphone
(function startSessionHeartbeat() {
    const HEARTBEAT_INTERVAL = 10 * 60 * 1000; // 10 minutos
    setInterval(function() {
        fetch('/api/auth/heartbeat', {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/json' }
        }).catch(function() {
            // Silencioso: si falla (sin internet), se reintentará en 10 min
        });
    }, HEARTBEAT_INTERVAL);
})();

// Initialize on page load
document.addEventListener('DOMContentLoaded', function() {
    console.log('[Realtime] DOM loaded, initializing...');
    initializeRealtime();
});

// Cleanup on page unload
window.addEventListener('beforeunload', function() {
    disconnectRealtime();
});

// Make socket available globally
window.realtimeSocket = socket;
window.initializeRealtime = initializeRealtime;
window.disconnectRealtime = disconnectRealtime;
