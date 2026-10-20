package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.api.TlsPolicy
import com.samourai.sentinel.tor.EnumTorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * #233: pins the pairing client's Tor gate. The deleted line built its
 * client as proxy(SentinelTorManager.getProxy()) - null until the SOCKS
 * listener publishes, and ON alone publishes at boot=5 (#135) - so
 * proxy(null) meant OkHttp's DEFAULT route: DIRECT, and /auth/login
 * with the apikey body left via the device's normal network.
 *
 * These legs are the old bug inverted: broadening the gate again (a
 * POST_NOW without a live proxy, a direct builder anywhere) fails
 * this suite.
 */
class PairingTorGateTest {

    private val socks = Proxy(
        Proxy.Type.SOCKS,
        InetSocketAddress("127.0.0.1", 9050),
    )

    //
    // Leg 1: the decision never reaches POST_NOW without a live proxy.
    //

    @Test
    fun `ON but not ready never reaches the client builder`() {
        // The exact leak window: ON publishes at boot=5, before the
        // SOCKS listener exists. The old code built the client right
        // here and POSTed direct.
        assertEquals(
            PairingTorDecision.DEFER,
            pairingTorDecision(EnumTorState.ON, 5, null),
        )
        // And buildPairingClient takes a non-null Proxy by type: there
        // is no buildPairingClient(null) to call. The direct-POST route
        // is gone at the signature, reachable only from POST_NOW.
    }

    @Test
    fun `ON at boot 100 with the listener published posts now`() {
        assertEquals(
            PairingTorDecision.POST_NOW,
            pairingTorDecision(EnumTorState.ON, 100, socks),
        )
    }

    @Test
    fun `boot 100 with ON but a null proxy still defers`() {
        // boot racing ahead of the listener: still never POST_NOW.
        assertEquals(
            PairingTorDecision.DEFER,
            pairingTorDecision(EnumTorState.ON, 100, null),
        )
    }

    @Test
    fun `starting defers`() {
        assertEquals(
            PairingTorDecision.DEFER,
            pairingTorDecision(EnumTorState.STARTING, 0, null),
        )
    }

    @Test
    fun `off and stopping fail closed`() {
        assertEquals(
            PairingTorDecision.FAIL_CLOSED,
            pairingTorDecision(EnumTorState.OFF, 0, null),
        )
        assertEquals(
            PairingTorDecision.FAIL_CLOSED,
            pairingTorDecision(EnumTorState.STOPPING, 0, null),
        )
    }

    //
    // Leg 2: the built client carries the proxy and the scoped policy.
    //

    @Test
    fun `pairing client routes through the SOCKS proxy`() {
        val client = buildPairingClient(socks)
        assertEquals(socks, client.proxy)
    }

    @Test
    fun `pairing client wires the scoped TLS verifier`() {
        val client = buildPairingClient(socks)
        assertTrue(client.hostnameVerifier === TlsPolicy.hostnameVerifier)
    }

    //
    // Leg 3: Tor-mode timeouts mirrored from ApiService's ON arm -
    // OkHttp's default 10 s callTimeout kills an onion circuit build.
    // OkHttp 4.12's client exposes these as *Millis Int vals
    // (callTimeoutMillis etc. - verified against the pinned 4.12
    // source, OkHttpClient.kt; the Duration overloads live on the
    // Builder only).
    //

    @Test
    fun `pairing client has no call timeout ceiling and Tor-tuned socket timeouts`() {
        val client = buildPairingClient(socks)
        assertEquals(0, client.callTimeoutMillis)
        assertEquals(180_000, client.readTimeoutMillis)
        assertEquals(180_000, client.writeTimeoutMillis)
        assertEquals(180_000, client.connectTimeoutMillis)
    }
}
