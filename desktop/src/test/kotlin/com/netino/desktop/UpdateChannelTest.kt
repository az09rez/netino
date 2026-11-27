package com.netino.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Each app only ever sees its own releases: Android "v", Windows "windows-v", Linux "linux-v". */
class UpdateChannelTest {

    private fun release(tag: String, vararg assets: String, draft: Boolean = false) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":false,"html_url":"https://example.com/$tag",
         "assets":[${assets.joinToString(",") { """{"name":"$it","browser_download_url":"https://example.com/$tag/$it"}""" }}]}
    """

    /** What GitHub returns, newest first: one release of every app. */
    private val mixed = "[" + listOf(
        release("linux-v1.1.0", "Netino-1.1.0-linux-x64.deb", "Netino-1.1.0-linux-x64.tar.gz", "SHA256SUMS-linux.txt"),
        release("windows-v2.16.0", "Netino-2.16.0-windows-x64.msi", "Netino-2.16.0-windows-x64-portable.zip", "SHA256SUMS-windows.txt"),
        release("v2.12.0", "Netino-2.12.0.apk", "SHA256SUMS.txt"),
        release("linux-v1.0.0", "Netino-1.0.0-linux-x64.deb", "Netino-1.0.0-linux-x64.tar.gz", "SHA256SUMS-linux.txt"),
    ).joinToString(",") + "]"

    @Test fun linuxSeesOnlyLinux() {
        val r = UpdateChannel.LINUX.pick(mixed, "1.0.0", portable = false)!!
        assertEquals("1.1.0", r.version)
        assertEquals("Netino-1.1.0-linux-x64.deb", r.name)
        assertEquals("https://example.com/linux-v1.1.0/SHA256SUMS-linux.txt", r.sumsUrl)
    }

    @Test fun linuxPortableGetsTheArchive() {
        assertEquals("Netino-1.1.0-linux-x64.tar.gz", UpdateChannel.LINUX.pick(mixed, "1.0.0", portable = true)!!.name)
    }

    @Test fun windowsSeesOnlyWindows() {
        val r = UpdateChannel.WINDOWS.pick(mixed, "2.15.0", portable = false)!!
        assertEquals("2.16.0", r.version)
        assertEquals("Netino-2.16.0-windows-x64.msi", r.name)
        assertEquals("https://example.com/windows-v2.16.0/SHA256SUMS-windows.txt", r.sumsUrl)
    }

    /** A Linux 3.0.0 must not make Windows 2.15.0 (or Linux, from a Windows release) think it is out of date. */
    @Test fun higherNumbersOnAnotherChannelAreIgnored() {
        val json = "[" + release("linux-v9.0.0", "Netino-9.0.0-linux-x64.deb", "SHA256SUMS-linux.txt") + "," +
            release("windows-v9.0.0", "Netino-9.0.0-windows-x64.msi", "SHA256SUMS-windows.txt") + "]"
        assertEquals("9.0.0", UpdateChannel.WINDOWS.pick(json, "2.15.0", false)!!.version)
        assertEquals("9.0.0", UpdateChannel.LINUX.pick(json, "1.0.0", false)!!.version)
        val onlyLinux = "[" + release("linux-v9.0.0", "Netino-9.0.0-linux-x64.deb") + "]"
        assertNull(UpdateChannel.WINDOWS.pick(onlyLinux, "2.15.0", false))
        val onlyWindows = "[" + release("windows-v9.0.0", "Netino-9.0.0-windows-x64.msi") + "]"
        assertNull(UpdateChannel.LINUX.pick(onlyWindows, "1.0.0", false))
    }

    /** A Linux file attached to a release with another tag (by mistake) isn't offered to Linux. */
    @Test fun linuxNeedsItsOwnTag() {
        val json = "[" + release("v2.13.0", "Netino-2.13.0-linux-x64.deb") + "]"
        assertNull(UpdateChannel.LINUX.pick(json, "1.0.0", false))
    }

    @Test fun upToDateAndDrafts() {
        assertNull(UpdateChannel.LINUX.pick(mixed, "1.1.0", false))
        val draft = "[" + release("linux-v2.0.0", "Netino-2.0.0-linux-x64.deb", draft = true) + "]"
        assertNull(UpdateChannel.LINUX.pick(draft, "1.0.0", false))
    }
}
