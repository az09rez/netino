import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.netino.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.netino.vpn"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "2.0.5"
        // Only ship ABIs that libv2ray.aar provides
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Release signing key lives in keystore.properties (never commit it)
    val ksProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (ksProps != null) create("release") {
            storeFile = rootProject.file("app/" + ksProps.getProperty("storeFile"))
            storePassword = ksProps.getProperty("storePassword")
            keyAlias = ksProps.getProperty("keyAlias")
            keyPassword = ksProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            if (ksProps != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { localeFilters += listOf("en", "fa") }
    packaging { jniLibs { useLegacyPackaging = true } }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Prebuilt native engines are not stored in git: they are downloaded from their official releases
// on first build and verified by SHA-256 (pinned versions, so builds are reproducible).
val nativeLibs = listOf(
    Triple("libv2ray.aar",
        "https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.9.30/libv2ray.aar",
        "cf71680b776b9ca583747ba652f816b047a655eab875d8951e6141636d88bbd6"),
    Triple("hev-socks5-tunnel.aar",
        "https://github.com/heiher/hev-socks5-tunnel/releases/download/2.18.0/hev-socks5-tunnel.aar",
        "15ec8ed121663b562c99caa5bb602d1009f24e5b09e733438b81988f12feaaab"),
)

val fetchNativeLibs by tasks.registering {
    description = "Downloads and verifies libv2ray.aar and hev-socks5-tunnel.aar into app/libs"
    val libsDir = layout.projectDirectory.dir("libs").asFile
    outputs.files(nativeLibs.map { File(libsDir, it.first) })
    doLast {
        libsDir.mkdirs()
        for ((name, url, sha) in nativeLibs) {
            val f = File(libsDir, name)
            fun hash() = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { b -> "%02x".format(b) }
            if (f.exists() && hash() == sha) continue
            logger.lifecycle("Downloading $name …")
            URI(url).toURL().openStream().use { input -> f.outputStream().use { out -> input.copyTo(out) } }
            check(hash() == sha) { f.delete(); "SHA-256 mismatch for $name" }
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchNativeLibs) }

dependencies {
    // Xray core (VLESS / VMess / Trojan / Shadowsocks / Reality / Hysteria2 ...)
    // Download libv2ray.aar from https://github.com/2dust/AndroidLibXrayLite/releases and put it in app/libs
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))


    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    // QR code import (camera only used while the scanner is open)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Background auto-update of subscriptions
    implementation("androidx.work:work-runtime-ktx:2.10.1")
}
