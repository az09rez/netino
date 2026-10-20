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
    private var lock: FileLock? = null

    /** True if this is the only instance; otherwise asks the running one to show itself. */
    fun acquire(): Boolean {
        val portFile = File(Store.dir, "instance.port")
        lock = runCatching { RandomAccessFile(File(Store.dir, "instance.lock"), "rw").channel.tryLock() }.getOrNull()
        if (lock == null) {
            runCatching { Socket(InetAddress.getLoopbackAddress(), portFile.readText().trim().toInt()).use { it.getOutputStream().write("show\n".toByteArray()) } }
            return false
        }
        runCatching {
            val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
            portFile.writeText(server.localPort.toString())
            Thread {
                while (true) runCatching {
                    server.accept().use { s -> if (s.getInputStream().bufferedReader().readLine() == "show") onShow() }
                }
            }.apply { isDaemon = true }.start()
        }
        return true
    }
}
