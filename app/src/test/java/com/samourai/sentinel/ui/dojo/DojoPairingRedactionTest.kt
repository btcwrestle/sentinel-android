package com.samourai.sentinel.ui.dojo

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #258: pins the pairing redaction contract.
 *
 * toString() is the log surface - it must never carry the apikey or the
 * onion URLs. Gson serialization is the export surface (exportDojoPayload
 * -> QR/clipboard/backup) - it must still carry the real payload. A
 * regression in either direction (leak, or broken export) fails here.
 *
 * Synthetic values only - no real keys, no real hosts.
 */
class DojoPairingRedactionTest {

    private val apiKey = "synthetic-apikey-0123456789abcdef"
    private val onionUrl = "http://synthetic5678abcdefghijklmnopqrstuvwxyz.onion/v2"
    private val explorerUrl = "http://synthetic-explorer-abcdefghijklmnop.onion"

    private val payload = DojoPairing(
        pairing = Pairing(
            apikey = apiKey,
            type = "dojo.api",
            url = onionUrl,
            version = "1.27.0"
        ),
        explorer = Explorer(type = "explorer.btc_rpc_explorer", url = explorerUrl)
    )

    @Test
    fun `toString never carries the apikey`() {
        val rendered = payload.toString()
        val pairingRendered = payload.pairing.toString()
        assertFalse("payload toString leaked the apikey", rendered.contains(apiKey))
        assertFalse("Pairing toString leaked the apikey", pairingRendered.contains(apiKey))
    }

    @Test
    fun `toString never carries the onion urls`() {
        val rendered = payload.toString()
        assertFalse("payload toString leaked the Dojo URL", rendered.contains(onionUrl))
        assertFalse("payload toString leaked the explorer URL", rendered.contains(explorerUrl))
    }

    @Test
    fun `toString keeps non-sensitive fields for debugging`() {
        val rendered = payload.toString()
        assertTrue(rendered.contains("dojo.api"))
        assertTrue(rendered.contains("1.27.0"))
        assertTrue(rendered.contains("REDACTED"))
    }

    @Test
    fun `gson export still serializes the real payload`() {
        val json = Gson().toJson(payload)
        assertTrue("export lost the apikey", json.contains(apiKey))
        assertTrue("export lost the Dojo URL", json.contains(onionUrl))
        assertTrue("export lost the explorer URL", json.contains(explorerUrl))

        val back = Gson().fromJson(json, DojoPairing::class.java)
        assertEquals(apiKey, back.pairing?.apikey)
        assertEquals(onionUrl, back.pairing?.url)
        assertEquals(explorerUrl, back.explorer?.url)
    }

    @Test
    fun `parsed payload toString is masked too`() {
        // The readPayload() path: the object comes back from Gson, and
        // any stray log of it must still render masked.
        val parsed = Gson().fromJson(Gson().toJson(payload), DojoPairing::class.java)
        assertFalse(parsed.toString().contains(apiKey))
        assertFalse(parsed.toString().contains(onionUrl))
    }
}
