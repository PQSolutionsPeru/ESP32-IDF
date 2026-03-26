// ============================================================================
// MQTT Manager - Frontend JavaScript
// ============================================================================

const API_BASE = window.location.origin;
let deleteUsername = null;

// ============================================================================
// TOAST NOTIFICATIONS
// ============================================================================

function showToast(title, message, type = 'info') {
    const container = document.getElementById('toastContainer');
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.innerHTML = `
        <div class="toast-title">${title}</div>
        <div class="toast-message">${message}</div>
    `;

    container.appendChild(toast);

    // Auto-remove after 5 seconds
    setTimeout(() => {
        toast.style.animation = 'slideInRight 0.3s ease reverse';
        setTimeout(() => toast.remove(), 300);
    }, 5000);
}

// ============================================================================
// MODAL
// ============================================================================

function showDeleteModal(username) {
    deleteUsername = username;
    document.getElementById('deleteUsername').textContent = username;
    document.getElementById('deleteModal').style.display = 'flex';
}

function hideDeleteModal() {
    deleteUsername = null;
    document.getElementById('deleteModal').style.display = 'none';
}

// ============================================================================
// API FUNCTIONS
// ============================================================================

async function fetchBrokerStatus() {
    try {
        const response = await fetch(`${API_BASE}/api/mqtt/status`, {
            credentials: 'include'
        });
        const data = await response.json();

        const statusBadge = document.getElementById('brokerStatus');

        if (data.success && data.broker.active) {
            statusBadge.textContent = 'Online';
            statusBadge.className = 'status-badge status-running';
        } else {
            statusBadge.textContent = 'Offline';
            statusBadge.className = 'status-badge status-stopped';
        }
    } catch (error) {
        console.error('Error fetching broker status:', error);
        const statusBadge = document.getElementById('brokerStatus');
        statusBadge.textContent = 'Error';
        statusBadge.className = 'status-badge status-stopped';
    }
}

async function fetchUsers() {
    const loadingSpinner = document.getElementById('loadingSpinner');
    const errorContainer = document.getElementById('errorContainer');
    const tableContainer = document.getElementById('tableContainer');

    loadingSpinner.style.display = 'block';
    errorContainer.style.display = 'none';
    tableContainer.style.display = 'none';

    try {
        const response = await fetch(`${API_BASE}/api/mqtt/users`, {
            credentials: 'include'
        });
        const data = await response.json();

        loadingSpinner.style.display = 'none';

        if (!data.success) {
            errorContainer.style.display = 'block';
            document.getElementById('errorMessage').textContent = data.error || 'Error desconocido';
            return;
        }

        renderUsersTable(data.users);
        tableContainer.style.display = 'block';

    } catch (error) {
        console.error('Error fetching users:', error);
        loadingSpinner.style.display = 'none';
        errorContainer.style.display = 'block';
        document.getElementById('errorMessage').textContent = 'Error de conexión con el servidor';
    }
}

async function createUser(esp32Id) {
    try {
        const response = await fetch(`${API_BASE}/api/mqtt/users`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            credentials: 'include',
            body: JSON.stringify({ esp32_id: esp32Id })
        });

        const data = await response.json();

        if (data.success) {
            showToast('✅ Usuario creado', `ESP32 ${esp32Id} registrado correctamente`, 'success');
            fetchUsers(); // Refresh list
            return true;
        } else {
            showToast('❌ Error', data.error || 'No se pudo crear el usuario', 'error');
            return false;
        }

    } catch (error) {
        console.error('Error creating user:', error);
        showToast('❌ Error', 'Error de conexión con el servidor', 'error');
        return false;
    }
}

async function deleteUser(username) {
    try {
        const response = await fetch(`${API_BASE}/api/mqtt/users/${username}`, {
            method: 'DELETE',
            credentials: 'include'
        });

        const data = await response.json();

        if (data.success) {
            showToast('✅ Usuario eliminado', `Usuario ${username} eliminado correctamente`, 'success');
            fetchUsers(); // Refresh list
            return true;
        } else {
            showToast('❌ Error', data.error || 'No se pudo eliminar el usuario', 'error');
            return false;
        }

    } catch (error) {
        console.error('Error deleting user:', error);
        showToast('❌ Error', 'Error de conexión con el servidor', 'error');
        return false;
    }
}

// ============================================================================
// RENDER FUNCTIONS
// ============================================================================

