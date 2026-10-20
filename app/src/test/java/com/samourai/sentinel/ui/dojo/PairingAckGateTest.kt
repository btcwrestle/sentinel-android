package com.samourai.sentinel.ui.dojo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * #234: pins the pairing acknowledgment gate. The old success arm
 * committed the pairing inline (prefs, tokens, payload store) and
 * auto-advanced: the listener fired 500 ms later and NetworkActivity
 * started the xpub registration burst with no user gate anywhere.
 *
 * These legs are the old bug inverted: a path from pairing success to
 * listener-fire that does not pass through onUserConfirmed(), or a
 * double-tap window that runs the commit twice, fails this suite.
 *
 * Fixture domains are RFC 2606 reserved .example hosts: the CI remnant
 * guard (#68) greps app/src for samourai.(io|com) substrings, and any
 * realistic-looking host carrying that suffix fails the build.
 */
class PairingAckGateTest {

    //
    // Leg 1: success arms the gate exactly once.
    //

    @Test
    fun `pairing success arms the confirmation once and a re-fire is a no-op`() {
        val gate = PairingAckGate()
        assertEquals(PairingAckDecision.SHOW_CONFIRMATION, gate.onPairingSuccess())
        // Any re-fire landing while the dialog is already up: one
        // dialog, one decision.
        assertEquals(PairingAckDecision.NOOP, gate.onPairingSuccess())
    }

    //
    // Leg 2: each terminal tap resolves exactly once; every racing or
    // repeat tap is a no-op.
    //

    @Test
    fun `confirm commits and dismisses exactly once`() {
        val gate = PairingAckGate()
        gate.onPairingSuccess()
        assertEquals(PairingAckDecision.COMMIT_AND_DISMISS, gate.onUserConfirmed())
        // The dialog dismisses on the first tap; a double-tap that
        // lands before the window closes must not run the commit twice.
        assertEquals(PairingAckDecision.NOOP, gate.onUserConfirmed())
    }

    @Test
    fun `decline discards and dismisses exactly once`() {
        val gate = PairingAckGate()
        gate.onPairingSuccess()
        assertEquals(PairingAckDecision.DISCARD_AND_DISMISS, gate.onUserDeclined())
        assertEquals(PairingAckDecision.NOOP, gate.onUserDeclined())
    }

    @Test
    fun `decline after confirm is a no-op`() {
        val gate = PairingAckGate()
        gate.onPairingSuccess()
        gate.onUserConfirmed()
        assertEquals(PairingAckDecision.NOOP, gate.onUserDeclined())
    }

    @Test
    fun `confirm after decline is a no-op`() {
        val gate = PairingAckGate()
        gate.onPairingSuccess()
        gate.onUserDeclined()
        assertEquals(PairingAckDecision.NOOP, gate.onUserConfirmed())
    }

    @Test
    fun `a stray success after the user declined never re-arms`() {
        val gate = PairingAckGate()
        gate.onPairingSuccess()
        gate.onUserDeclined()
        // An in-flight retry POST answering 200 after the decline:
        // the user's decision stands, nothing arms again.
        assertEquals(PairingAckDecision.NOOP, gate.onPairingSuccess())
    }

    @Test
    fun `taps before any success are no-ops`() {
        val gate = PairingAckGate()
        assertEquals(PairingAckDecision.NOOP, gate.onUserConfirmed())
        assertEquals(PairingAckDecision.NOOP, gate.onUserDeclined())
    }

    //
    // Leg 3: the confirmation summary - payload-derived, apikey-free.
    //

    private val fullPayload = DojoPairing(
        pairing = Pairing(
            apikey = "secret-api-key",
            type = "dojo.api",
            url = "http://dojo.example",
            version = "1.19.1",
        ),
        explorer = Explorer(
            type = "btc_rpc_explorer",
            url = "http://explorer.example",
        ),
    )

    @Test
    fun `summary shows url version and explorer`() {
        assertEquals(
            listOf(
                "http://dojo.example",
                "Dojo 1.19.1",
                "Explorer: btc_rpc_explorer - http://explorer.example",
            ),
            pairingConfirmationLines(fullPayload),
        )
    }

    @Test
    fun `summary drops blanks and never contains the apikey`() {
        val bare = DojoPairing(
            pairing = Pairing(url = "http://onlyurl.example"),
            explorer = null,
        )
        assertEquals(listOf("http://onlyurl.example"), pairingConfirmationLines(bare))
        val untypedExplorer = DojoPairing(
            pairing = Pairing(url = "http://onlyurl.example"),
            explorer = Explorer(type = "", url = "http://expl.example"),
        )
        assertEquals(
            listOf(
                "http://onlyurl.example",
                "Explorer: explorer - http://expl.example",
            ),
            pairingConfirmationLines(untypedExplorer),
        )
        // The apikey grants access to the node; it must never reach the
        // dialog (see DojoUtility.getPairing's FLAG_SECURE note).
        pairingConfirmationLines(fullPayload).forEach { line ->
            assertFalse(line.contains("secret-api-key"))
        }
    }
}
