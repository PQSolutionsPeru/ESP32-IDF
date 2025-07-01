package com.pqsolutions.hdd_monitor.di

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.pqsolutions.hdd_monitor.config.FirebaseConfig24x7
import com.pqsolutions.hdd_monitor.data.AuthRepository
import com.pqsolutions.hdd_monitor.data.ClientRepository
import com.pqsolutions.hdd_monitor.data.EventRepository
import com.pqsolutions.hdd_monitor.data.NotificationRepository
import com.pqsolutions.hdd_monitor.data.PanelRepository
import com.pqsolutions.hdd_monitor.data.RelayControlRepository
import com.pqsolutions.hdd_monitor.data.UserPreferences
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.data.manager.ListenerManager
import com.pqsolutions.hdd_monitor.esp32.ESP32Repository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context

    // *** ELIMINADAS: provideFirebaseAuth, provideFirebaseFirestore, provideFirebaseMessaging ***
    // Estas ahora las proporciona FirebaseModule

    @Provides
    @Singleton
    fun provideUserPreferences(
        @ApplicationContext context: Context
    ): UserPreferences {
        return UserPreferences(context)
    }

    @Provides
    @Singleton
    fun provideAuthRepository(
        firebaseAuth: FirebaseAuth,
        firestore: FirebaseFirestore,
        userPreferences: UserPreferences
    ): AuthRepository {
        return AuthRepository(firebaseAuth, firestore, userPreferences)
    }

    @Provides
    @Singleton
    fun provideUserRepository(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth
    ): UserRepository {
        return UserRepository(firestore, auth)
    }

    @Provides
    @Singleton
    fun provideClientRepository(
        firestore: FirebaseFirestore
    ): ClientRepository {
        return ClientRepository(firestore)
    }

    @Provides
    @Singleton
    fun provideESP32Repository(
        firestore: FirebaseFirestore
    ): ESP32Repository {
        return ESP32Repository(firestore)
    }

    @Provides
    @Singleton
    fun providePanelRepository(
        firestore: FirebaseFirestore,
        esp32Repository: ESP32Repository
    ): PanelRepository {
        return PanelRepository(firestore, esp32Repository)
    }

    @Provides
    @Singleton
    fun provideRelayControlRepository(
        firestore: FirebaseFirestore,
        esp32Repository: ESP32Repository
    ): RelayControlRepository {
        return RelayControlRepository(firestore, esp32Repository)
    }

    @Provides
    @Singleton
    fun provideNotificationRepository(
        firestore: FirebaseFirestore,
        userRepository: UserRepository
    ): NotificationRepository {
        return NotificationRepository(firestore, userRepository)
    }

    @Provides
    @Singleton
    fun provideEventRepository(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth
    ): EventRepository {
        return EventRepository(firestore, auth)
    }

    @Provides
    @Singleton
    fun provideListenerManager(
        panelRepository: PanelRepository,
        relayControlRepository: RelayControlRepository,
        notificationRepository: NotificationRepository,
        eventRepository: EventRepository,
        esp32Repository: ESP32Repository,
        firestore: FirebaseFirestore
    ): ListenerManager {
        return ListenerManager(
            panelRepository,
            relayControlRepository,
            notificationRepository,
            eventRepository,
            esp32Repository,
            firestore
        )
    }

    @Provides
    @Singleton
    fun provideFirebaseConfig24x7(
        @ApplicationContext context: Context,
        listenerManager: ListenerManager
    ): FirebaseConfig24x7 {
        return FirebaseConfig24x7(context, listenerManager)
    }
}