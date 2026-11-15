plugins {
    id("com.android.application")
}

android {
    namespace = "me.haujet.btprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "me.haujet.btprobe"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
