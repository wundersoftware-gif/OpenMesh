plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.openmesh.vpn.tun2socks"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Pinned local copy of hev-socks5-tunnel 2.20.0 (MIT). See docs/ADR/0002-tun2socks.md
    // and third_party/hev-socks5-tunnel/LICENSE. SHA-256 of the AAR is recorded there.
    implementation(files("libs/hev-socks5-tunnel-2.20.0.aar"))
    testImplementation(libs.junit)
}
