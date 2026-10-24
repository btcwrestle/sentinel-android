package com.samourai.sentinel.api

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.net.ssl.SSLContext

/**
 * #229: pins the host-scoping contract that replaced the blanket
 * trust-all + accept-any-hostname pair previously installed whenever
 * Tor was ON.
 *
 * These legs are the old bug inverted: the deleted verifier returned
 * true for EVERY host, and the deleted trust manager accepted every
 * chain. Broadening the relaxation again fails this suite.
 */
class TlsPolicyTest {

    //
    // Leg 1: the scoping predicate.
    //

    @Test
    fun `onion host relaxes`() {
        assertTrue(
            TlsPolicy.shouldRelax(
                "dojobayeryasshgghz537de5ckgd5hhi4z5sdeil3roeh65fwhdnu2yd.onion"
            )
        )
    }

    @Test
    fun `uppercase onion relaxes`() {
        assertTrue(TlsPolicy.shouldRelax("SOMETHING.ONION"))
    }

    @Test
    fun `clearnet host stays strict`() {
        assertFalse(TlsPolicy.shouldRelax("api.coingecko.com"))
    }

    @Test
    fun `onion as a path segment or inner label stays strict`() {
        // The old WebUtil gate was a substring match; these must NOT relax.
        assertFalse(TlsPolicy.shouldRelax("example.com"))
        assertFalse(TlsPolicy.shouldRelax("onion.example.com"))
        assertFalse(TlsPolicy.shouldRelax("notonion.onion.example.com"))
        assertFalse(TlsPolicy.shouldRelax("fakeonion"))
    }

    @Test
    fun `null or empty host fails closed to strict`() {
        assertFalse(TlsPolicy.shouldRelax(null))
        assertFalse(TlsPolicy.shouldRelax(""))
    }

    //
    // Leg 2: the hostname verifier - the direct inverse of the old bug.
    //

    @Test
    fun `clearnet host is NOT accepted by the scoped verifier`() {
        // Old code: HostnameVerifier { _, _ -> true } -> this returned true,
        // which is precisely how a malicious exit spoofed any clearnet host.
        assertFalse(TlsPolicy.hostnameVerifier.verify("api.coingecko.com", null))
    }

    @Test
    fun `onion host is accepted by the scoped verifier`() {
        assertTrue(
            TlsPolicy.hostnameVerifier.verify(
                "dojobayeryasshgghz537de5ckgd5hhi4z5sdeil3roeh65fwhdnu2yd.onion",
                null
            )
        )
    }

    //
    // Leg 3: chain trust really delegates to the system default for
    // non-onion peers (an engine without a peer host is not an onion).
    //

    @Test
    fun `clearnet peer keeps default verification - empty chain rejected`() {
        val trustManager = TlsPolicy.OnionScopedTrustManager()
        val engine = SSLContext.getDefault().createSSLEngine()
        try {
            trustManager.checkServerTrusted(
                emptyArray<java.security.cert.X509Certificate>(),
                "TLS",
                engine
            )
            fail("system default must reject a zero-length chain for a clearnet peer")
        } catch (expected: Exception) {
            // CertificateException or IllegalArgumentException depending on
            // JDK - either way, the chain was REJECTED, not accepted.
        }
    }

    //
    // Leg 4: install() actually puts the policy on the client.
    //

    @Test
    fun `install wires the scoped verifier into the client`() {
        val builder = OkHttpClient.Builder()
        TlsPolicy.install(builder)
        val client = builder.build()
        assertTrue(client.hostnameVerifier === TlsPolicy.hostnameVerifier)
    }
}
