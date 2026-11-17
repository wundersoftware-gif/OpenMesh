plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.openmesh.testclient"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openmesh.testclient"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
