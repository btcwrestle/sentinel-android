package com.samourai.sentinel.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #278. decryptSHA256 forwards the iteration count. The legacy v2 arm
 * was forwarding the SHA-1 count (5000). v2 payloads are written with
 * the SHA-256 count (15000). The version-1 arm stays on the SHA-1 count.
 */
class LegacyPbkdf2IterationsTest {

    @Test
    fun `legacy v2 uses the SHA-256 iteration count and v1 stays on SHA-1`() {
        val util = source("extlibj/src/main/java/com/samourai/wallet/crypto/BackupCodec.kt")
        val legacy = util.substringAfter("private fun decryptLegacy")
        val v1 = legacy.substringBefore("else")
        val v2 = legacy.substringAfter("else").substringBefore("catch")
        assertTrue(v1.contains("AESUtil.decrypt("))
        assertTrue(v1.contains("DefaultPBKDF2Iterations"))
        assertFalse(v1.contains("DefaultPBKDF2HMACSHA256Iterations"))
        assertTrue(v2.contains("decryptSHA256"))
        assertTrue(v2.contains("DefaultPBKDF2HMACSHA256Iterations"))
        assertFalse(v2.contains("DefaultPBKDF2Iterations"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(
            File(relative),
            File(relative.removePrefix("app/")),
            File("../$relative"),
        ).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
