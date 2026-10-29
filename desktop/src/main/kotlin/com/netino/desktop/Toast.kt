package com.netino.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/** A Windows notification: title, text, and buttons as (label, netino:// command). */
data class Notice(val title: String, val text: String, val actions: List<Pair<String, String>> = emptyList())

/** What notification buttons (and links) ask the running Netino to do. */
object Commands {
    const val FASTEST = "netino://fastest"
    const val RELEASE = "netino://release"
    const val SHOW = "netino://show"
    const val UPDATE = "netino://update"
}

/**
 * Windows' own notifications (toasts), which can have buttons, unlike the tray's. A button opens a netino:// link:
 * Windows starts Netino with it, and that second start hands the command to the running one (see [SingleInstance]).
 * Unpackaged apps get toasts through a registered AppUserModelID (name and icon), set up in the user's registry.
 */
object Toast {
    private const val AUMID = "Netino.Desktop"

    /** Registers the netino:// link and the notification identity (current user only, no administrator rights). */
    fun register() {
        val exe = Win.launcher ?: return
        if (!Win.isWindows) return
        runCatching {
            val hk = WinReg.HKEY_CURRENT_USER
            val link = "Software\\Classes\\netino"
            Advapi32Util.registryCreateKey(hk, "$link\\shell\\open\\command")
            Advapi32Util.registrySetStringValue(hk, link, "", "URL:Netino")
            Advapi32Util.registrySetStringValue(hk, link, "URL Protocol", "")
            Advapi32Util.registrySetStringValue(hk, "$link\\shell\\open\\command", "", "\"$exe\" \"%1\"")
            val id = "Software\\Classes\\AppUserModelId\\$AUMID"
            Advapi32Util.registryCreateKey(hk, id)
            Advapi32Util.registrySetStringValue(hk, id, "DisplayName", "Netino")
            icon()?.let { Advapi32Util.registrySetStringValue(hk, id, "IconUri", it.absolutePath) }
        }
    }

    /** The logo as a file (the notification's icon is read from disk). */
    private fun icon(): File? = runCatching {
        File(Store.dir, "netino.png").also { f ->
            if (!f.exists()) Toast::class.java.classLoader.getResourceAsStream("netino.png")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        }
    }.getOrNull()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Shows a toast with [n]'s buttons; false if Windows didn't (then the tray notification is used). Blocking: call off the UI thread. */
    fun show(n: Notice): Boolean {
        if (!Win.isWindows || Win.launcher == null) return false
        val actions = n.actions.joinToString("") { (label, cmd) -> "<action content=\"${esc(label)}\" activationType=\"protocol\" arguments=\"${esc(cmd)}\"/>" }
        val xml = "<toast activationType=\"protocol\" launch=\"${Commands.SHOW}\"><visual><binding template=\"ToastGeneric\">" +
            "<text>${esc(n.title)}</text><text>${esc(n.text)}</text></binding></visual><actions>$actions</actions></toast>"
        // Base64: the Persian text arrives intact whatever PowerShell's console code page is
        val b64 = java.util.Base64.getEncoder().encodeToString(xml.toByteArray(Charsets.UTF_8))
        val script = """
            [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
            [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
            ${'$'}x = New-Object Windows.Data.Xml.Dom.XmlDocument
            ${'$'}x.LoadXml([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$b64')))
            [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('$AUMID').Show((New-Object Windows.UI.Notifications.ToastNotification ${'$'}x))
        """.trimIndent()
        return runCatching { Win.powershell(script, 15) }.isSuccess
    }
}
