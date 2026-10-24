package com.samourai.sentinel.data.db

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM seal around the 32-byte database key. The wrapping key is an
 * Android Keystore key on device. Tests pass a software [SecretKey].
 * The PIN is not an input: the database has to open when the process
 * starts, and the wrapped blob must stay valid if the PIN record changes.
 */
internal interface KeyWrapper {
    fun seal(plaintext: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

internal class AesGcmKeyWrapper(private val key: SecretKey) : KeyWrapper {
    override fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        val ct = cipher.doFinal(plaintext)
        val iv = cipher.iv
        if (iv == null || iv.size != IV_BYTES) {
            throw DatabaseKeyException()
        }
        val out = ByteArray(1 + iv.size + ct.size)
        out[0] = VERSION
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
        return out
    }

    override fun open(sealed: ByteArray): ByteArray {
        try {
            if (sealed.size < 1 + IV_BYTES + TAG_BYTES || sealed[0] != VERSION) {
                throw DatabaseKeyException()
            }
            val iv = sealed.copyOfRange(1, 1 + IV_BYTES)
            val ct = sealed.copyOfRange(1 + IV_BYTES, sealed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            val plain = cipher.doFinal(ct)
            if (plain.size != DatabasePassphrase.RAW_BYTES) throw DatabaseKeyException()
            return plain
        } catch (e: DatabaseKeyException) {
            throw e
        } catch (e: Exception) {
            throw DatabaseKeyException(cause = e)
        }
    }

    companion object {
        private const val VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val AAD = "sentinel-room-v1".toByteArray(Charsets.US_ASCII)
    }
}

internal object DatabaseKeyStore {
    private const val FILE_NAME = "sentinel_room.key"
    private const val KEYSTORE_ALIAS = "sentinel.room.database"

    fun keyFile(context: android.content.Context): File = File(context.filesDir, FILE_NAME)

    fun getOrCreate(context: android.content.Context, encryptedDatabasePresent: Boolean): ByteArray {
        return getOrCreate(keyFile(context), AesGcmKeyWrapper(keystoreKey()), encryptedDatabasePresent)
    }

    fun getOrCreate(
        keyFile: File,
        wrapper: KeyWrapper,
        encryptedDatabasePresent: Boolean
    ): ByteArray {
        if (keyFile.exists()) {
            val sealed = try {
                keyFile.readBytes()
            } catch (e: IOException) {
                throw DatabaseKeyException(cause = e)
            }
            return wrapper.open(sealed).copyOf()
        }
        if (encryptedDatabasePresent) {
            throw DatabaseKeyException()
        }
        val raw = DatabasePassphrase.random()
        atomicWrite(keyFile, wrapper.seal(raw))
        return raw.copyOf()
    }

    private fun keystoreKey(): SecretKey {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val builder = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setUnlockedDeviceRequired(false)
            }
            generator.init(builder.build())
            return generator.generateKey()
        } catch (e: DatabaseKeyException) {
            throw e
        } catch (e: Exception) {
            throw DatabaseKeyException(cause = e)
        }
    }

    private fun atomicWrite(dest: File, bytes: ByteArray) {
        val dir = dest.parentFile ?: throw IOException("Could not store the local database key.")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Could not store the local database key.")
        }
        val tmp = File(dir, dest.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (dest.exists() && !dest.delete()) {
                throw IOException("Could not store the local database key.")
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                if (!tmp.delete() && tmp.exists()) {
                    throw IOException("Could not store the local database key.")
                }
            }
            dest.setReadable(false, false)
            dest.setWritable(false, false)
            dest.setExecutable(false, false)
            dest.setReadable(true, true)
            dest.setWritable(true, true)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }
}
