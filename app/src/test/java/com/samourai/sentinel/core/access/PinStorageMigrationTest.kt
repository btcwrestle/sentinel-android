package com.samourai.sentinel.core.access

import com.samourai.sentinel.data.db.PayloadCipher
import com.samourai.sentinel.data.db.PayloadReadException
import com.samourai.sentinel.data.db.PayloadRecord
import com.samourai.sentinel.util.Hash
import com.samourai.wallet.crypto.AESUtil
import com.samourai.wallet.crypto.AesGcm
import com.samourai.wallet.crypto.AuthTagException
import com.samourai.wallet.util.CharSequenceX
import org.bouncycastle.util.encoders.Base64
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Previous PIN verifier plus previous payload bytes, rewritten on unlock
 * into the current data-key format without dropping the plaintext.
 */
class PinStorageMigrationTest {

    private val access = AccessFactory.getInstance(null)

    @Before
    fun reset() = clearAccess()

    @After
    fun cleanup() = clearAccess()

    @Test
    fun migratesLegacyPinAndPayloadThenRejectsTamper() {
        val dir = tempDir()
        val collections = PayloadRecord(dir.absolutePath, "collections.payload")
        val dojo = PayloadRecord(dir.absolutePath, "dojo.payload")
        val collectionsPlain = """{"collections":[{"id":"wallet-1"}]}"""
        val dojoPlain = """{"pairing":{"url":"http://dojo.example","apikey":"node-key"}}"""

        writeLegacy(collections, collectionsPlain)
        writeLegacy(dojo, dojoPlain)
        assertTrue(collections.file.readText().startsWith("U2FsdGVkX1"))
        assertTrue(dojo.file.readText().startsWith("U2FsdGVkX1"))

        val store = MemoryPinMaterialStore()
        store.pinHash = legacyPinHash(PIN)
        store.pinEnabled = true
        val vault = PinVault(store, access, NoAtRestSealer)

        assertEquals(UnlockResult.WrongPin, vault.unlock("00000", listOf(collections, dojo)))
        assertEquals(legacyPinHash(PIN), store.pinHash)
        assertTrue(store.pinKeyMaterial.isNullOrBlank())
        assertTrue(collections.file.readText().startsWith("U2FsdGVkX1"))
        assertTrue(access.dataKey == null)

        assertEquals(UnlockResult.Success, vault.unlock(PIN, listOf(collections, dojo)))
        assertTrue(store.pinHash.isNullOrBlank())
        assertFalse(store.pinKeyMaterial.isNullOrBlank())
        assertFalse(store.pinKeyMaterial!!.contains(PIN))
        assertTrue(PayloadCipher.isModern(collections.file.readText()))
        assertTrue(PayloadCipher.isModern(dojo.file.readText()))
        assertEquals(collectionsPlain, collections.decrypt(collections.file.readText()))
        assertEquals(dojoPlain, dojo.decrypt(dojo.file.readText()))

        clearAccess()
        assertEquals(UnlockResult.Success, PinVault(store, access, NoAtRestSealer).unlock(PIN, listOf(collections, dojo)))
        assertEquals(collectionsPlain, collections.decrypt(collections.file.readText()))
        assertEquals(dojoPlain, dojo.decrypt(dojo.file.readText()))

        val beforeTamper = collections.file.readText()
        val blob = Base64.decode(beforeTamper.removePrefix(PayloadCipher.PREFIX))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        collections.file.writeText(PayloadCipher.PREFIX + Base64.toBase64String(blob))
        try {
            collections.decrypt(collections.file.readText())
            fail("tampered payload opened")
        } catch (e: AuthTagException) {
            assertTrue(e.cause != null)
        }
        collections.file.writeText(beforeTamper)

        try {
            collections.decrypt("""{"swapped":true}""")
            fail("plaintext was accepted while a data key is unlocked")
        } catch (e: PayloadReadException) {
            assertTrue(e.message != null)
        }

        val good = store.pinKeyMaterial!!
        val wrap = JSONObject(good)
        val wrapped = Base64.decode(wrap.getString("wrap"))
        wrapped[wrapped.size - 1] = (wrapped[wrapped.size - 1].toInt() xor 0x01).toByte()
        wrap.put("wrap", Base64.toBase64String(wrapped))
        store.pinKeyMaterial = wrap.toString()
        clearAccess()
        assertEquals(UnlockResult.WrongPin, PinVault(store, access, NoAtRestSealer).unlock(PIN, listOf(collections, dojo)))
        assertEquals(beforeTamper, collections.file.readText())
        store.pinKeyMaterial = good
    }

    @Test
    fun enableDoesNotStoreVerifierAndDisableWritesPlaintext() {
        val dir = tempDir()
        val record = PayloadRecord(dir.absolutePath, "collections.payload")
        val plain = """{"collections":[{"id":"fresh"}]}"""
        record.file.parentFile?.mkdirs()
        record.file.writeText(plain)

        val store = MemoryPinMaterialStore()
        val vault = PinVault(store, access, NoAtRestSealer)
        vault.enable(PIN)
        assertTrue(store.pinHash.isNullOrBlank())
        assertTrue(store.pinEnabled == true)
        assertTrue(vault.upgradeExisting(listOf(record)))
        assertTrue(PayloadCipher.isModern(record.file.readText()))
        assertEquals(plain, record.decrypt(record.file.readText()))

        clearAccess()
        assertEquals(UnlockResult.Success, vault.unlock(PIN, listOf(record)))

        assertFalse(vault.disable("00000", listOf(record)))
        assertTrue(PayloadCipher.isModern(record.file.readText()))
        assertFalse(store.pinKeyMaterial.isNullOrBlank())

        assertTrue(vault.disable(PIN, listOf(record)))
        assertEquals(plain, record.file.readText())
        assertFalse(File(dir, "collections.payload.bak").exists())
        assertTrue(store.pinKeyMaterial.isNullOrBlank())
        assertTrue(store.pinHash.isNullOrBlank())
        assertTrue(store.pinEnabled != true)
        assertTrue(access.dataKey == null)
        assertEquals(plain, record.decrypt(record.file.readText()))
    }

