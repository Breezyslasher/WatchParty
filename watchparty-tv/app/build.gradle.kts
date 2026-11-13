plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.watchparty.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.watchparty.tv"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
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
