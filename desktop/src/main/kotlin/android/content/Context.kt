package android.content

import java.io.File

/** Stand-in for Android's Context: only where the shared code keeps its files. */
open class Context(val filesDir: File) {
    open val applicationContext: Context get() = this
    val noBackupFilesDir: File get() = filesDir
}
