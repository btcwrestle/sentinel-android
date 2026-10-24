package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.helpers.fromJSON
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pairing gate. [DojoUtility.validate] delegates to
 * [validateDojoPairingPayload]; these cases run that function on
 * real JSON, including the Gson empty-string default for omitted
 * fields (#302).
 */
class DojoPairingValidationTest {

    private fun pairing(
        url: String? = "http://synthetic.onion/v2",
        apikey: String? = "synthetic-apikey",
        type: String? = "dojo.api",
        includeUrl: Boolean = true,
        includeApikey: Boolean = true,
    ): String {
        val body = JSONObject()
        if (type != null) body.put("type", type)
        if (includeApikey) {
            if (apikey == null) body.put("apikey", JSONObject.NULL)
            else body.put("apikey", apikey)
        }
        if (includeUrl) {
            if (url == null) body.put("url", JSONObject.NULL)
            else body.put("url", url)
        }
        return JSONObject().put("pairing", body).toString()
    }

    @Test
    fun `onion http pairing is accepted`() {
        assertTrue(validateDojoPairingPayload(pairing()))
        assertTrue(validateDojoPairingPayload(pairing(url = "http://synthetic.onion")))
        assertTrue(validateDojoPairingPayload(pairing(url = "http://SYNTHETIC.ONION/v2")))
        assertTrue(validateDojoPairingPayload(pairing(url = "https://synthetic.onion/v2")))
        assertTrue(
            validateDojoPairingPayload(
                pairing(url = "http://synthetic.onion:8080/test/v2")
            )
        )
        val host56 = ("abcdefghijklmnopqrstuvwxyz234567".repeat(2)).take(56)
        assertTrue(
            validateDojoPairingPayload(
                pairing(url = "http://$host56.onion/test/v2")
            )
        )
        assertTrue(
            validateDojoPairingPayload(
                pairing(url = "http://example.com@synthetic.onion/v2")
            )
        )
    }

    @Test
    fun `https clearnet pairing is accepted`() {
        assertTrue(
            validateDojoPairingPayload(
                pairing(url = "https://dojo.example.com/v2")
            )
        )
        assertTrue(
            validateDojoPairingPayload(
                pairing(url = "https://dojo.example.com")
            )
        )
    }

    @Test
    fun `http clearnet pairing is rejected`() {
        assertFalse(validateDojoPairingPayload(pairing(url = "http://dojo.example.com/v2")))
        assertFalse(validateDojoPairingPayload(pairing(url = "http://dojo.example.com")))
        assertFalse(validateDojoPairingPayload(pairing(url = "http://192.168.1.10/v2")))
        assertFalse(validateDojoPairingPayload(pairing(url = "http://10.1.2.3:8080/v2")))
        assertFalse(validateDojoPairingPayload(pairing(url = "http://[2001:db8::1]/v2")))
        assertFalse(validateDojoPairingPayload(pairing(url = "http://localhost/v2")))
        assertFalse(validateDojoPairingPayload(pairing(url = "http://127.0.0.1/v2")))
        assertFalse(validateDojoPairingPayload(pairing(url = "HTTP://dojo.example.com/v2")))
        // Host, not a substring. These all contain "onion" and must still fail.
        assertFalse(validateDojoPairingPayload(pairing(url = "http://onion.example/v2")))
        assertFalse(validateDojoPairingPayload(pairing(url = "http://synthetic.onion.example/v2")))
        assertFalse(validateDojoPairingPayload(pairing(url = "http://notonion/v2")))
        assertFalse(validateDojoPairingPayload(pairing(url = "http://example.com/synthetic.onion")))
        assertFalse(validateDojoPairingPayload(pairing(url = "http://onion/v2")))
        assertFalse(
            validateDojoPairingPayload(
                pairing(url = "http://synthetic.onion@example.com/v2")
            )
        )
    }

    @Test
    fun `payload missing url or apikey is rejected`() {
        val omitted = """{"pairing":{"type":"dojo.api","version":"1.0.0"}}"""
        val parsed = fromJSON<DojoPairing>(omitted)
        assertNotNull(parsed)
        assertEquals("dojo.api", parsed!!.pairing?.type)
        assertTrue(parsed.pairing?.url.isNullOrBlank())
        assertTrue(parsed.pairing?.apikey.isNullOrBlank())
        assertFalse(validateDojoPairingPayload(omitted))

        assertFalse(
            validateDojoPairingPayload(
                """{"pairing":{"type":"dojo.api","apikey":"synthetic"}}"""
            )
        )
        assertFalse(
            validateDojoPairingPayload(
                """{"pairing":{"type":"dojo.api","url":"http://synthetic.onion/v2"}}"""
            )
        )
        assertFalse(validateDojoPairingPayload(pairing(url = null)))
        assertFalse(validateDojoPairingPayload(pairing(apikey = null)))
        assertFalse(validateDojoPairingPayload(pairing(includeUrl = false)))
        assertFalse(validateDojoPairingPayload(pairing(includeApikey = false)))
    }

    @Test
    fun `blank url or apikey is rejected`() {
        assertFalse(validateDojoPairingPayload(pairing(url = "")))
        assertFalse(validateDojoPairingPayload(pairing(url = "   ")))
        assertFalse(validateDojoPairingPayload(pairing(apikey = "")))
        assertFalse(validateDojoPairingPayload(pairing(apikey = "   ")))
        // OkHttp skips surrounding whitespace, so padding must not turn a
        // clearnet host into an accepted URL.
        assertFalse(validateDojoPairingPayload(pairing(url = " http://dojo.example.com/v2 ")))
        assertTrue(validateDojoPairingPayload(pairing(url = " http://synthetic.onion/v2 ")))
    }

    @Test
    fun `unparseable or wrong-type payload is rejected`() {
        assertFalse(validateDojoPairingPayload(""))
        assertFalse(validateDojoPairingPayload("   "))
        assertFalse(validateDojoPairingPayload("not json"))
        assertFalse(validateDojoPairingPayload("{"))
        assertFalse(validateDojoPairingPayload("{}"))
        assertFalse(validateDojoPairingPayload("""{"pairing":null}"""))
        assertFalse(validateDojoPairingPayload(pairing(type = "dojo")))
        assertFalse(validateDojoPairingPayload(pairing(type = "")))
        assertFalse(validateDojoPairingPayload(pairing(url = "ftp://synthetic.onion/v2")))
        assertFalse(validateDojoPairingPayload(pairing(url = "synthetic.onion/v2")))
        assertFalse(validateDojoPairingPayload(pairing(url = "http:///v2")))
    }

    @Test
    fun `extra explorer fields do not change the decision`() {
        val raw = """
            {"pairing":{"type":"dojo.api","version":"1.27.0","apikey":"synthetic",
             "url":"http://synthetic.onion/v2"},
             "explorer":{"type":"explorer.btc_rpc_explorer",
             "url":"http://synthetic-explorer.onion"}}
        """.trimIndent()
        assertTrue(validateDojoPairingPayload(raw))
    }

    @Test
    fun `endpoint helper matches the pairing url rule`() {
        assertTrue(isAcceptableDojoPairingUrl("http://synthetic.onion/v2"))
        assertTrue(isAcceptableDojoPairingUrl("https://dojo.example.com/v2"))
        assertFalse(isAcceptableDojoPairingUrl("http://dojo.example.com/v2"))
        assertFalse(isAcceptableDojoPairingUrl(null))
        assertFalse(isAcceptableDojoPairingUrl(""))
        assertFalse(isAcceptableDojoPairingUrl("   "))
    }
}
