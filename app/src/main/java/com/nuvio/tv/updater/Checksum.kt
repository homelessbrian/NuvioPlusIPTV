package com.nuvio.tv.updater

/** SHA-256 helpers for checking a downloaded update before it is installed. */
internal object Checksum {
    private const val HEX_LENGTH = 64

    /** Accepts GitHub's "sha256:<hex>" (or a bare hex string) and returns lower-case hex, or null if it isn't a SHA-256. */
    fun fromDigest(digest: String?): String? {
        val value = digest?.trim()?.lowercase().orEmpty()
        val hex = if (value.startsWith("sha256:")) value.removePrefix("sha256:") else value
        return hex.takeIf { it.length == HEX_LENGTH && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { b -> append("%02x".format(b.toInt() and 0xff)) }
    }

    /** True when nothing is expected (older releases) or the file's hash is the expected one. */
    fun matches(expected: String?, actualHex: String): Boolean =
        expected == null || expected.equals(actualHex, ignoreCase = true)
}
