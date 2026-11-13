plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.watchparty.sessionprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.watchparty.sessionprobe"
        minSdk = 21
        targetSdk = 35
        versionCode = 5
        versionName = "1.3.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No dependencies: plain framework Activity, no AndroidX.
