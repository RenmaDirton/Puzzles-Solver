plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.puzzlesolver"
    compileSdk = 34

    // One fixed key for every build, so a new APK installs over the old one
    // (keeps permissions and the accessibility setting) instead of needing an uninstall first.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.puzzlesolver"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "0.9"

        // Phone target: 64-bit ARM only
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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
