package com.pqsolutions.hdd_monitor.di

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.pqsolutions.hdd_monitor.data.EventRepository
import com.pqsolutions.hdd_monitor.data.NotificationRepository
import com.pqsolutions.hdd_monitor.data.PanelRepository
import com.pqsolutions.hdd_monitor.data.UserPreferences
import com.pqsolutions.hdd_monitor.data.UserRepository
import com.pqsolutions.hdd_monitor.esp32.ESP32Repository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideApplicationContext(@ApplicationContext context: Context): Context {
        return context
    }

    @Provides
    @Singleton
    fun providePanelRepository(
        firestore: FirebaseFirestore,
        esp32Repository: ESP32Repository
    ): PanelRepository = PanelRepository(firestore, esp32Repository)

    // Eliminamos el proveedor de EventNotificationScheduler
    // @Provides
    // @Singleton
    // fun provideEventNotificationScheduler(
    //     @ApplicationContext context: Context,
    //     firestore: FirebaseFirestore,
    //     userRepository: UserRepository
    // ): EventNotificationScheduler {
    //     return EventNotificationScheduler(context, firestore, userRepository)
    // }

    @Provides
    @Singleton
    fun provideESP32Repository(
        firestore: FirebaseFirestore
    ): ESP32Repository {
        return ESP32Repository(firestore)
    }

    @Provides
    @Singleton
    fun provideUserPreferences(@ApplicationContext context: Context): UserPreferences {
        return UserPreferences(context)
    }

    @Provides
    @Singleton
    @Named("HddMonitorPrefs")
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("HddMonitorPrefs", Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    fun provideEventRepository(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth,
        // Eliminamos la dependencia de eventNotificationScheduler
        // eventNotificationScheduler: EventNotificationScheduler
    ): EventRepository {
        // Modificamos el constructor para que no use eventNotificationScheduler
        return EventRepository(firestore, auth)
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
    fun provideNotificationRepository(
        firestore: FirebaseFirestore,
        userRepository: UserRepository
    ): NotificationRepository {
        return NotificationRepository(firestore, userRepository)
    }
}