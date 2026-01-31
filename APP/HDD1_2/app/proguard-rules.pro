# =====================================================
# REGLAS DE PROGUARD BALANCEADAS PARA APLICACIÓN CRÍTICA
# Protección del funcionamiento + APK optimizado
# =====================================================

# =====================================================
# TU CÓDIGO - PROTECCIÓN ABSOLUTA
# =====================================================
# Mantener TODO el código de la aplicación sin modificación
-keep class com.pqsolutions.hdd_monitor.** { *; }
-keepclassmembers class com.pqsolutions.hdd_monitor.** { *; }
-keepnames class com.pqsolutions.hdd_monitor.** { *; }

# Información para debugging de crashes
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# =====================================================
# FIREBASE - PROTECCIÓN INTELIGENTE
# =====================================================
# Proteger solo las clases que Firebase necesita para funcionar
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Firebase general
-keep class com.google.firebase.FirebaseApp { *; }
-keep class com.google.firebase.FirebaseOptions { *; }

# Firestore - Solo lo esencial
-keep class com.google.firebase.firestore.FirebaseFirestore { *; }
-keep class com.google.firebase.firestore.DocumentReference { *; }
-keep class com.google.firebase.firestore.CollectionReference { *; }
-keep class com.google.firebase.firestore.Query { *; }
-keep class com.google.firebase.firestore.DocumentSnapshot { *; }
-keep class com.google.firebase.firestore.QuerySnapshot { *; }
-keep class com.google.firebase.firestore.FirebaseFirestoreException { *; }
-keep class com.google.firebase.firestore.EventListener { *; }
-keep class com.google.firebase.firestore.ListenerRegistration { *; }
-keep class com.google.firebase.firestore.FieldValue { *; }
-keep class com.google.firebase.firestore.WriteBatch { *; }
-keep class com.google.firebase.Timestamp { *; }

# Firebase Auth - Solo lo esencial
-keep class com.google.firebase.auth.FirebaseAuth { *; }
-keep class com.google.firebase.auth.FirebaseUser { *; }

# Firebase Messaging - Solo lo esencial
-keep class com.google.firebase.messaging.FirebaseMessaging { *; }
-keep class com.google.firebase.messaging.RemoteMessage { *; }
-keep class com.google.firebase.messaging.RemoteMessage$Notification { *; }
-keep class * extends com.google.firebase.messaging.FirebaseMessagingService {
    *;
}

# Firebase Crashlytics
-keep class com.google.firebase.crashlytics.FirebaseCrashlytics { *; }

# Firebase Functions
-keep class com.google.firebase.functions.FirebaseFunctions { *; }
-keep class com.google.firebase.functions.HttpsCallableReference { *; }

# Advertencias de Firebase que podemos ignorar
-dontwarn com.google.firebase.**

# =====================================================
# HILT - PROTECCIÓN ESENCIAL
# =====================================================
-keep class dagger.hilt.android.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.Module class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }

# =====================================================
# MQTT - PROTECCIÓN COMPLETA
# =====================================================
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**

# =====================================================
# KOTLIN Y COROUTINES - PROTECCIÓN MÍNIMA
# =====================================================
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlin.**
-dontwarn kotlinx.**

# =====================================================
# ANDROIDX - MÍNIMO NECESARIO
# =====================================================
# Lifecycle
-keep class * extends androidx.lifecycle.ViewModel {
    <init>();
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

# Compose - Solo runtime crítico
-keep class androidx.compose.runtime.** { *; }

# Navigation
-keepnames class androidx.navigation.fragment.NavHostFragment

# Hilt
-keep class androidx.hilt.** { *; }

# Work Manager
-keep class androidx.work.Worker { *; }
-keep class androidx.work.WorkerParameters { *; }
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# =====================================================
# GOOGLE PLAY SERVICES - MÍNIMO
# =====================================================
-keep class com.google.android.gms.common.ConnectionResult { *; }
-keep class com.google.android.gms.common.GooglePlayServicesUtil { *; }
-keep class com.google.android.gms.auth.api.signin.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-dontwarn com.google.android.gms.**

# =====================================================
# SERIALIZACIÓN - SOLO LO NECESARIO
# =====================================================
# Constructores vacíos (Firebase)
-keepclassmembers class * {
    public <init>();
}

# Getters y setters (Firebase)
-keepclassmembers class * {
    public void set*(***);
    public *** get*();
    public boolean is*();
}

# Enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# =====================================================
# COMPONENTES DE ANDROID
# =====================================================
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application

# =====================================================
# NETWORKING
# =====================================================
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# =====================================================
# SHEETS COMPOSE DIALOGS
# =====================================================
-keep class com.maxkeppeler.sheets.** { *; }

# =====================================================
# WARNINGS SEGUROS DE IGNORAR
# =====================================================
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn sun.misc.**
-dontwarn java.lang.management.**
-dontwarn java.beans.**
-dontwarn javax.lang.model.**

# =====================================================
# OPTIMIZACIÓN
# =====================================================
# NO ofuscar (para debugging)
-dontobfuscate

# Permitir optimización y eliminación de código no usado
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# =====================================================
# FIN DE REGLAS
# =====================================================
