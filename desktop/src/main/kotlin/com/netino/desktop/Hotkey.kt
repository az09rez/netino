package com.netino.desktop

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser

/** Ctrl+Alt+N anywhere in Windows: connect / disconnect. Its own thread, with the message loop the hotkey needs. */
object Hotkey {
    private const val ID = 0x4E4E
    private const val MOD_ALT = 0x0001
    private const val MOD_CONTROL = 0x0002
    private const val MOD_NOREPEAT = 0x4000
    private const val VK_N = 0x4E
    @Volatile private var thread: Thread? = null

    fun start(onPress: () -> Unit) {
        if (!Win.isWindows || thread != null) return
        thread = Thread {
            if (!User32.INSTANCE.RegisterHotKey(null, ID, MOD_CONTROL or MOD_ALT or MOD_NOREPEAT, VK_N)) {
                Controller.log(L.t("کلید میانبر Ctrl+Alt+N را برنامهٔ دیگری گرفته است", "Ctrl+Alt+N is taken by another program"))
                thread = null
                return@Thread
            }
            val msg = WinUser.MSG()
            while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.message == WinUser.WM_HOTKEY && msg.wParam.toInt() == ID) runCatching(onPress)
            }
            User32.INSTANCE.UnregisterHotKey(null, ID)
        }.apply { isDaemon = true; name = "netino-hotkey"; start() }
    }
}
