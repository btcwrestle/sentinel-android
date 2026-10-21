package com.samourai.sentinel.ui.dojo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 *
 * #256: the suite also pins the pairing dialogs' display data - the
 * pre-pair detail rows (payload + directory snapshot; absent means
 * absent, never a placeholder) and the post-pair receipt's identity
 * ladder (directory flag + name, manual URL, never blank). Pure
 * functions, no Android machinery.
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
    fun `a stray success after confirm is a no-op`() {
        val gate = PairingAckGate()
        gate.onPairingSuccess()
        gate.onUserConfirmed()
        // An in-flight re-fire answering 200 after the commit has run
        // and the receipt flow has begun: the decision stands, nothing
        // re-arms - not behind the pre-pair dialog, not behind the
        // receipt.
        assertEquals(PairingAckDecision.NOOP, gate.onPairingSuccess())
    }

    @Test
    fun `taps before any success are no-ops`() {
        val gate = PairingAckGate()
        assertEquals(PairingAckDecision.NOOP, gate.onUserConfirmed())
        assertEquals(PairingAckDecision.NOOP, gate.onUserDeclined())
    }

    //
    // Leg 3 (#256): the dialogs' display data - detail rows, header
    // guard, identity ladder. Fixtures are RFC 2606 .example hosts.
    //

    private val communityPairing = DojoPairing(
        pairing = Pairing(
            apikey = "secret-api-key",
            type = "dojo.api",
            url = "http://community.example",
            version = "1.19.1",
        ),
        explorer = Explorer(
            type = "btc_rpc_explorer",
            url = "http://explorer.example",
        ),
    )

    private val communitySnapshot = DojoDisplaySnapshot(
        url = "http://community.example",
        name = "Directory Dojo",
        flag = "SG",
        jurisdiction = "Singapore",
    )

    private val manualPairing = DojoPairing(
        pairing = Pairing(url = "http://manual.example"),
        explorer = null,
    )

    @Test
    fun `detail rows carry directory metadata for a community pairing`() {
        assertEquals(
            DojoDirectoryHeader(flag = "SG", name = "Directory Dojo"),
            directoryHeader(communitySnapshot, communityPairing.pairing?.url),
        )
        assertEquals(
            PairingDetailRows(
                jurisdiction = "Singapore",
                url = "http://community.example",
                version = "1.19.1",
                explorer = "http://explorer.example",
            ),
            pairingDetailRows(communityPairing, communitySnapshot),
        )
    }

    @Test
    fun `detail rows omit directory metadata for a manual pairing`() {
        // The payload carries none of it; absent means absent, never a
        // placeholder row.
        assertNull(directoryHeader(null, manualPairing.pairing?.url))
        assertEquals(
            PairingDetailRows(
                jurisdiction = null,
                url = "http://manual.example",
                version = null,
                explorer = null,
            ),
            pairingDetailRows(manualPairing, null),
        )
    }

    @Test
    fun `a stale snapshot never renders`() {
        // Directory metadata snapshotted for a different node: the
        // url-match guard keeps it out of every dialog - a stale
        // directory label can never ride this pairing.
        val stale = communitySnapshot.copy(url = "http://elsewhere.example")
        assertNull(directoryHeader(stale, communityPairing.pairing?.url))
        assertNull(pairingDetailRows(communityPairing, stale).jurisdiction)
        assertEquals(
            "http://community.example",
            pairingIdentityLine(communityPairing.pairing?.url, stale, "Unnamed Dojo"),
        )
    }

    @Test
    fun `detail rows drop absent optional payload rows`() {
        val bare = DojoPairing(
            pairing = Pairing(url = "http://onlyurl.example"),
            explorer = null,
        )
        val rows = pairingDetailRows(bare, null)
        // The URL row is the one guarantee (validate); version,
        // explorer and jurisdiction are absent, not blank-rendered.
        assertEquals("http://onlyurl.example", rows.url)
        assertNull(rows.version)
        assertNull(rows.explorer)
        assertNull(rows.jurisdiction)
    }

    @Test
    fun `identity ladder resolves community nameless manual and never blank`() {
        // 1. Directory pairing: flag + name.
        assertEquals(
            "SG Directory Dojo",
            pairingIdentityLine("http://community.example", communitySnapshot, "Unnamed Dojo"),
        )
        // 1b. Directory pairing, nameless node: flag + the unnamed
        // fallback (R.string.dojo_unnamed at runtime).
        assertEquals(
            "SG Unnamed Dojo",
            pairingIdentityLine(
                "http://community.example",
                communitySnapshot.copy(name = null),
                "Unnamed Dojo",
            ),
        )
        // 2. Manual/QR pairing: the URL - the node's only identity in
        // the payload.
        assertEquals(
            "http://manual.example",
            pairingIdentityLine("http://manual.example", null, "Unnamed Dojo"),
        )
        // 3. Never blank: no URL, no snapshot - the fallback lands.
        assertEquals(
            "Unnamed Dojo",
            pairingIdentityLine(null, null, "Unnamed Dojo"),
        )
    }

    @Test
    fun `blank snapshot fields are dropped, never rendered`() {
        // Blank is null's twin under the takeIf filters: the guards
        // drop it, no row or glyph renders for it.
        val blanks = DojoDisplaySnapshot(
            url = "http://community.example",
            name = " ",
            flag = "",
            jurisdiction = "  ",
        )
        assertNull(directoryHeader(blanks, "http://community.example"))
        assertNull(pairingDetailRows(communityPairing, blanks).jurisdiction)
        assertEquals(
            "Unnamed Dojo",
            pairingIdentityLine("http://community.example", blanks, "Unnamed Dojo"),
        )
    }

    @Test
    fun `the apikey never reaches any dialog data`() {
        // It grants access to the node (see DojoUtility.getPairing's
        // FLAG_SECURE note); rows, header and identity carry none of
        // it. Pinned so a future field addition fails loudly.
        val rows = pairingDetailRows(communityPairing, communitySnapshot)
        listOf(rows.jurisdiction, rows.url, rows.version, rows.explorer).forEach { value ->
            assertFalse(value?.contains("secret-api-key") == true)
        }
        val header = directoryHeader(communitySnapshot, communityPairing.pairing?.url)
        assertFalse(header?.flag?.contains("secret-api-key") == true)
        assertFalse(header?.name?.contains("secret-api-key") == true)
    }
}
