package com.samourai.sentinel.ui.dojo

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DojoPairingParseTest {

    // Synthetic payload, field shape observed on the wire (#119 amendment
    // comment); no real keys, no real hosts.
    private val withExplorer = """
        {"pairing":{"type":"dojo.api","version":"1.27.0","apikey":"synthetic",
         "url":"http://synthetic.onion/v2"},
         "explorer":{"type":"explorer.btc_rpc_explorer",
         "url":"http://synthetic-explorer.onion"}}
    """.trimIndent()

    @Test
    fun `explorer object is captured not dropped`() {
        val p = Gson().fromJson(withExplorer, DojoPairing::class.java)
        assertEquals("http://synthetic-explorer.onion", p.explorer?.url)
        assertEquals("explorer.btc_rpc_explorer", p.explorer?.type)
    }

    @Test
    fun `payloads without explorer parse to null explorer`() {
        val p = Gson().fromJson(
            """{"pairing":{"type":"dojo.api","apikey":"k","url":"http://s.onion/v2"}}""",
            DojoPairing::class.java,
        )
        assertNull(p.explorer)
    }
}