    @Test
    fun unlockRewritesPlaintextLeftAfterThePinWasSaved() {
        val dir = tempDir()
        val record = PayloadRecord(dir.absolutePath, "collections.payload")
        val plain = """{"collections":[{"id":"pending"}]}"""
        record.file.parentFile?.mkdirs()
        record.file.writeText(plain)

        val store = MemoryPinMaterialStore()
        val vault = PinVault(store, access, NoAtRestSealer)
        vault.enable(PIN)
        assertFalse(PayloadCipher.isModern(record.file.readText()))

        clearAccess()
        assertEquals(UnlockResult.Success, vault.unlock(PIN, listOf(record)))
        assertTrue(PayloadCipher.isModern(record.file.readText()))
        assertEquals(plain, record.decrypt(record.file.readText()))
    }

    @Test
    fun secondEnableKeepsThePayloadReadable() {
        val dir = tempDir()
        val record = PayloadRecord(dir.absolutePath, "collections.payload")
        val plain = """{"collections":[{"id":"again"}]}"""
        record.file.parentFile?.mkdirs()
        record.file.writeText(plain)

        val store = MemoryPinMaterialStore()
        val vault = PinVault(store, access, NoAtRestSealer)
        vault.enable(PIN)
        assertTrue(vault.upgradeExisting(listOf(record)))
        val firstKey = access.dataKey!!.copyOf()

        vault.enable(PIN)
        assertTrue(access.dataKey!!.contentEquals(firstKey))
        assertEquals(plain, record.decrypt(record.file.readText()))
        firstKey.fill(0)
    }

    @Test
    fun enableRefusesADifferentPinWhileThePreviousVerifierRemains() {
        val dir = tempDir()
        val record = PayloadRecord(dir.absolutePath, "collections.payload")
        val plain = """{"collections":[{"id":"legacy-enable"}]}"""
        writeLegacy(record, plain)
        val previous = record.file.readText()

        val store = MemoryPinMaterialStore()
        store.pinHash = legacyPinHash(PIN)
        store.pinEnabled = true
        val vault = PinVault(store, access, NoAtRestSealer)

        var rejected = false
        try {
            vault.enable("99999")
        } catch (e: Exception) {
            rejected = true
        }
        assertTrue(rejected)
        assertTrue(store.pinKeyMaterial.isNullOrBlank())
        assertEquals(legacyPinHash(PIN), store.pinHash)
        assertEquals(previous, record.file.readText())

        vault.enable(PIN)
        assertTrue(vault.upgradeExisting(listOf(record)))
        vault.markPayloadsCurrent()
        assertTrue(store.pinHash.isNullOrBlank())
        assertEquals(plain, record.decrypt(record.file.readText()))
    }

    @Test
    fun writeRefusesPayloadWhenPinIsSetWithoutDataKey() {
        val dir = tempDir()
        val record = PayloadRecord(dir.absolutePath, "collections.payload")
        access.pin = PIN
        access.setPinProtected(true)
        try {
            record.writeToFile("""{"collections":[]}""")
            fail("payload was written without a data key")
        } catch (e: PayloadReadException) {
            assertTrue(e.message!!.contains("data key"))
        }
        assertFalse(record.file.exists())
    }

    @Test
    fun sealedWrapOpensOnlyWithTheSameSealer() {
        val store = MemoryPinMaterialStore()
        val sealer = MemorySealer()
        val vault = PinVault(store, access, sealer)
        vault.enable(PIN)
        assertTrue(JSONObject(store.pinKeyMaterial).getBoolean("sealed"))

        clearAccess()
        assertEquals(UnlockResult.Success, PinVault(store, access, sealer).unlock(PIN))

        clearAccess()
        val foreign = PinVault(store, access, MemorySealer())
        assertTrue(foreign.unlock(PIN) is UnlockResult.Unavailable)
        assertTrue(access.dataKey == null)
    }

    private fun clearAccess() {
        access.clearDataKey()
        access.pin = null
        access.setPinProtected(false)
        access.setIsLoggedIn(false)
    }

    private fun tempDir(): File {
        val dir = File.createTempFile("pin-storage-", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    private fun writeLegacy(record: PayloadRecord, plain: String) {
        record.file.parentFile?.mkdirs()
        record.file.writeText(
            AESUtil.encryptSHA256(
                plain,
                CharSequenceX(PIN),
                AESUtil.DefaultPBKDF2HMACSHA256Iterations
            )
        )
    }

    private fun legacyPinHash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return Hash(digest.digest(pin.toByteArray(Charsets.UTF_8))).toString()
    }

    private class MemorySealer : AtRestSealer {
        private val key = ByteArray(AesGcm.KEY_LEN).also { SecureRandom().nextBytes(it) }
        override val seals: Boolean = true
        override fun seal(plain: ByteArray): ByteArray = AesGcm.encrypt(key, plain)
        override fun open(sealed: ByteArray): ByteArray = AesGcm.decrypt(key, sealed)
    }

    companion object {
        private const val PIN = "12345"
    }
}