function renderUsersTable(users) {
    const tbody = document.getElementById('usersTableBody');
    const userCount = document.getElementById('userCount');
    const emptyState = document.getElementById('emptyState');

    // Filter only ESP32 devices
    const esp32Users = users.filter(u => u.type === 'esp32');

    userCount.textContent = `${esp32Users.length} dispositivo${esp32Users.length !== 1 ? 's' : ''}`;

    if (esp32Users.length === 0) {
        tbody.innerHTML = '';
        emptyState.style.display = 'block';
        return;
    }

    emptyState.style.display = 'none';

    tbody.innerHTML = esp32Users.map(user => `
        <tr>
            <td>
                <strong style="font-family: monospace; font-size: 1.1rem;">${user.username}</strong>
            </td>
            <td>
                <span class="user-type-badge badge-${user.type}">
                    ${getTypeLabel(user.type)}
                </span>
            </td>
            <td>
                <button
                    class="btn btn-danger btn-small"
                    onclick="showDeleteModal('${user.username}')"
                >
                    🗑️ Eliminar
                </button>
            </td>
        </tr>
    `).join('');
}

function getTypeLabel(type) {
    const labels = {
        'esp32': 'ESP32 Device',
        'service': 'Service',
        'admin': 'Admin',
        'other': 'Other'
    };
    return labels[type] || type;
}

// ============================================================================
// EVENT HANDLERS
// ============================================================================

const addUserForm = document.getElementById('addUserForm');
if (addUserForm) {
    addUserForm.addEventListener('submit', async (e) => {
        e.preventDefault();

        const input = document.getElementById('esp32Id');
        const esp32Id = input.value.trim().toUpperCase();
        const addBtn = document.getElementById('addBtn');
        const btnText = addBtn.querySelector('.btn-text');
        const btnLoading = addBtn.querySelector('.btn-loading');

        // Validate format
        if (!/^[A-F0-9]{8}$/.test(esp32Id)) {
            showToast('❌ Formato inválido', 'El ESP32 ID debe tener 8 caracteres hexadecimales', 'error');
            return;
        }

        // Disable button
        addBtn.disabled = true;
        btnText.style.display = 'none';
        btnLoading.style.display = 'inline';

        const success = await createUser(esp32Id);

        // Re-enable button
        addBtn.disabled = false;
        btnText.style.display = 'inline';
        btnLoading.style.display = 'none';

        if (success) {
            input.value = '';
        }
    });
}

const refreshBtn = document.getElementById('refreshBtn');
if (refreshBtn) {
    refreshBtn.addEventListener('click', () => {
        fetchUsers();
        fetchBrokerStatus();
        showToast('🔄 Actualizado', 'Lista de dispositivos actualizada', 'info');
    });
}

// Only add event listeners if elements exist (this page is mqtt_config.html)
const cancelDeleteBtn = document.getElementById('cancelDeleteBtn');
const confirmDeleteBtn = document.getElementById('confirmDeleteBtn');
const deleteModal = document.getElementById('deleteModal');
const esp32IdInput = document.getElementById('esp32Id');

if (cancelDeleteBtn) {
    cancelDeleteBtn.addEventListener('click', hideDeleteModal);
}

if (confirmDeleteBtn) {
    confirmDeleteBtn.addEventListener('click', async () => {
        if (!deleteUsername) return;

        confirmDeleteBtn.disabled = true;
        confirmDeleteBtn.textContent = 'Eliminando...';

        await deleteUser(deleteUsername);

        confirmDeleteBtn.disabled = false;
        confirmDeleteBtn.textContent = 'Eliminar';
        hideDeleteModal();
    });
}

// Close modal on background click
if (deleteModal) {
    deleteModal.addEventListener('click', (e) => {
        if (e.target.id === 'deleteModal') {
            hideDeleteModal();
        }
    });
}

// Auto-uppercase ESP32 ID input
if (esp32IdInput) {
    esp32IdInput.addEventListener('input', (e) => {
        e.target.value = e.target.value.toUpperCase();
    });
}

// ============================================================================
// INITIALIZATION
// ============================================================================

document.addEventListener('DOMContentLoaded', () => {
    // Only initialize if we're on the MQTT Config page
    const usersTableBody = document.getElementById('usersTableBody');
    if (usersTableBody) {
        fetchUsers();
        fetchBrokerStatus();

        // Auto-refresh broker status every 10 seconds
        setInterval(fetchBrokerStatus, 10000);

        // Auto-refresh users every 30 seconds
        setInterval(fetchUsers, 30000);
    }
});
