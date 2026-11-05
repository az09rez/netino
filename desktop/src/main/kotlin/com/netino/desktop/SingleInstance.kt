package com.netino.desktop

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock

/**
 * One Netino at a time (two would fight over the ports and the system proxy). Starting it again
 * brings the running one's window up instead.
 */
object SingleInstance {
    @Volatile var onShow: () -> Unit = {}
    /** A netino:// command handed over by a second start (a notification button). */
    @Volatile var onCommand: (String) -> Unit = {}
    private var lock: FileLock? = null

    /**
     * True if this is the only instance; otherwise asks the running one to show itself. [wait]: the previous
     * instance is exiting (restart as administrator, update), so wait for it a few seconds.
     */
    fun acquire(wait: Boolean = false, command: String? = null): Boolean {
        val portFile = File(Store.dir, "instance.port")
        val channel = RandomAccessFile(File(Store.dir, "instance.lock"), "rw").channel
        val until = System.currentTimeMillis() + if (wait) 10_000 else 0
        while (true) {
            lock = runCatching { channel.tryLock() }.getOrNull()
            if (lock != null || System.currentTimeMillis() >= until) break
            Thread.sleep(200)
        }
        if (lock == null) {
            val line = command?.let { "cmd $it" } ?: "show"
            runCatching { Socket(InetAddress.getLoopbackAddress(), portFile.readText().trim().toInt()).use { it.getOutputStream().write("$line\n".toByteArray()) } }
            return false
        }
        runCatching {
            val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
            portFile.writeText(server.localPort.toString())
            Thread {
                while (true) runCatching {
                    server.accept().use { s ->
                        val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                        if (line == "show") onShow() else if (line.startsWith("cmd netino://")) onCommand(line.removePrefix("cmd "))
                    }
                }
            }.apply { isDaemon = true }.start()
        }
        return true
    }
}
