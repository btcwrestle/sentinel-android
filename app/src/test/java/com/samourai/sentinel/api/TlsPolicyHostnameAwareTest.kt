package com.samourai.sentinel.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Clearnet handshakes must go through the hostname-aware platform
 * check: with the app's per-domain network security config, the
 * host-blind entry throws and every clearnet HTTPS call dies.
 */
class TlsPolicyHostnameAwareTest {

    @Test
    fun `strict path delegates through the hostname-aware platform check`() {
        val src = source("app/src/main/java/com/samourai/sentinel/api/TlsPolicy.kt")
        assertTrue(src.contains("X509TrustManagerExtensions(delegate)"))
        assertTrue(src.contains("aware.checkServerTrusted(chain, authType, peerHost)"))
    }

    @Test
    fun `socket and engine checks never delegate host-blind while a peer host exists`() {
        val src = source("app/src/main/java/com/samourai/sentinel/api/TlsPolicy.kt")
        assertFalse(src.contains("delegate.checkServerTrusted(chain, authType, socket)"))
        assertFalse(src.contains("delegate.checkServerTrusted(chain, authType, engine)"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/")))
            .firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
