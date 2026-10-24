package com.samourai.sentinel.core.access

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Non-exportable AES-GCM key in the Android Keystore.
 * The sealed blob is a 12-byte IV followed by ciphertext and tag.
 */
@RequiresApi(Build.VERSION_CODES.M)
class AndroidKeystoreSealer : AtRestSealer {

    override val seals: Boolean = true

    override fun seal(plain: ByteArray): ByteArray {
        synchronized(gate) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            if (iv == null || iv.size != IV_LEN) {
                throw IllegalStateException("Unexpected keystore IV")
            }
            return iv + cipher.doFinal(plain)
        }
    }

    override fun open(sealed: ByteArray): ByteArray {
        synchronized(gate) {
            if (sealed.size < IV_LEN + 16) {
                throw IllegalArgumentException("Sealed blob is too short")
            }
            val iv = sealed.copyOfRange(0, IV_LEN)
            val body = sealed.copyOfRange(IV_LEN, sealed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            return cipher.doFinal(body)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "sentinel_payload_seal"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private val gate = Any()
    }
}
