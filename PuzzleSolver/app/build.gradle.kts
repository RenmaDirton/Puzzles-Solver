plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.puzzlesolver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.puzzlesolver"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

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
