package com.netino.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Each app has its own update channel on the project's GitHub Releases, so a release of one never offers (or
 * blocks) an update to another:
 *  - Android: "v<version>" with the APK (the phone's updater, app/.../service/Updater.kt)
 *  - Windows: "windows-v<version>" with Netino-<version>-windows-x64.msi / -portable.zip and SHA256SUMS-windows.txt
 *  - Linux:   "linux-v<version>" with Netino-<version>-linux-x64.deb / .tar.gz and SHA256SUMS-linux.txt
 * Each desktop app also has its own version number (desktop/build.gradle.kts: appVersion, linuxVersion).
 */
data class UpdateChannel(
    /** Tag prefix this channel accepts; null = any tag (Windows: older releases carried both apps under "v"). */
    val tagPrefix: String?,
    /** File names end with this; the version is what sits between "Netino-" and it. */
    val installedSuffix: String,
    val portableSuffix: String,
    val sums: String,
    /** Refuse a download that the sums file doesn't vouch for (missing file or entry). */
    val strict: Boolean = false,
) {
    fun suffix(portable: Boolean) = if (portable) portableSuffix else installedSuffix

    /** The newest release of this channel above [local] in GitHub's releases list (JSON), or null. */
    fun pick(releasesJson: String, local: String, portable: Boolean): Updater.Release? {
        val suffix = suffix(portable)
        return Json.parseToJsonElement(releasesJson).jsonArray.map { it.jsonObject }
            .filter { it["draft"]?.jsonPrimitive?.content != "true" && it["prerelease"]?.jsonPrimitive?.content != "true" }
            .filter { o -> tagPrefix == null || o["tag_name"]?.jsonPrimitive?.content?.startsWith(tagPrefix) == true }
            .mapNotNull { o ->
                val urls = o["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
                    .associate { it["name"]!!.jsonPrimitive.content to it["browser_download_url"]!!.jsonPrimitive.content }
                // Files are attached to a release a few minutes after it appears
                val name = urls.keys.firstOrNull { it.startsWith("Netino-") && it.endsWith(suffix) } ?: return@mapNotNull null
                val v = name.removePrefix("Netino-").removeSuffix(suffix)
                Updater.Release(v, name, urls.getValue(name), urls[sums], o["html_url"]?.jsonPrimitive?.content.orEmpty())
            }
            .filter { isNewer(it.version, local) }
            .maxWithOrNull { a, b -> if (isNewer(a.version, b.version)) 1 else if (a.version == b.version) 0 else -1 }
    }

    companion object {
        val WINDOWS = UpdateChannel(null, "-windows-x64.msi", "-windows-x64-portable.zip", "SHA256SUMS-windows.txt")
        val LINUX = UpdateChannel("linux-v", "-linux-x64.deb", "-linux-x64.tar.gz", "SHA256SUMS-linux.txt", strict = true)

        /** This computer's channel. */
        val current get() = if (Platform.isLinux) LINUX else WINDOWS

        /** "2.10.0" > "2.9.3"; non-numeric parts count as 0. */
        fun isNewer(remote: String, local: String): Boolean {
            val r = remote.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
            val l = local.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(r.size, l.size)) {
                val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }
    }
}
