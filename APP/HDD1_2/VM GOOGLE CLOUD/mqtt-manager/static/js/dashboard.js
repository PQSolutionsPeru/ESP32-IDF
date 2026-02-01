/**
 * Dashboard JavaScript Module
 * Handles auto-refresh and metric updates for the dashboard
 */

let refreshInterval = null;
let retryCount = 0;
const MAX_RETRIES = 3;
const REFRESH_INTERVAL = 30000; // 30 seconds

/**
 * DataRefresher class with exponential backoff
 */
class DataRefresher {
    constructor(interval = REFRESH_INTERVAL) {
        this.interval = interval;
        this.retryCount = 0;
        this.maxRetries = MAX_RETRIES;
        this.isRunning = false;
    }

    start(callback) {
        if (this.isRunning) return;
        this.isRunning = true;
        this.refreshInterval = setInterval(() => {
            callback();
        }, this.interval);
    }

    stop() {
        if (this.refreshInterval) {
            clearInterval(this.refreshInterval);
            this.refreshInterval = null;
        }
        this.isRunning = false;
    }

    reset() {
        this.retryCount = 0;
    }
}

// Global refresher instance
const dashboardRefresher = new DataRefresher();

/**
 * Update dashboard metrics from API
 */
async function updateDashboardMetrics() {
    try {
        const response = await fetch('/api/dashboard/metrics', {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const data = await response.json();

        if (data.success && data.metrics) {
            updateMetricsUI(data.metrics);
            updateStatusMessage('success', 'All systems operational');
            retryCount = 0; // Reset retry count on success
            dashboardRefresher.reset();
        } else {
            throw new Error(data.error || 'Failed to load metrics');
        }
    } catch (error) {
        console.error('Error updating dashboard metrics:', error);
        retryCount++;

        if (retryCount >= MAX_RETRIES) {
            updateStatusMessage('error', `Failed to load metrics after ${MAX_RETRIES} attempts. Check connection.`);
            showToast('Dashboard update failed', 'error');
        } else {
            updateStatusMessage('warning', `Loading metrics... (attempt ${retryCount}/${MAX_RETRIES})`);
        }

        // Set fallback values
        setFallbackMetrics();
    }
}

/**
 * Update metrics UI elements
 */
function updateMetricsUI(metrics) {
    // ESP32 Devices Online
    const esp32Online = document.getElementById('esp32Online');
    const esp32Total = document.getElementById('esp32Total');
    if (esp32Online && esp32Total) {
        esp32Online.textContent = metrics.esp32_online || 0;
        esp32Total.textContent = `of ${metrics.esp32_total || 0} total devices`;
    }

    // Panels OK
    const panelsOk = document.getElementById('panelsOk');
    const panelsTotal = document.getElementById('panelsTotal');
    if (panelsOk && panelsTotal) {
        panelsOk.textContent = metrics.panels_ok || 0;
        panelsTotal.textContent = `of ${metrics.panels_total || 0} total panels`;
    }

    // Events Today
    const eventsToday = document.getElementById('eventsToday');
    if (eventsToday) {
        eventsToday.textContent = metrics.events_today || 0;
    }

    // MQTT Status
    const mqttStatus = document.getElementById('mqttStatus');
    if (mqttStatus) {
        const status = metrics.mqtt_status || 'unknown';
        let statusClass = 'status-info';
        let statusText = 'Unknown';

        if (status === 'online') {
            statusClass = 'status-online';
            statusText = 'Online';
        } else if (status === 'offline') {
            statusClass = 'status-offline';
            statusText = 'Offline';
        } else if (status === 'warning') {
            statusClass = 'status-warning';
            statusText = 'Warning';
        }

        mqttStatus.className = `status-badge ${statusClass}`;
        mqttStatus.innerHTML = `
            <span class="status-dot"></span>
            <span>${statusText}</span>
        `;
    }

    // Update last updated timestamp
    const lastUpdated = document.getElementById('lastUpdated');
    if (lastUpdated) {
        const now = new Date();
        lastUpdated.textContent = `Last updated: ${now.toLocaleTimeString()}`;
    }
}

/**
 * Set fallback metrics when API fails
 */
function setFallbackMetrics() {
    const esp32Online = document.getElementById('esp32Online');
    const panelsOk = document.getElementById('panelsOk');
    const eventsToday = document.getElementById('eventsToday');
    const mqttStatus = document.getElementById('mqttStatus');

    if (esp32Online) esp32Online.textContent = '--';
    if (panelsOk) panelsOk.textContent = '--';
    if (eventsToday) eventsToday.textContent = '--';

    if (mqttStatus) {
        mqttStatus.className = 'status-badge status-warning';
        mqttStatus.innerHTML = `
            <span class="status-dot"></span>
            <span>Unknown</span>
        `;
    }
}

/**
 * Update status message
 */
function updateStatusMessage(type, message) {
    const statusMessage = document.getElementById('statusMessage');
    if (!statusMessage) return;

    const icons = {
        'success': '✓',
        'error': '✗',
        'warning': '⚠',
        'info': 'ℹ'
    };

    const colors = {
        'success': 'var(--success-color)',
        'error': 'var(--danger-color)',
        'warning': 'var(--warning-color)',
        'info': 'var(--info-color)'
    };

    statusMessage.innerHTML = `
        <span style="color: ${colors[type]}; margin-right: 8px; font-weight: 700;">${icons[type]}</span>
        <span>${message}</span>
    `;
}

/**
 * Start dashboard auto-refresh
 */
function startDashboardRefresh() {
    // Stop existing refresh if any
    dashboardRefresher.stop();

    // Start new refresh cycle
    dashboardRefresher.start(updateDashboardMetrics);

    // Handle page visibility changes (battery/bandwidth saving)
    document.addEventListener('visibilitychange', function() {
        if (document.hidden) {
            dashboardRefresher.stop();
        } else {
            // Refresh immediately when page becomes visible
            updateDashboardMetrics();
            dashboardRefresher.start(updateDashboardMetrics);
        }
    });
}

/**
 * Stop dashboard auto-refresh
 */
function stopDashboardRefresh() {
    dashboardRefresher.stop();
}

// Make functions available globally
window.updateDashboardMetrics = updateDashboardMetrics;
window.startDashboardRefresh = startDashboardRefresh;
window.stopDashboardRefresh = stopDashboardRefresh;
