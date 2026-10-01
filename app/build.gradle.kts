plugins { id("com.android.application") }

android {
    namespace = "dev.ghost.nearbyim"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.ghost.nearbyim"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false } }
}
