package site.xmpp.greenthumb.core.platform

import java.security.MessageDigest

/** jvmMain: тот же `java.security.MessageDigest`, что и на Android. */
actual fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) {
        for (byte in digest) {
            append(HEX_CHARS[(byte.toInt() shr 4) and 0xF])
            append(HEX_CHARS[byte.toInt() and 0xF])
        }
    }
}

private const val HEX_CHARS = "0123456789abcdef"
