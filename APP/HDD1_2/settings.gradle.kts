pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            version("compose-compiler", "1.5.8")
            version("compose-bom", "2024.01.00")

            plugin("android-application", "com.android.application").version("8.2.2")
            plugin("kotlin-android", "org.jetbrains.kotlin.android").version("1.9.22")
            plugin("hilt-android", "com.google.dagger.hilt.android").version("2.50")
            plugin("google-services", "com.google.gms.google-services").version("4.4.2")

            library("androidx-compose-bom", "androidx.compose", "compose-bom").versionRef("compose-bom")
            library("androidx-compose-ui", "androidx.compose.ui", "ui").withoutVersion()
            library("androidx-compose-ui-graphics", "androidx.compose.ui", "ui-graphics").withoutVersion()
            library("androidx-compose-ui-tooling-preview", "androidx.compose.ui", "ui-tooling-preview").withoutVersion()
            library("androidx-compose-material3", "androidx.compose.material3", "material3").version("1.2.0")
            library("androidx-compose-runtime", "androidx.compose.runtime", "runtime").withoutVersion()

            library("androidx-compose-animation", "androidx.compose.animation", "animation").version("1.5.4")
            library("androidx-compose-foundation", "androidx.compose.foundation", "foundation").withoutVersion()
            library("androidx-compose-material-icons-extended", "androidx.compose.material", "material-icons-extended").withoutVersion()

            library("androidx-core-ktx", "androidx.core", "core-ktx").version("1.12.0")
            library("androidx-lifecycle-runtime-ktx", "androidx.lifecycle", "lifecycle-runtime-ktx").version("2.7.0")
            library("androidx-activity-compose", "androidx.activity", "activity-compose").version("1.8.2")
            library("androidx-room-ktx", "androidx.room", "room-ktx").version("2.6.1")

            // Firebase
            library("firebase-bom-v3280", "com.google.firebase", "firebase-bom").version("32.8.0")
            library("firebase-analytics-ktx", "com.google.firebase", "firebase-analytics-ktx")
            library("com-google-firebase-firebase-auth-ktx", "com.google.firebase", "firebase-auth-ktx")
            library("com-google-firebase-firebase-firestore-ktx", "com.google.firebase", "firebase-firestore-ktx")
            library("com-google-firebase-firebase-messaging-ktx", "com.google.firebase", "firebase-messaging-ktx")
            library("firebase-functions-ktx", "com.google.firebase", "firebase-functions-ktx")
            library("firebase-crashlytics-ktx", "com.google.firebase", "firebase-crashlytics-ktx")

            // Hilt
            library("hilt-android", "com.google.dagger", "hilt-android").version("2.50")
            library("hilt-android-compiler", "com.google.dagger", "hilt-android-compiler").version("2.50")
            library("androidx-hilt-navigation-compose", "androidx.hilt", "hilt-navigation-compose").version("1.2.0")
            library("androidx-hilt-work", "androidx.hilt", "hilt-work").version("1.2.0")
            library("androidx-hilt-compiler", "androidx.hilt", "hilt-compiler").version("1.2.0")

            // Navigation & Paging
            library("androidx-navigation-compose-v281", "androidx.navigation", "navigation-compose").version("2.8.1")
            library("androidx-paging-compose", "androidx.paging", "paging-compose").version("3.3.2")

            // Coroutines
            library("kotlinx-coroutines-android", "org.jetbrains.kotlinx", "kotlinx-coroutines-android").version("1.7.3")
            library("kotlinx-coroutines-play-services", "org.jetbrains.kotlinx", "kotlinx-coroutines-play-services").version("1.7.3")

            // Testing
            library("junit", "junit", "junit").version("4.13.2")
            library("mockito-core", "org.mockito", "mockito-core").version("5.7.0")
            library("kotlinx-coroutines-test", "org.jetbrains.kotlinx", "kotlinx-coroutines-test").version("1.7.3")
            library("androidx-test-ext-junit", "androidx.test.ext", "junit").version("1.1.5")
            library("androidx-test-espresso-core", "androidx.test.espresso", "espresso-core").version("3.5.1")

            // Google Play Services
            library("play-services-base", "com.google.android.gms", "play-services-base").version("18.5.0")
            library("play-services-auth", "com.google.android.gms", "play-services-auth").version("21.2.0")

            // WorkManager
            library("androidx-work-runtime-ktx", "androidx.work", "work-runtime-ktx").version("2.9.1")

            // Lifecycle
            library("androidx-lifecycle-viewmodel-ktx", "androidx.lifecycle", "lifecycle-viewmodel-ktx").version("2.8.6")
            library("androidx-lifecycle-runtime-ktx-v286", "androidx.lifecycle", "lifecycle-runtime-ktx").version("2.8.6")
            library("androidx-activity-ktx", "androidx.activity", "activity-ktx").version("1.9.2")

            // Date & Time Pickers
            library("core", "com.maxkeppeler.sheets-compose-dialogs", "core").version("1.2.0")
            library("calendar", "com.maxkeppeler.sheets-compose-dialogs", "calendar").version("1.2.0")
            library("clock", "com.maxkeppeler.sheets-compose-dialogs", "clock").version("1.2.0")
            library("state", "com.maxkeppeler.sheets-compose-dialogs", "state").version("1.2.0")

            // DataStore
            library("androidx-datastore-preferences", "androidx.datastore", "datastore-preferences").version("1.1.1")

            // Multidex
            library("androidx-multidex", "androidx.multidex", "multidex").version("2.0.1")
        }
    }
}

rootProject.name = "HDD1_2"
include(":app")