plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mirador.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mirador.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Scanner de QR code Google (pas de permission caméra nécessaire)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
