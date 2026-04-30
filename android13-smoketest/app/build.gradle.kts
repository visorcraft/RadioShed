plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.smoketest"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.example.smoketest"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
