# Keep data and domain classes
-keep class com.pqsolutions.hdd_monitor.data.** { *; }
-keep class com.pqsolutions.hdd_monitor.domain.** { *; }

# Keep Firebase classes
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Keep Hilt classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel

# Keep Kotlin Coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep Compose classes
-keep class androidx.compose.** { *; }
-keepclassmembers class * extends androidx.compose.ui.node.LayoutNode {
    public <init>(...);
}

# Keep Google Play Services
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Keep Constructor without arguments in UserRepository.kt
-keepclassmembers class com.pqsolutions.hdd_monitor.data.UserData {
    <init>();
}

# Keep Retrofit and OkHttp
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}