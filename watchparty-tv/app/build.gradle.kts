plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.breezyslasher.watchparty"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.breezyslasher.watchparty"
        minSdk = 21
        targetSdk = 35
        versionCode = 60
        versionName = "0.16"
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
