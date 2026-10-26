package com.samourai.wallet.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM. The returned blob is a 12-byte nonce followed by
 * ciphertext and the 16-byte authentication tag.
 */
object AesGcm {

    const val KEY_LEN = 32
    const val NONCE_LEN = 12
    const val TAG_BITS = 128

    private val random = SecureRandom()

    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == KEY_LEN) { "AES-256 key required" }
        val nonce = ByteArray(NONCE_LEN)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val body = cipher.doFinal(plaintext)
        return nonce + body
    }

    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        require(key.size == KEY_LEN) { "AES-256 key required" }
        if (blob.size < NONCE_LEN + TAG_BITS / 8) {
            throw AuthTagException(IllegalArgumentException("ciphertext too short"))
        }
        val nonce = blob.copyOfRange(0, NONCE_LEN)
        val body = blob.copyOfRange(NONCE_LEN, blob.size)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce)
            )
            return cipher.doFinal(body)
        } catch (e: AEADBadTagException) {
            throw AuthTagException(e)
        } catch (e: BadPaddingException) {
            throw AuthTagException(e)
        }
    }
}

/** GCM authentication failed, or the blob was too short to authenticate. */
class AuthTagException(cause: Throwable? = null) : Exception("Authentication check failed", cause)
