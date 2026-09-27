plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.radvoice.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.radvoice.keyboard"
        minSdk = 28          // Android 9+
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
// No third-party dependencies: nothing leaves the phone except via the system speech service.
