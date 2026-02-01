/**
 * ESP32 Devices Monitoring JavaScript
 * Handles device list, filtering, and detail modal
 */

let allDevices = [];
let refreshInterval = null;
const REFRESH_INTERVAL = 30000; // 30 seconds

/**
 * Initialize ESP32 devices page
 */
document.addEventListener('DOMContentLoaded', function() {
    loadDevices();
    startAutoRefresh();

    // Close modal when clicking outside
    document.getElementById('deviceModal')?.addEventListener('click', function(e) {
        if (e.target.id === 'deviceModal') {
            closeDeviceModal();
        }
    });
});

/**
 * Load all ESP32 devices from API
 */
async function loadDevices() {
    try {
        const response = await fetch('/api/esp32/devices', {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const data = await response.json();

        if (data.success && data.devices) {
            allDevices = data.devices;
            renderDevicesTable(allDevices);
        } else {
            throw new Error(data.error || 'Failed to load devices');
        }
    } catch (error) {
        console.error('Error loading devices:', error);
        showErrorInTable('Failed to load devices. Check connection.');
        showToast('Failed to load devices', 'error');
    }
}

/**
 * Render devices table
 */
function renderDevicesTable(devices) {
    const tbody = document.getElementById('devicesTableBody');
    if (!tbody) return;

    if (devices.length === 0) {
        tbody.innerHTML = `
            <tr>
                <td colspan="7" style="text-align: center; padding: 40px; color: var(--text-secondary);">
                    No devices found
                </td>
            </tr>
        `;
        return;
    }

    tbody.innerHTML = devices.map(device => {
        const status = device.status || 'unknown';
        const statusBadge = getStatusBadge(status);
        const lastSeen = formatLastSeen(device.last_seen);

        return `
            <tr onclick="showDeviceDetails('${device.id}')">
                <td><span class="device-id">${device.id || 'N/A'}</span></td>
                <td>${device.mac_address || 'N/A'}</td>
                <td>${device.ip_address || 'N/A'}</td>
                <td>${statusBadge}</td>
                <td>${device.firmware_version || 'N/A'}</td>
                <td>${lastSeen}</td>
                <td onclick="event.stopPropagation()">
                    <button class="btn-icon" onclick="showDeviceDetails('${device.id}')" title="View Details">
                        👁️
                    </button>
                </td>
            </tr>
        `;
    }).join('');
}

/**
 * Get status badge HTML
 */
function getStatusBadge(status) {
    const badges = {
        'online': '<span class="status-badge status-online"><span class="status-dot"></span>Online</span>',
        'offline': '<span class="status-badge status-offline"><span class="status-dot"></span>Offline</span>',
        'inactive': '<span class="status-badge status-warning"><span class="status-dot"></span>Inactive</span>',
        'awaiting_config': '<span class="status-badge status-info"><span class="status-dot"></span>Awaiting Config</span>',
        'unknown': '<span class="status-badge status-warning"><span class="status-dot"></span>Unknown</span>'
    };

    return badges[status] || badges['unknown'];
}

/**
 * Format last seen timestamp
 */
function formatLastSeen(timestamp) {
    if (!timestamp) return 'Never';

    try {
        const date = new Date(timestamp);
        const now = new Date();
        const diffMs = now - date;
        const diffMins = Math.floor(diffMs / 60000);

        if (diffMins < 1) return 'Just now';
        if (diffMins < 60) return `${diffMins}m ago`;

        const diffHours = Math.floor(diffMins / 60);
        if (diffHours < 24) return `${diffHours}h ago`;

        const diffDays = Math.floor(diffHours / 24);
        return `${diffDays}d ago`;
    } catch (error) {
        return 'Unknown';
    }
}

/**
 * Filter devices by status
 */
function filterDevices() {
    const filter = document.getElementById('statusFilter')?.value || 'all';

    if (filter === 'all') {
        renderDevicesTable(allDevices);
    } else {
        const filtered = allDevices.filter(device => device.status === filter);
        renderDevicesTable(filtered);
    }
}

/**
 * Show device details modal
 */
async function showDeviceDetails(deviceId) {
    try {
        const response = await fetch(`/api/esp32/devices/${deviceId}`, {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const data = await response.json();

        if (data.success && data.device) {
            renderDeviceModal(data.device);
        } else {
            throw new Error(data.error || 'Failed to load device details');
        }
    } catch (error) {
        console.error('Error loading device details:', error);
        showToast('Failed to load device details', 'error');
    }
}

/**
 * Render device details in modal
 */
function renderDeviceModal(device) {
    const modal = document.getElementById('deviceModal');
    const modalTitle = document.getElementById('modalTitle');
    const modalBody = document.getElementById('modalBody');

    if (!modal || !modalTitle || !modalBody) return;

    modalTitle.textContent = `Device: ${device.id}`;

    const statusBadge = getStatusBadge(device.status);
    const lastSeen = formatLastSeen(device.last_seen);

    modalBody.innerHTML = `
        <div class="detail-row">
            <span class="detail-label">ESP32 ID:</span>
            <span class="detail-value">${device.id || 'N/A'}</span>
        </div>
        <div class="detail-row">
            <span class="detail-label">Status:</span>
            <span>${statusBadge}</span>
        </div>
        <div class="detail-row">
            <span class="detail-label">MAC Address:</span>
            <span class="detail-value">${device.mac_address || 'N/A'}</span>
        </div>
        <div class="detail-row">
            <span class="detail-label">IP Address:</span>
            <span class="detail-value">${device.ip_address || 'N/A'}</span>
        </div>
        <div class="detail-row">
            <span class="detail-label">Firmware Version:</span>
            <span class="detail-value">${device.firmware_version || 'N/A'}</span>
        </div>
        <div class="detail-row">
            <span class="detail-label">Last Seen:</span>
            <span class="detail-value">${lastSeen}</span>
        </div>
        <div class="detail-row">
            <span class="detail-label">Assigned Panel:</span>
            <span class="detail-value">${device.assigned_panel || 'None'}</span>
        </div>
        <div class="detail-row">
            <span class="detail-label">Capabilities:</span>
            <span class="detail-value">${device.capabilities?.join(', ') || 'N/A'}</span>
        </div>
    `;

    modal.style.display = 'flex';
}

/**
 * Close device modal
 */
function closeDeviceModal() {
    const modal = document.getElementById('deviceModal');
    if (modal) {
        modal.style.display = 'none';
    }
}

/**
 * Refresh devices
 */
function refreshDevices() {
    loadDevices();
    showToast('Devices refreshed', 'success');
}

/**
 * Start auto-refresh
 */
function startAutoRefresh() {
    if (refreshInterval) {
        clearInterval(refreshInterval);
    }

    refreshInterval = setInterval(() => {
        if (!document.hidden) {
            loadDevices();
        }
    }, REFRESH_INTERVAL);

    // Handle page visibility changes
    document.addEventListener('visibilitychange', function() {
        if (!document.hidden) {
            loadDevices();
        }
    });
}

/**
 * Show error in table
 */
function showErrorInTable(message) {
    const tbody = document.getElementById('devicesTableBody');
    if (tbody) {
        tbody.innerHTML = `
            <tr>
                <td colspan="7" style="text-align: center; padding: 40px; color: var(--danger-color);">
                    ⚠️ ${message}
                </td>
            </tr>
        `;
    }
}

// Make functions available globally
window.filterDevices = filterDevices;
window.showDeviceDetails = showDeviceDetails;
window.closeDeviceModal = closeDeviceModal;
window.refreshDevices = refreshDevices;
