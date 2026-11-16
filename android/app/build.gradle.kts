plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.slipe.jarvis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.slipe.jarvis"
        minSdk = 26
        targetSdk = 33
        versionCode = 2
        versionName = "2.2"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("jarvis.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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

dependencies {
}
