const functions = require("firebase-functions");
const admin = require("firebase-admin");
admin.initializeApp();

async function cleanupNotifications(db, clientId = null) {
    try {
        // Limpiar notificaciones de cliente si se proporciona clientId
        if (clientId) {
            const notificationsRef = db.collection(`hdd-monitor/accounts/clients/${clientId}/notifications`);
            const snapshot = await notificationsRef
                .orderBy('date_time', 'desc')
                .get();

            if (snapshot.size > 20) {
                const batch = db.batch();
                const docsToDelete = snapshot.docs.slice(20);
                
                docsToDelete.forEach(doc => {
                    batch.delete(doc.ref);
                });

                await batch.commit();
                console.log(`Eliminadas ${docsToDelete.length} notificaciones antiguas del cliente ${clientId}`);
            }
        }
    } catch (error) {
        console.error('Error en cleanup:', error);
    }
}

exports.sendRelayNotification = functions.firestore
    .document("hdd-monitor/accounts/clients/{clientId}/panels/{panelId}/relays/{relayId}")
    .onUpdate(async (change, context) => {
        const newValue = change.after.data();
        const previousValue = change.before.data();

        if (newValue.status !== previousValue.status) {
            const clientDocName = context.params.clientId;
            const panelDocName = context.params.panelId;
            const relayName = context.params.relayId;

            // Obtener información del panel
            const panelDoc = await admin.firestore()
                .doc(`hdd-monitor/accounts/clients/${clientDocName}/panels/${panelDocName}`)
                .get();
            const panelName = panelDoc.data().name;

            // Obtener información del cliente
            const clientDoc = await admin.firestore()
                .doc(`hdd-monitor/accounts/clients/${clientDocName}`)
                .get();
            const clientName = clientDoc.data().name;

            // Preparar mensaje de notificación
            const message = `El relay ${relayName} del panel "${panelName}" ha cambiado de ${previousValue.status} a ${newValue.status}`;

            // 1. Crear la notificación en Firestore
            const now = new Date();
            const notificationData = {
                panelDocName: panelDocName,
                relayName: relayName,
                message: message,
                date_time: now.toLocaleString('es-ES', { 
                    day: '2-digit',
                    month: '2-digit',
                    year: 'numeric',
                    hour: '2-digit',
                    minute: '2-digit'
                }).replace(/(\d{2})\/(\d{2})\/(\d{4}) (\d{2}):(\d{2})/, '$1/$2/$3, $4:$5'),
                isRead: false,
                timestamp: now.getTime(), // Añadir timestamp
                lastUpdate: admin.firestore.FieldValue.serverTimestamp()
            };

            // Guardar notificación
            await admin.firestore()
                .collection(`hdd-monitor/accounts/clients/${clientDocName}/notifications`)
                .add(notificationData);

            // 2. Enviar notificación push a usuarios del cliente
            const userQuerySnapshot = await admin.firestore()
                .collection(`hdd-monitor/accounts/clients/${clientDocName}/users`)
                .get();
            
            const messagePayload = {
                notification: {
                    title: `${clientName} - Cambio de Estado`,
                    body: message,
                },
                data: {
                    clientDocName: clientDocName,
                    panelDocName: panelDocName,
                    relayName: relayName,
                    oldStatus: previousValue.status,
                    newStatus: newValue.status
                },
                android: {
                    priority: 'high',
                    notification: {
                        channelId: 'relay_status',
                        priority: 'high',
                        sound: 'default',
                        visibility: 'public'
                    }
                }
            };

            for (const userDoc of userQuerySnapshot.docs) {
                const userData = userDoc.data();
                if (userData.fcmToken) {
                    try {
                        messagePayload.token = userData.fcmToken;
                        await admin.messaging().send(messagePayload);
                        console.log('Notificación enviada exitosamente a usuario');
                    } catch (error) {
                        console.error('Error al enviar notificación a usuario:', error);
                    }
                }
            }

            // 3. Enviar notificación push a administradores
            const adminQuerySnapshot = await admin.firestore()
                .collection('hdd-monitor/accounts/admins')
                .get();

            const adminMessagePayload = {
                ...messagePayload,
                notification: {
                    title: "Cambio de Estado de Relay",
                    body: `${message} (Cliente: ${clientName})`
                }
            };

            for (const adminDoc of adminQuerySnapshot.docs) {
                const adminData = adminDoc.data();
                if (adminData.fcmToken) {
                    try {
                        adminMessagePayload.token = adminData.fcmToken;
                        await admin.messaging().send(adminMessagePayload);
                        console.log('Notificación enviada exitosamente a admin');
                    } catch (error) {
                        console.error('Error al enviar notificación a admin:', error);
                    }
                }
            }

            // 4. Ejecutar limpieza de notificaciones
            await cleanupNotifications(admin.firestore(), clientDocName);
        }

        return null;
    });