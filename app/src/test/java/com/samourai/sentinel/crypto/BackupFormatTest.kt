package com.samourai.sentinel.crypto

import com.samourai.sentinel.util.ExportImportUtil
import com.samourai.wallet.crypto.AESUtil
import com.samourai.wallet.crypto.AesGcm
import com.samourai.wallet.crypto.AuthTagException
import com.samourai.wallet.crypto.BackupCodec
import com.samourai.wallet.crypto.BackupDecryptException
import com.samourai.wallet.util.CharSequenceX
import org.bouncycastle.util.encoders.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom

/**
 * Runs the backup and payload ciphers: current envelope, authentication
 * failure, and the previous envelope (including the v2 iteration count
 * shared by both sentinel importers).
 */
class BackupFormatTest {

    @Test
    fun aesGcmRoundTripAndRejectsTamperOrWrongKey() {
        val key = ByteArray(AesGcm.KEY_LEN).also { random.nextBytes(it) }
        val plain = """{"collections":[{"id":"1"}]}""".toByteArray(Charsets.UTF_8)
        val blob = AesGcm.encrypt(key, plain)
        assertArrayEquals(plain, AesGcm.decrypt(key, blob))

        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        try {
            AesGcm.decrypt(key, blob)
            fail("tampered ciphertext opened")
        } catch (e: AuthTagException) {
            assertTrue(e.cause != null)
        }

        val other = ByteArray(AesGcm.KEY_LEN).also { random.nextBytes(it) }
        val again = AesGcm.encrypt(key, plain)
        try {
            AesGcm.decrypt(other, again)
            fail("wrong key opened")
        } catch (e: AuthTagException) {
            assertTrue(e.message != null)
        }
    }

    @Test
    fun currentBackupRoundTripsAndRejectsTamper() {
        val env = BackupCodec.encrypt(SENTINEL_PLAIN, PASSWORD, 55, 1L)
        val json = JSONObject(env)
        assertEquals(BackupCodec.FORMAT_AES_GCM, json.getInt("format"))
        assertEquals(55, json.getInt("version"))
        assertEquals(1L, json.getLong("time"))
        assertEquals("argon2id", json.getString("kdf"))
        assertFalse(json.getString("payload").contains("selectedCurrency"))

        assertEquals(SENTINEL_PLAIN, BackupCodec.decryptEnvelope(env, PASSWORD))
        val imported = ExportImportUtil().decryptSentinel(env, PASSWORD)
        assertEquals("USD", imported.second.getString("selectedCurrency"))
        assertTrue(imported.first != null && imported.first!!.isEmpty())
        assertFalse(imported.second.has("pinHash"))

        val tampered = JSONObject(env)
        val blob = Base64.decode(tampered.getString("payload"))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        tampered.put("payload", Base64.toBase64String(blob))
        try {
            BackupCodec.decryptEnvelope(tampered.toString(), PASSWORD)
            fail("tampered backup opened")
        } catch (e: BackupDecryptException) {
            assertTrue(e.cause is AuthTagException)
        }

        try {
            BackupCodec.decryptEnvelope(env, "not-the-password")
            fail("wrong password opened")
        } catch (e: BackupDecryptException) {
            assertTrue(e.cause is AuthTagException)
        }
    }

    @Test
    fun legacyV2EnvelopeUsesExportIterationCount() {
        val opened = BackupCodec.decryptEnvelope(legacyEnvelope(SENTINEL_PLAIN, 42, AESUtil.DefaultPBKDF2HMACSHA256Iterations, sha256 = true), PASSWORD)
        assertEquals(SENTINEL_PLAIN, opened)
        val imported = ExportImportUtil().decryptSentinel(
            legacyEnvelope(SENTINEL_PLAIN, 42, AESUtil.DefaultPBKDF2HMACSHA256Iterations, sha256 = true),
            PASSWORD
        )
        assertEquals("USD", imported.second.getString("selectedCurrency"))

        val mismatched = try {
            BackupCodec.decryptEnvelope(
                legacyEnvelope(SENTINEL_PLAIN, 42, AESUtil.DefaultPBKDF2Iterations, sha256 = true),
                PASSWORD
            )
        } catch (e: BackupDecryptException) {
            null
        }
        assertNotEquals(SENTINEL_PLAIN, mismatched)
    }

    @Test
    fun legacyV1EnvelopeStillOpens() {
        val env = legacyEnvelope(SENTINEL_PLAIN, 1, AESUtil.DefaultPBKDF2Iterations, sha256 = false)
        assertEquals(SENTINEL_PLAIN, BackupCodec.decryptEnvelope(env, PASSWORD))
        val imported = ExportImportUtil().decryptSentinel(env, PASSWORD)
        assertEquals("USD", imported.second.getString("selectedCurrency"))
    }

    @Test
    fun legacySentinelXpubBackupImports() {
        val inner = JSONObject()
            .put("xpubs", JSONArray().put(JSONObject().put(XPUB, "Savings")))
            .toString()
        val env = legacyEnvelope(inner, 42, AESUtil.DefaultPBKDF2HMACSHA256Iterations, sha256 = true)
        val parsed = ExportImportUtil().decryptSentinelLegacy(env, PASSWORD)
        assertEquals(1, parsed.first.size)
        assertEquals(XPUB, parsed.first[0].pubKey)
        assertEquals("Savings", parsed.first[0].label)
        assertEquals(null, parsed.second)
    }

    private fun legacyEnvelope(plain: String, version: Int, iterations: Int, sha256: Boolean): String {
        val password = CharSequenceX(PASSWORD)
        val payload = if (sha256) {
            AESUtil.encryptSHA256(plain, password, iterations)
        } else {
            AESUtil.encrypt(plain, password, iterations)
        }
        return JSONObject()
            .put("version", version)
            .put("time", 1L)
            .put("payload", payload)
            .toString()
    }

    companion object {
        private const val PASSWORD = "correct horse"
        private const val SENTINEL_PLAIN =
            """{"collections":[],"prefs":{"selectedCurrency":"USD"}}"""
        private const val XPUB =
            "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8"
        private val random = SecureRandom()
    }
}
