plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jackson.duostock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jackson.duostock"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
}
