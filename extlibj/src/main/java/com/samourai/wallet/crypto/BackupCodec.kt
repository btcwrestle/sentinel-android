package com.samourai.wallet.crypto

import com.samourai.wallet.util.CharSequenceX
import org.bouncycastle.util.encoders.Base64
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Versioned backup envelope.
 *
 * Format 3 is AES-256-GCM with a key from Argon2id. The header carries the
 * KDF profile that produced the key. Older envelopes stay readable:
 * version 1 uses the historical PBKDF2 count, and every later legacy
 * envelope uses [AESUtil.DefaultPBKDF2HMACSHA256Iterations], the same count
 * export used for that format. [com.samourai.sentinel.util.ExportImportUtil]
 * opens both current and legacy sentinel backups through here so the two
 * decrypt paths cannot pick different counts (#278).
 */
object BackupCodec {

    const val FORMAT_AES_GCM = 3

    private const val KDF_ARGON2ID = "argon2id"
    private val random = SecureRandom()

    fun encrypt(
        plaintext: String,
        password: String,
        appVersionCode: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        if (password.isEmpty()) {
            throw BackupDecryptException("Password is empty")
        }
        val salt = ByteArray(16)
        random.nextBytes(salt)
        val passwordChars = password.toCharArray()
        val key = Argon2Kdf.derive(
            passwordChars,
            salt,
            Argon2Kdf.BACKUP_MEMORY_KIB,
            Argon2Kdf.BACKUP_ITERATIONS,
            Argon2Kdf.BACKUP_PARALLELISM,
            AesGcm.KEY_LEN
        )
        try {
            val blob = AesGcm.encrypt(key, plaintext.toByteArray(Charsets.UTF_8))
            return JSONObject()
                .put("format", FORMAT_AES_GCM)
                .put("version", appVersionCode)
                .put("time", nowMillis)
                .put("kdf", KDF_ARGON2ID)
                .put("salt", Base64.toBase64String(salt))
                .put("memoryKiB", Argon2Kdf.BACKUP_MEMORY_KIB)
                .put("iterations", Argon2Kdf.BACKUP_ITERATIONS)
                .put("parallelism", Argon2Kdf.BACKUP_PARALLELISM)
                .put("payload", Base64.toBase64String(blob))
                .toString()
        } finally {
            passwordChars.fill('\u0000')
            key.fill(0)
        }
    }

    fun decryptEnvelope(envelope: String, password: String): String {
        if (password.isEmpty()) {
            throw BackupDecryptException("Password is empty")
        }
        val json = try {
            JSONObject(envelope)
        } catch (e: Exception) {
            throw BackupDecryptException("Invalid payload", e)
        }
        if (!json.has("payload")) {
            throw BackupDecryptException("Invalid payload")
        }
        if (json.optInt("format", 0) == FORMAT_AES_GCM) {
            return decryptCurrent(json, password)
        }
        if (!json.has("version")) {
            throw BackupDecryptException("Invalid payload")
        }
        return decryptLegacy(json.getString("payload"), json.getInt("version"), password)
    }

    private fun decryptCurrent(json: JSONObject, password: String): String {
        if (json.optString("kdf") != KDF_ARGON2ID) {
            throw BackupDecryptException("Unsupported backup KDF")
        }
        val memoryKiB = json.optInt("memoryKiB", -1)
        val iterations = json.optInt("iterations", -1)
        val parallelism = json.optInt("parallelism", -1)
        if (memoryKiB < 8 * 1024 || memoryKiB > 256 * 1024 ||
            iterations < 1 || iterations > 8 ||
            parallelism != 1
        ) {
            throw BackupDecryptException("Unsupported backup KDF profile")
        }
        val salt = decode(json.optString("salt"))
        val blob = decode(json.optString("payload"))
        if (salt.size < 16 || salt.size > 64) {
            throw BackupDecryptException("Unsupported backup salt")
        }
        val passwordChars = password.toCharArray()
        val key = Argon2Kdf.derive(
            passwordChars,
            salt,
            memoryKiB,
            iterations,
            parallelism,
            AesGcm.KEY_LEN
        )
        try {
            val plain = AesGcm.decrypt(key, blob)
            return String(plain, Charsets.UTF_8)
        } catch (e: AuthTagException) {
            throw BackupDecryptException("Authentication check failed", e)
        } finally {
            passwordChars.fill('\u0000')
            key.fill(0)
        }
    }

    private fun decryptLegacy(payload: String, version: Int, password: String): String {
        val passwordChars = CharSequenceX(password)
        try {
            return if (version == 1) {
                AESUtil.decrypt(payload, passwordChars, AESUtil.DefaultPBKDF2Iterations)
            } else {
                AESUtil.decryptSHA256(
                    payload,
                    passwordChars,
                    AESUtil.DefaultPBKDF2HMACSHA256Iterations
                )
            }
        } catch (e: Exception) {
            throw BackupDecryptException("Unable to decrypt", e)
        }
    }

    private fun decode(value: String): ByteArray {
        if (value.isEmpty()) {
            throw BackupDecryptException("Invalid payload")
        }
        return try {
            Base64.decode(value)
        } catch (e: Exception) {
            throw BackupDecryptException("Invalid payload", e)
        }
    }
}

class BackupDecryptException(message: String, cause: Throwable? = null) : Exception(message, cause)
