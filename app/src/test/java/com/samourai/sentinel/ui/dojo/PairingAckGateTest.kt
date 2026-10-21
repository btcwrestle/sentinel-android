package com.samourai.sentinel.ui.dojo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #234/#261: pins the pairing decision gate. The old success arm of
 * setDojoPost committed the pairing inline and auto-advanced; #234 put
 * the commit behind an explicit tap - but the tap still came AFTER the
 * /auth/login POST, so the apikey left the device and the attempt sat
 * in the Dojo's logs before the user decided anything. #261 inverts
 * the order: selection arms the confirmation dialog with ZERO network
 * contact, decline is zero contact, and Pair spends the gate on the
 * connect - the first network byte of the flow. One dialog, one
 * decision, one POST per sheet; every racing or repeat event is a
 * no-op.
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
    // Leg 1 (#261): selection arms the gate exactly once - the dialog
    // opens before any network contact, once per sheet.
    //

    @Test
    fun `selection arms the confirmation once and a re-selection is a no-op`() {
        val gate = PairingAckGate()
        assertEquals(PairingAckDecision.SHOW_CONFIRMATION, gate.onPairingSelected())
        // Any re-selection landing while the dialog is up: one
        // dialog, one decision, one sheet.
        assertEquals(PairingAckDecision.NOOP, gate.onPairingSelected())
    }

    //
    // Leg 2: each terminal tap resolves exactly once; every racing or
    // repeat tap is a no-op. Pair spends the gate on the connect (the
    // first network contact of the flow); decline spends it with zero
    // contact.
    //

    @Test
    fun `confirm starts the connect exactly once`() {
        val gate = PairingAckGate()
        gate.onPairingSelected()
        assertEquals(PairingAckDecision.START_CONNECT, gate.onUserConfirmed())
        // The dialog dismisses on the first tap; a double-tap that
        // lands before the window closes must not fire a second POST.
        assertEquals(PairingAckDecision.NOOP, gate.onUserConfirmed())
    }

    @Test
    fun `decline discards and dismisses exactly once`() {
        val gate = PairingAckGate()
        gate.onPairingSelected()
        assertEquals(PairingAckDecision.DISCARD_AND_DISMISS, gate.onUserDeclined())
        assertEquals(PairingAckDecision.NOOP, gate.onUserDeclined())
    }

    @Test
    fun `decline after confirm is a no-op`() {
        val gate = PairingAckGate()
        gate.onPairingSelected()
        gate.onUserConfirmed()
        assertEquals(PairingAckDecision.NOOP, gate.onUserDeclined())
    }

    @Test
    fun `confirm after decline is a no-op`() {
        val gate = PairingAckGate()
        gate.onPairingSelected()
        gate.onUserDeclined()
        assertEquals(PairingAckDecision.NOOP, gate.onUserConfirmed())
    }

    @Test
    fun `a stray re-selection after the decision never re-arms`() {
        val gate = PairingAckGate()
        gate.onPairingSelected()
        gate.onUserConfirmed()
        // A second selection fire after Pair: spent is spent, nothing
        // arms again - not behind the POST, not behind the receipt.
        assertEquals(PairingAckDecision.NOOP, gate.onPairingSelected())
        val declinedGate = PairingAckGate()
        declinedGate.onPairingSelected()
        declinedGate.onUserDeclined()
        // Same law on the decline arm: the Dojo never hears a whisper.
        assertEquals(PairingAckDecision.NOOP, declinedGate.onPairingSelected())
    }

    @Test
    fun `taps before any selection are no-ops`() {
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
