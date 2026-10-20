package android.util

/**
 * The part of Android's Base64 the shared code uses, on java.util.Base64. Decoding is lenient like Android's:
 * line breaks and spaces are skipped, padding is optional, and both alphabets are accepted.
 */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val URL_SAFE = 8

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        var enc = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) enc = enc.withoutPadding()
        val s = enc.encodeToString(input)
        // Android wraps at 76 characters unless NO_WRAP
        return if (flags and NO_WRAP != 0) s else s.chunked(76).joinToString("\n", postfix = "\n")
    }

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray {
        val clean = str.filterNot { it.isWhitespace() }.trimEnd('=')
            .replace('-', '+').replace('_', '/')
        try {
            return java.util.Base64.getDecoder().decode(clean + "=".repeat((4 - clean.length % 4) % 4))
        } catch (e: IllegalArgumentException) {
            // Android throws IllegalArgumentException too ("bad base-64")
            throw IllegalArgumentException("bad base-64", e)
        }
    }
}
