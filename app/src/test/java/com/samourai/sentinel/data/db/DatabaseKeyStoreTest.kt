package com.samourai.sentinel.data.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The wrapped database key lives in its own file. These tests run the
 * seal, open, and get-or-create paths with a software AES key.
 */
class DatabaseKeyStoreTest {

    @Test
    fun sealRoundTripsAndUsesAFreshIv() {
        val wrapper = wrapper()
        val raw = DatabasePassphrase.random(SecureRandom())
        val first = wrapper.seal(raw)
        val second = wrapper.seal(raw)

        assertFalse(first.contentEquals(second))
        assertArrayEquals(raw, wrapper.open(first))
        assertArrayEquals(raw, wrapper.open(second))
        assertEquals(64, DatabasePassphrase.hex(raw).length)
        assertTrue(DatabasePassphrase.hex(raw).all { it in '0'..'9' || it in 'a'..'f' })
        assertArrayEquals(
            DatabasePassphrase.hex(raw).toByteArray(Charsets.US_ASCII),
            DatabasePassphrase.utf8(raw)
        )
    }

    @Test
    fun tamperedBlobFailsAndTheFileIsLeftInPlace() {
        val dir = tempDir()
        val keyFile = File(dir, "sentinel_room.key")
        val wrapper = wrapper()
        val created = DatabaseKeyStore.getOrCreate(keyFile, wrapper, encryptedDatabasePresent = false)
        val sealed = keyFile.readBytes()
        sealed[sealed.lastIndex] = (sealed[sealed.lastIndex].toInt() xor 0xff).toByte()
        keyFile.writeBytes(sealed)

        assertThrows(DatabaseKeyException::class.java) {
            DatabaseKeyStore.getOrCreate(keyFile, wrapper, encryptedDatabasePresent = true)
        }
        assertArrayEquals(sealed, keyFile.readBytes())
        assertEquals(DatabasePassphrase.RAW_BYTES, created.size)
    }

    @Test
    fun missingWrappedKeyWithAnEncryptedDatabaseDoesNotCreateOne() {
        val dir = tempDir()
        val keyFile = File(dir, "sentinel_room.key")
        val prefs = File(dir, "prefs.xml")
        prefs.writeText("pinHash=not-the-database-key")

        assertThrows(DatabaseKeyException::class.java) {
            DatabaseKeyStore.getOrCreate(keyFile, wrapper(), encryptedDatabasePresent = true)
        }
        assertFalse(keyFile.exists())
        assertTrue(prefs.exists())
    }

    @Test
    fun existingWrappedKeyIsStableAndIndependentOfASiblingPrefsFile() {
        val dir = tempDir()
        val keyFile = File(dir, "sentinel_room.key")
        val prefs = File(dir, "package_preferences.xml")
        prefs.writeText("pinHash=abc")
        val wrapper = wrapper()

        val first = DatabaseKeyStore.getOrCreate(keyFile, wrapper, encryptedDatabasePresent = false)
        prefs.delete()
        val second = DatabaseKeyStore.getOrCreate(keyFile, wrapper, encryptedDatabasePresent = true)

        assertArrayEquals(first, second)
        assertFalse(first === second)
        assertTrue(keyFile.exists())
        assertFalse(prefs.exists())
        val hex = DatabasePassphrase.hex(first)
        assertFalse(String(keyFile.readBytes(), Charsets.ISO_8859_1).contains(hex))
        assertNotEquals(String(first.copyOf()), hex)
    }

    @Test
    fun plaintextHeaderAndShred() {
        val dir = tempDir()
        val plain = File(dir, "sentinel_database")
        plain.writeBytes("SQLite format 3\u0000and then some bytes".toByteArray(Charsets.US_ASCII))
        assertTrue(PlaintextSqlite.isPlaintext(plain))

        val random = File(dir, "other")
        random.writeBytes(ByteArray(32) { 7 })
        assertFalse(PlaintextSqlite.isPlaintext(random))

        val secret = File(dir, "sentinel_database.plaintext")
        secret.writeBytes("xpub-secret-residue".toByteArray())
        PlaintextSqlite.shredPlaintextResidue(plain)
        assertFalse(secret.exists())
        assertTrue(plain.exists())
        assertTrue(PlaintextSqlite.isPlaintext(plain))
    }

    private fun wrapper(): AesGcmKeyWrapper {
        val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return AesGcmKeyWrapper(key)
    }

    private fun tempDir(): File {
        val dir = File.createTempFile("sentinel_key_", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }
}
