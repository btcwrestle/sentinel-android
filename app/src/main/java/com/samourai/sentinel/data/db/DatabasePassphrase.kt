package com.samourai.sentinel.data.db

import java.security.SecureRandom

/**
 * 32 random bytes stored wrapped at rest. SQLCipher receives the hex
 * encoding of those bytes (ASCII 0-9a-f only), which is a high-entropy
 * passphrase and is safe to pass through both the Android binding and
 * SQLCipher's key pragma without quoting hazards.
 */
internal object DatabasePassphrase {
    const val RAW_BYTES = 32

    fun random(rng: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(RAW_BYTES).also { rng.nextBytes(it) }

    fun hex(raw: ByteArray): String {
        require(raw.size == RAW_BYTES) { "database key" }
        val digits = "0123456789abcdef"
        val out = CharArray(RAW_BYTES * 2)
        for (i in raw.indices) {
            val v = raw[i].toInt() and 0xff
            out[i * 2] = digits[v ushr 4]
            out[i * 2 + 1] = digits[v and 0x0f]
        }
        return String(out)
    }

    /** Passphrase bytes handed to SQLCipher. A fresh array on every call. */
    fun utf8(raw: ByteArray): ByteArray = hex(raw).toByteArray(Charsets.US_ASCII)
}
