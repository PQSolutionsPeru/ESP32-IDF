const functions = require("firebase-functions");
const admin = require("firebase-admin");

if (!admin.apps.length) {
    admin.initializeApp();
}

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

async function determineActorRole(accountId) {
    try {
        // Primero verificar si es admin
        const adminDoc = await admin.firestore()
            .doc(`hdd-monitor/accounts/admins/${accountId}`)
            .get();
        
        if (adminDoc.exists) {
            return 'admin';
        }

        // Si no es admin, buscar en todos los clientes
        const clientsSnapshot = await admin.firestore()
            .collection('hdd-monitor/accounts/clients')
            .get();

        for (const clientDoc of clientsSnapshot.docs) {
            const userDoc = await admin.firestore()
                .doc(`hdd-monitor/accounts/clients/${clientDoc.id}/users/${accountId}`)
                .get();
            
            if (userDoc.exists) {
                return 'user';
            }
        }

        return null;
    } catch (error) {
        console.error('Error determinando rol:', error);
        return null;
    }
}

async function getActorName(clientId, accountId, role, isForAdmin = false) {
    try {
        if (role === 'admin') {
            if (isForAdmin) {
                const adminDoc = await admin.firestore()
                    .doc(`hdd-monitor/accounts/admins/${accountId}`)
                    .get();
                return adminDoc.exists ? adminDoc.data().name : 'Administrador';
            } else {
                return 'Administrador';
            }
        } else {
            const userDoc = await admin.firestore()
                .doc(`hdd-monitor/accounts/clients/${clientId}/users/${accountId}`)
                .get();
            return userDoc.exists ? userDoc.data().name : 'Usuario';
        }
    } catch (error) {
        console.error('Error obteniendo nombre del actor:', error);
        return role === 'admin' ? 'Administrador' : 'Usuario';
    }
}

exports.sendNewEventNotification = functions.firestore
    .document("hdd-monitor/accounts/clients/{clientId}/events/{eventId}")
    .onCreate(async (snap, context) => {
        try {
            console.log('🆕 NUEVO EVENTO DETECTADO');
            const newData = snap.data();
            const clientId = context.params.clientId;
            const eventId = context.params.eventId;

            console.log('📋 Datos del nuevo evento:', {
                clientId,
                eventId,
                actorId: newData.createdByAccountId,
                type: newData.type,
                title: newData.title
            });

            // Verificar el ID del creador
            if (!newData.createdByAccountId) {
                console.error('❌ ID del creador faltante');
                return null;
            }

            // Determinar el rol del creador
            const actorId = newData.createdByAccountId;
            const actorRole = await determineActorRole(actorId);

            if (!actorRole) {
                console.error('❌ No se pudo determinar el rol del creador');
                return null;
            }

            console.log('👤 Rol del creador determinado:', { actorId, actorRole });

            // Obtener información del cliente
            const clientDoc = await admin.firestore()
                .doc(`hdd-monitor/accounts/clients/${clientId}`)
                .get();
            
            if (!clientDoc.exists) {
                console.log('❌ Cliente no encontrado');
                return null;
            }
            
            const clientName = clientDoc.data().name;

            console.log('👤 Obteniendo nombres de actores...');
            // Obtener nombres del actor
            const actorNameForUsers = await getActorName(clientId, actorId, actorRole, false);
            const actorNameForAdmins = await getActorName(clientId, actorId, actorRole, true);

            const messageForUsers = `${actorRole === 'admin' ? 'Un administrador' : actorNameForUsers} ha creado un nuevo evento de ${newData.type}: "${newData.title}"${newData.panelDocName ? ` para el panel "${newData.panelName || newData.panelDocName}"` : ''}`;
            const messageForAdmins = `${actorNameForAdmins} ha creado un nuevo evento de ${newData.type}: "${newData.title}"${newData.panelDocName ? ` para el panel "${newData.panelName || newData.panelDocName}"` : ''} - ${clientName}`;

            const baseMessage = {
                data: {
                    clientDocName: clientId,
                    eventId: eventId,
                    eventType: newData.type,
                    status: newData.status,
                    action: "CREATE",
                    ...(newData.panelDocName && {
                        panelDocName: newData.panelDocName,
                        panelName: newData.panelName || ''
                    })
                },
                android: {
                    priority: 'high',
                    notification: {
                        channelId: 'event_notifications',
                        priority: 'high',
                        sound: 'default',
                        visibility: 'public'
                    }
                }
            };

            // Obtener TODOS los usuarios del cliente actual
            console.log('👥 Obteniendo usuarios del cliente...');
            const usersSnapshot = await admin.firestore()
                .collection(`hdd-monitor/accounts/clients/${clientId}/users`)
                .get();

            console.log('📊 Usuarios del cliente encontrados:', usersSnapshot.size);

            // Preparar notificaciones para usuarios
            const userPromises = usersSnapshot.docs
                .filter(doc => doc.data().fcmToken)
                .map(async (doc) => {
                    try {
                        const message = {
                            token: doc.data().fcmToken,
                            notification: {
                                title: `Nuevo Evento - ${newData.type}`,
                                body: messageForUsers
                            },
                            ...baseMessage
                        };
                        await admin.messaging().send(message);
                        console.log('✅ Notificación enviada a usuario:', doc.id);
                    } catch (error) {
                        console.error('❌ Error enviando notificación a usuario:', error);
                    }
                });

            // Obtener TODOS los administradores
            console.log('👥 Obteniendo administradores...');
            const adminSnapshot = await admin.firestore()
                .collection('hdd-monitor/accounts/admins')
                .get();

            console.log('📊 Admins encontrados:', adminSnapshot.size);

            // Preparar notificaciones para admins
            const adminPromises = adminSnapshot.docs
                .filter(doc => doc.data().fcmToken)
                .map(async (doc) => {
                    try {
                        const message = {
                            token: doc.data().fcmToken,
                            notification: {
                                title: `${clientName} - Nuevo Evento ${newData.type}`,
                                body: messageForAdmins
                            },
                            ...baseMessage
                        };
                        await admin.messaging().send(message);
                        console.log('✅ Notificación enviada a admin:', doc.id);
                    } catch (error) {
                        console.error('❌ Error enviando notificación a admin:', error);
                    }
                });

            // Esperar a que todas las notificaciones se envíen
            console.log('⏳ Enviando todas las notificaciones...');
            await Promise.all([...userPromises, ...adminPromises]);

            // Guardar la notificación en Firestore
            console.log('💾 Guardando notificación en Firestore...');
            await admin.firestore()
                .collection(`hdd-monitor/accounts/clients/${clientId}/notifications`)
                .add({
                    date_time: new Date().toLocaleString('es-ES', {
                        day: '2-digit',
                        month: '2-digit',
                        year: 'numeric',
                        hour: '2-digit',
                        minute: '2-digit'
                    }).replace(/(\d{2})\/(\d{2})\/(\d{4}) (\d{2}):(\d{2})/, '$1/$2/$3, $4:$5'),
                    message: messageForUsers,
                    eventId,
                    eventType: newData.type,
                    status: newData.status,
                    isRead: false,
                    timestamp: new Date().getTime(), // Añadir timestamp
                    lastUpdate: admin.firestore.FieldValue.serverTimestamp(),
                    ...(newData.panelDocName && { 
                        panelDocName: newData.panelDocName,
                        panelName: newData.panelName 
                    })
                });

            // Ejecutar limpieza de notificaciones
            await cleanupNotifications(admin.firestore(), clientId);

            console.log('✅ Proceso completado exitosamente');
            return null;
            
        } catch (error) {
            console.error('❌ Error en la función:', error);
            return null;
        }
    });