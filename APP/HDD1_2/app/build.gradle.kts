import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.google.services)
    id("com.google.firebase.crashlytics")
    kotlin("kapt")
}

android {
    namespace = "com.pqsolutions.hdd_monitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pqsolutions.hdd_monitor"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        multiDexEnabled = true

        resValue("string", "default_notification_channel_id", "panel_updates")
        resValue("string", "channel_name", "Estado de Panel")
        resValue("string", "channel_description", "Notificaciones de cambios en el estado del panel")
    }

    signingConfigs {
        create("release") {
            try {
                val keystorePropertiesFile = rootProject.file("keystore.properties")
                val keystoreProperties = Properties()
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))

                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            } catch (e: Exception) {
                println("Signing config not found: ${e.message}")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "HDD Monitor Debug")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "HDD Monitor")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
                "META-INF/maven/**"
            )
            pickFirsts += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties"
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.hilt.work)
    kapt(libs.androidx.hilt.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.firebase.bom.v3280))

    implementation(libs.com.google.firebase.firebase.messaging.ktx2)
    implementation(libs.com.google.firebase.firebase.analytics.ktx)
    implementation(libs.com.google.firebase.firebase.auth.ktx2)
    implementation(libs.com.google.firebase.firebase.firestore.ktx2)
    implementation(libs.com.google.firebase.firebase.functions.ktx)
    implementation(libs.google.firebase.crashlytics.ktx)

    implementation(libs.hilt.android)
    kapt(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    kapt(libs.androidx.hilt.compiler)

    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation("androidx.compose.material3:material3")
    implementation(libs.androidx.runtime)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.ui.tooling)

    implementation(libs.org.eclipse.paho.client.mqttv3)
    implementation("org.eclipse.paho:org.eclipse.paho.android.service:1.1.1") {
        exclude(group = "com.android.support")
        exclude(module = "appcompat-v7")
        exclude(module = "support-v4")
    }

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.test.manifest)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.room.ktx)

    implementation(libs.androidx.navigation.compose.v281)
    implementation(libs.androidx.paging.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.play.services.base)
    implementation(libs.play.services.auth)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx.v286)
    implementation(libs.androidx.activity.ktx)

    implementation(libs.core)
    implementation(libs.calendar)
    implementation(libs.clock)
    implementation(libs.state)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.multidex)

    implementation(libs.play.services.nearby)

    implementation(libs.androidx.localbroadcastmanager)
}

kapt {
    correctErrorTypes = true
    useBuildCache = true
}