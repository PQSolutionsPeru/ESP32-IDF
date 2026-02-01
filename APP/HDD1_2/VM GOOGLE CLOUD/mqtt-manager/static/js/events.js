/**
 * Events Management JavaScript
 * Handles event list, filtering, and detail modal
 */

let allClients = [];
let currentEvents = [];
let selectedClientId = null;

/**
 * Initialize events page
 */
document.addEventListener('DOMContentLoaded', function() {
    loadClients();

    // Close modal when clicking outside
    document.getElementById('eventModal')?.addEventListener('click', function(e) {
        if (e.target.id === 'eventModal') {
            closeEventModal();
        }
    });
});

/**
 * Load all clients for the dropdown
 */
async function loadClients() {
    try {
        const response = await fetch('/api/clients', {
            method: 'GET',
            headers: { 'Content-Type': 'application/json' }
        });

        if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);

        const data = await response.json();
        if (data.success && data.clients) {
            allClients = data.clients;
            populateClientDropdown(allClients);
        }
    } catch (error) {
        console.error('Error loading clients:', error);
        showToast('Failed to load clients', 'error');
    }
}

/**
 * Populate client dropdown
 */
function populateClientDropdown(clients) {
    const dropdown = document.getElementById('clientFilter');
    if (!dropdown) return;

    dropdown.innerHTML = '<option value="">Select Client</option>' +
        clients.map(client =>
            `<option value="${client.id}">${client.name || client.id}</option>`
        ).join('');
}

/**
 * Load events for selected client
 */
async function loadEventsForSelectedClient() {
    const dropdown = document.getElementById('clientFilter');
    if (!dropdown || !dropdown.value) {
        showEmptyState('Select a client to view events');
        return;
    }

    selectedClientId = dropdown.value;
    await loadEvents(selectedClientId);
}

/**
 * Load events from API
 */
async function loadEvents(clientId, status = null) {
    try {
        let url = `/api/events/${clientId}`;
        if (status) {
            url += `?status=${status}`;
        }

        const response = await fetch(url, {
            method: 'GET',
            headers: { 'Content-Type': 'application/json' }
        });

        if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);

        const data = await response.json();
        if (data.success && data.events) {
            currentEvents = data.events;
            renderEventsTable(currentEvents);
        } else {
            throw new Error(data.error || 'Failed to load events');
        }
    } catch (error) {
        console.error('Error loading events:', error);
        showErrorInTable('Failed to load events. Check connection.');
        showToast('Failed to load events', 'error');
    }
}

/**
 * Render events table
 */
