package com.samourai.sentinel.data.db

import com.samourai.wallet.crypto.AesGcm
import org.bouncycastle.util.encoders.Base64

/**
 * On-disk payload armor. The prefix keeps this distinct from OpenSSL
 * "Salted__" payloads and from raw JSON.
 */
object PayloadCipher {

    const val PREFIX = "sentinel-gcm-v1:"

    fun isModern(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(plaintext: String, key: ByteArray): String {
        val blob = AesGcm.encrypt(key, plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.toBase64String(blob)
    }

    fun decrypt(value: String, key: ByteArray): String {
        if (!isModern(value)) {
            throw IllegalArgumentException("Not a current payload")
        }
        val blob = Base64.decode(value.substring(PREFIX.length))
        return String(AesGcm.decrypt(key, blob), Charsets.UTF_8)
    }
}

/** OpenSSL "Salted__" armoring, base64, as written by the previous payload format. */
fun isLegacyOpenSslPayload(value: String): Boolean {
    return value.trimStart().startsWith("U2FsdGVkX1")
}
