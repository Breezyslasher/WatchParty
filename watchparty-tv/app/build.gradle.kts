plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.breezyslasher.watchparty"
    // Play requires new apps and updates to target Android 16 (API 36) since 2026-08-31
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.breezyslasher.watchparty"
        minSdk = 21
        targetSdk = 36
        versionCode = 73
        versionName = "0.17.12"
    }

    // Release signing (Play's upload key) from the environment, as the
    // "Android release" workflow sets it from repository secrets; without it a
    // release build is unsigned.
    val keystore = System.getenv("WATCHPARTY_KEYSTORE")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("WATCHPARTY_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("WATCHPARTY_KEY_ALIAS")
            keyPassword = System.getenv("WATCHPARTY_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
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

// No dependencies: framework Activity/Service, org.json and HttpURLConnection.
dependencies {
    testImplementation("junit:junit:4.13.2")
    // local unit tests run against a stubbed android.jar; give them real org.json
    testImplementation("org.json:json:20240303")
}