function renderEventsTable(events) {
    const tbody = document.getElementById('eventsTableBody');
    if (!tbody) return;

    if (events.length === 0) {
        tbody.innerHTML = `
            <tr>
                <td colspan="6" style="text-align: center; padding: 40px; color: var(--text-secondary);">
                    No events found for this client
                </td>
            </tr>
        `;
        return;
    }

    tbody.innerHTML = events.map(event => {
        const statusBadge = getStatusBadge(event.status);
        const startDate = formatDate(event.fecha_inicio);
        const endDate = formatDate(event.fecha_fin);

        return `
            <tr onclick="showEventDetails(${JSON.stringify(event).replace(/"/g, '&quot;')})">
                <td><span class="event-title">${event.titulo || 'No Title'}</span></td>
                <td>${event.panel_name || 'N/A'}</td>
                <td>${statusBadge}</td>
                <td>${startDate}</td>
                <td>${endDate}</td>
                <td onclick="event.stopPropagation()">
                    <button class="btn-icon" onclick='showEventDetails(${JSON.stringify(event).replace(/'/g, "\\'")})'  title="View Details">
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
        'PROGRAMADO': '<span class="status-badge status-info"><span class="status-dot"></span>Programado</span>',
        'ACEPTADO': '<span class="status-badge status-warning"><span class="status-dot"></span>Aceptado</span>',
        'FINALIZADO': '<span class="status-badge status-online"><span class="status-dot"></span>Finalizado</span>',
        'CANCELADO': '<span class="status-badge status-offline"><span class="status-dot"></span>Cancelado</span>'
    };

    return badges[status] || `<span class="status-badge status-info">${status || 'Unknown'}</span>`;
}

/**
 * Format date for display
 */
function formatDate(dateStr) {
    if (!dateStr) return 'N/A';

    try {
        const date = new Date(dateStr);
        const day = String(date.getDate()).padStart(2, '0');
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const year = date.getFullYear();
        const hours = String(date.getHours()).padStart(2, '0');
        const minutes = String(date.getMinutes()).padStart(2, '0');

        return `${day}/${month}/${year} ${hours}:${minutes}`;
    } catch (error) {
        return 'Invalid Date';
    }
}

/**
 * Filter events by status
 */
function filterEventsByStatus() {
    const statusFilter = document.getElementById('statusFilter');
    if (!statusFilter) return;

    const status = statusFilter.value;

    if (!selectedClientId) {
        showToast('Please select a client first', 'warning');
        return;
    }

    if (status) {
        const filtered = currentEvents.filter(event => event.status === status);
        renderEventsTable(filtered);
    } else {
        // Reload all events
        loadEvents(selectedClientId);
    }
}

/**
 * Show event details modal
 */
function showEventDetails(event) {
    const modal = document.getElementById('eventModal');
    const modalTitle = document.getElementById('eventModalTitle');
    const modalBody = document.getElementById('eventModalBody');

    if (!modal || !modalTitle || !modalBody) return;

    modalTitle.textContent = event.titulo || 'Event Details';

    const statusBadge = getStatusBadge(event.status);
    const startDate = formatDate(event.fecha_inicio);
    const endDate = formatDate(event.fecha_fin);

    modalBody.innerHTML = `
        <div class="detail-row">
            <span class="detail-label">Title:</span>
            <span class="detail-value">${event.titulo || 'N/A'}</span>
        </div>
        <div class="detail-row">
            <span class="detail-label">Status:</span>
            <span>${statusBadge}</span>
        </div>
        <div class="detail-row">
            <span class="detail-label">Panel:</span>
            <span class="detail-value">${event.panel_name || 'N/A'}</span>
        </div>
        <div class="detail-row">
            <span class="detail-label">Start Date:</span>
            <span class="detail-value">${startDate}</span>
        </div>
        <div class="detail-row">
            <span class="detail-label">End Date:</span>
            <span class="detail-value">${endDate}</span>
        </div>
        <div class="detail-row">
            <span class="detail-label">Description:</span>
            <span class="detail-value">${event.descripcion || 'No description'}</span>
        </div>
        <div class="detail-row">
            <span class="detail-label">Contact Name:</span>
            <span class="detail-value">${event.contacto_nombre || 'N/A'}</span>
        </div>
        <div class="detail-row">
            <span class="detail-label">Contact Phone:</span>
            <span class="detail-value">${event.contacto_telefono || 'N/A'}</span>
        </div>
        <div class="detail-row">
            <span class="detail-label">Notes:</span>
            <span class="detail-value">${event.notas || 'No notes'}</span>
        </div>
    `;

    modal.style.display = 'flex';
}

/**
 * Close event modal
 */
function closeEventModal() {
    const modal = document.getElementById('eventModal');
    if (modal) {
        modal.style.display = 'none';
    }
}

/**
 * Refresh events
 */
function refreshEvents() {
    if (selectedClientId) {
        loadEvents(selectedClientId);
        showToast('Events refreshed', 'success');
    } else {
        showToast('Please select a client first', 'warning');
    }
}

/**
 * Show empty state
 */
function showEmptyState(message) {
    const tbody = document.getElementById('eventsTableBody');
    if (tbody) {
        tbody.innerHTML = `
            <tr>
                <td colspan="6" style="text-align: center; padding: 40px; color: var(--text-secondary);">
                    ${message}
                </td>
            </tr>
        `;
    }
}

/**
 * Show error in table
 */
function showErrorInTable(message) {
    const tbody = document.getElementById('eventsTableBody');
    if (tbody) {
        tbody.innerHTML = `
            <tr>
                <td colspan="6" style="text-align: center; padding: 40px; color: var(--danger-color);">
                    ⚠️ ${message}
                </td>
            </tr>
        `;
    }
}

// Add detail-row styles
const style = document.createElement('style');
style.textContent = `
    .detail-row {
        display: flex;
        justify-content: space-between;
        padding: 12px 0;
        border-bottom: 1px solid var(--border-color);
    }
    .detail-label {
        font-weight: 600;
        color: var(--text-secondary);
    }
    .detail-value {
        color: var(--text-primary);
        text-align: right;
    }
`;
document.head.appendChild(style);

// Make functions available globally
window.loadEventsForSelectedClient = loadEventsForSelectedClient;
window.filterEventsByStatus = filterEventsByStatus;
window.showEventDetails = showEventDetails;
window.closeEventModal = closeEventModal;
window.refreshEvents = refreshEvents;
