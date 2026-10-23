plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.cameracast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.cameracast"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("fixed") {
            // keystore คงที่ ทำให้ APK ทุกเวอร์ชันติดตั้งทับกันได้ (ไม่ต้องลบแอปเดิม)
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "cameracast"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
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
