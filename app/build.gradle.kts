plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// The debug build captures the bundled lab traffic client by default. Building
// with -PopenmeshTarget=com.whatsapp produces an otherwise identical,
// debug-signed APK that captures WhatsApp instead, so the same code can be
// exercised against the real target app without a signing keystore.
val debugTargetPackage: String =
    providers.gradleProperty("openmeshTarget").getOrElse("com.openmesh.testclient")
val debugCapturesLab: Boolean = debugTargetPackage == "com.openmesh.testclient"

require(Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""").matches(debugTargetPackage)) {
    "invalid openmeshTarget package name: $debugTargetPackage"
}

android {
    namespace = "com.openmesh.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openmesh.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-p2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = if (debugCapturesLab) "-debug" else "-debug-whatsapp"
            // Lab builds capture the bundled traffic generator; the WhatsApp
            // variant captures the real target package instead.
            buildConfigField("String", "OPENMESH_TARGET_PACKAGE", "\"$debugTargetPackage\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "OPENMESH_TARGET_PACKAGE", "\"com.whatsapp\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = false
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":vpn:android"))
    implementation(project(":gateway"))
    implementation(project(":transport"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
