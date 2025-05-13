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

exports.sendEventNotification = functions.firestore
    .document("hdd-monitor/accounts/clients/{clientId}/events/{eventId}")
    .onUpdate(async (change, context) => {
        try {
            console.log('🔥 FUNCIÓN DISPARADA - Inicio');
            console.log('📋 Estado del trigger:');
            console.log('- Documento antes existe:', change.before.exists);
            console.log('- Documento después existe:', change.after.exists);
            
            const newData = change.after.exists ? change.after.data() : null;
            const previousData = change.before.exists ? change.before.data() : null;
            const clientId = context.params.clientId;
            const eventId = context.params.eventId;

            if (!change.before.exists) {
                console.log('🆕 Nuevo evento detectado - Será manejado por sendNewEventNotification');
                return null;
            }

            if (!newData) {
                console.log('❌ Documento eliminado');
                return null;
            }

            // Obtener información del cliente
            const clientDoc = await admin.firestore()
                .doc(`hdd-monitor/accounts/clients/${clientId}`)
                .get();
            
            if (!clientDoc.exists) {
                console.log('❌ Cliente no encontrado');
                return null;
            }
            
            const clientName = clientDoc.data().name;

            let actionType, actorId, actorRole;

            if (newData.status === "ACEPTADO" && previousData.status === "PROGRAMADO") {
                actionType = "ACCEPT";
                actorId = newData.acceptedByAccountId;
                actorRole = await determineActorRole(actorId);
                console.log('🔄 Evento aceptado por:', { actorId, actorRole });
            } else if (newData.status === "FINALIZADO" && previousData.status !== "FINALIZADO") {
                actionType = "FINISH";
                actorId = newData.finishedByAccountId;
                actorRole = await determineActorRole(actorId);
                console.log('✅ Evento finalizado por:', { actorId, actorRole });
            } else if (previousData.status === "FINALIZADO" && newData.status === "ACEPTADO") {
                actionType = "REOPEN";
                actorId = newData.reopenedByAccountId;
                actorRole = "admin";
                console.log('🔄 Evento reabierto por admin:', actorId);
            } else if (newData.text !== previousData?.text || 
                      newData.title !== previousData?.title || 
                      newData.type !== previousData?.type) {
                actionType = "EDIT";
                actorId = newData.lastEditByAccountId || newData.createdByAccountId;
                actorRole = await determineActorRole(actorId);
                console.log('📝 Evento editado por:', { actorId, actorRole });
            } else {
                console.log('❌ No hay cambios significativos');
                return null;
            }

            // Verificación de seguridad para los datos del actor
            if (!actorId || !actorRole) {
                console.error('❌ Datos de actor faltantes:', { actorId, actorRole });
                return null;
            }

            console.log('👤 Actor final seleccionado:', { actorId, actorRole });

            // Obtener nombres del actor
            const actorNameForUsers = await getActorName(clientId, actorId, actorRole, false);
            const actorNameForAdmins = await getActorName(clientId, actorId, actorRole, true);

            const messageForUsers = actionType === "ACCEPT" ? 
                `${actorRole === 'admin' ? 'Un administrador' : actorNameForUsers} ha aceptado el evento de ${newData.type}: "${newData.title}"${newData.panelDocName ? ` para el panel "${newData.panelName || newData.panelDocName}"` : ''}` :
                actionType === "FINISH" ? 
                `${actorRole === 'admin' ? 'Un administrador' : actorNameForUsers} ha finalizado el evento de ${newData.type}: "${newData.title}"${newData.panelDocName ? ` para el panel "${newData.panelName || newData.panelDocName}"` : ''}` :
                actionType === "REOPEN" ? 
                `Un administrador ha reabierto el evento de ${newData.type}: "${newData.title}"${newData.panelDocName ? ` para el panel "${newData.panelName || newData.panelDocName}"` : ''}` :
                `${actorRole === 'admin' ? 'Un administrador' : actorNameForUsers} ha actualizado el evento de ${newData.type}: "${newData.title}"${newData.panelDocName ? ` para el panel "${newData.panelName || newData.panelDocName}"` : ''}`;

            const messageForAdmins = actionType === "ACCEPT" ? 
                `${actorNameForAdmins} ha aceptado el evento de ${newData.type}: "${newData.title}"${newData.panelDocName ? ` para el panel "${newData.panelName || newData.panelDocName}"` : ''} - ${clientName}` :
                actionType === "FINISH" ? 
                `${actorNameForAdmins} ha finalizado el evento de ${newData.type}: "${newData.title}"${newData.panelDocName ? ` para el panel "${newData.panelName || newData.panelDocName}"` : ''} - ${clientName}` :
                actionType === "REOPEN" ? 
                `${actorNameForAdmins} ha reabierto el evento de ${newData.type}: "${newData.title}"${newData.panelDocName ? ` para el panel "${newData.panelName || newData.panelDocName}"` : ''} - ${clientName}` :
                `${actorNameForAdmins} ha actualizado el evento de ${newData.type}: "${newData.title}"${newData.panelDocName ? ` para el panel "${newData.panelName || newData.panelDocName}"` : ''} - ${clientName}`;

            const baseMessage = {
                data: {
                    clientDocName: clientId,
                    eventId: eventId,
                    eventType: newData.type,
                    status: newData.status,
                    action: actionType,
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
                                title: `Evento ${newData.type}`,
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
                                title: `${clientName} - Evento ${newData.type}`,
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
            await Promise.all([...userPromises, ...adminPromises]);

            // Guardar la notificación en Firestore y ejecutar limpieza
            const newNotificationRef = await admin.firestore()
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

            // Ejecutar limpieza de notificaciones después de agregar la nueva
            await cleanupNotifications(admin.firestore(), clientId);

            console.log('✅ Notificación guardada y limpieza ejecutada');
            return null;
            
        } catch (error) {
            console.error('❌ Error en la función:', error);
            return null;
        }
    });