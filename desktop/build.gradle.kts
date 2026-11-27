import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.0"
}

// The Windows app's own version, released as "windows-v<version>" (the Android app has its own): a change to
// one doesn't offer an update to the other. MSI needs numbers only, and each release a higher one.
val appVersion = "2.15.0"
// The Linux app's own version, released as "linux-v<version>" by .github/workflows/linux.yml: its own update
// channel, so a Linux release never offers an update to Windows or Android, and theirs never reach Linux.
val linuxVersion = "1.0.0"
// The build runs on the system it packages for (jpackage can't cross-build)
val linuxBuild = System.getProperty("os.name").startsWith("Linux")
val packVersion = if (linuxBuild) linuxVersion else appVersion
version = packVersion

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

// The Android app's engine (config builder, parsers, testers, WARP, ...) compiled as is. The few Android-only
// files get desktop versions under src/main/kotlin (same names and packages).
val sharedSrc = tasks.register<Sync>("sharedSources") {
    from("../app/src/main/java") {
        include("com/netino/vpn/core/**", "com/netino/vpn/data/**")
        exclude("com/netino/vpn/core/XrayCore.kt", "com/netino/vpn/core/HevTunnel.kt", "com/netino/vpn/data/SecureStore.kt")
    }
    into(layout.buildDirectory.dir("shared/kotlin"))
}
val sharedRes = tasks.register<Sync>("sharedResources") {
    from("../app/src/main/res/font") { include("*.ttf"); into("font") }
    // The logo on its white rounded tile (window, tray and the app's own header)
    from("icons/netino_tile.png") { rename { "netino.png" } }
    into(layout.buildDirectory.dir("shared/res"))
}
sourceSets.main {
    kotlin.srcDir(sharedSrc)
    resources.srcDir(sharedRes)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    // QR codes: showing configs to the phone, reading them from the screen or an image
    implementation("com.google.zxing:core:3.5.3")
    // Unit tests of the connection core (state machine, reconnect policy, scoring): no Windows or network needed
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

// Xray-core for this system (xray.exe / xray, without the geo files), from its official release, verified by SHA-256
val xrayZip = if (linuxBuild) Triple(
    "Xray-linux-64.zip",
    "https://github.com/XTLS/Xray-core/releases/download/v26.9.30/Xray-linux-64.zip",
    "f851110beaff16e78d643f0ccfd9524b4a44dfd59bae3e34bb52bba378f7690e",
) else Triple(
    "Xray-windows-64.zip",
    "https://github.com/XTLS/Xray-core/releases/download/v26.9.30/Xray-windows-64.zip",
    "b17a619343c11b89d8faf36278298856749b15c52277d875d32051b33dcda617",
)
val xrayFiles = if (linuxBuild) listOf("xray", "LICENSE") else listOf("xray.exe", "wintun.dll", "LICENSE")
val fetchXray = tasks.register("fetchXray") {
    description = "Downloads and verifies Xray-core into build/xray/windows or build/xray/linux"
    val out = layout.buildDirectory.dir(if (linuxBuild) "xray/linux" else "xray/windows")
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.apply { deleteRecursively(); mkdirs() }
        val zip = layout.buildDirectory.file("xray/${xrayZip.first}").get().asFile
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (!zip.exists() || sha(zip) != xrayZip.third) {
            URI(xrayZip.second).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
            check(sha(zip) == xrayZip.third) { "SHA-256 mismatch for ${xrayZip.first}" }
        }
        ZipFile(zip).use { z ->
            // Not the geo files (27 MB): the Iran lists are downloaded on first start instead
            for (name in xrayFiles) {
                val e = z.getEntry(name) ?: continue
                val f = File(dir, if (name == "LICENSE") "LICENSE-Xray" else name)
                z.getInputStream(e).use { input -> f.outputStream().use { input.copyTo(it) } }
                if (name == "xray") f.setExecutable(true, false)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.netino.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xmx512m")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "Netino"
            packageVersion = packVersion
            description = "Netino VPN"
            vendor = "Netino"
            copyright = "Netino"
            appResourcesRootDir.set(layout.buildDirectory.dir("xray"))
            // jdeps over the app and its libraries; jdk.localedata for Persian dates
            modules("java.naming", "java.instrument", "java.management", "jdk.crypto.ec", "jdk.unsupported", "jdk.localedata")
            windows {
                iconFile.set(file("icons/netino.ico"))
                menuGroup = "Netino"
                shortcut = true
                menu = true
                perUserInstall = true
                dirChooser = true
                // Fixed: installing a new version replaces the old one
                upgradeUuid = "6f1c2a8e-3b7d-4c55-9e0a-8d2f4b7c1e93"
            }
            linux {
                iconFile.set(file("icons/netino_tile.png"))
                packageName = "netino"
                debMaintainer = "netino@users.noreply.github.com"
                menuGroup = "Network"
                appCategory = "net"
                shortcut = true
                debPackageVersion = linuxVersion
            }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}

tasks.matching { it.name.startsWith("prepareAppResources") }.configureEach { dependsOn(fetchXray) }
