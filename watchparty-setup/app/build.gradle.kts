plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.watchparty.setup"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.watchparty.setup"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Bundle the Watch Party APK it installs: build ../watchparty-tv first
// (./gradlew assembleDebug there), then this copies it into assets.
val bundleWatchParty by tasks.registering(Copy::class) {
    from("../../watchparty-tv/app/build/outputs/apk/debug") { include("*.apk") }
    into("src/main/assets")
    rename { "WatchPartyTV.apk" }
}
tasks.named("preBuild") { dependsOn(bundleWatchParty) }
