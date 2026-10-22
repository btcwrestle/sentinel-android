package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.core.DerivedNetwork
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #250: every row of the pairing-path network decision table, the
 * #250/#272 agreement pinned without Android machinery - the
 * PairingAckGate/PairingTorGate precedent. Evidence: declared
 * (directory label, contract) beats whispered (URL mount,
 * convention); neither means no evidence at all.
 */
class PairingNetworkDecisionTest {

    @Test
    fun `no evidence proceeds on established wallet`() {
        assertEquals(
            PairingNetworkDecision.Proceed,
            pairingNetworkDecision(
                appEstablished = true,
                appIsTestnet = false,
                whisper = null,
                declared = null
            )
        )
    }

    @Test
    fun `no evidence proceeds on empty wallet`() {
        assertEquals(
            PairingNetworkDecision.Proceed,
            pairingNetworkDecision(
                appEstablished = false,
                appIsTestnet = true,
                whisper = null,
                declared = null
            )
        )
    }

    @Test
    fun `declared evidence matching established network proceeds`() {
        assertEquals(
            PairingNetworkDecision.Proceed,
            pairingNetworkDecision(
                appEstablished = true,
                appIsTestnet = true,
                whisper = null,
                declared = DerivedNetwork.TESTNET
            )
        )
    }

    @Test
    fun `whispered evidence matching established network proceeds`() {
        assertEquals(
            PairingNetworkDecision.Proceed,
            pairingNetworkDecision(
                appEstablished = true,
                appIsTestnet = true,
                whisper = DerivedNetwork.TESTNET,
                declared = null
            )
        )
    }

    @Test
    fun `evidence matching app network proceeds when not established`() {
        assertEquals(
            PairingNetworkDecision.Proceed,
            pairingNetworkDecision(
                appEstablished = false,
                appIsTestnet = false,
                whisper = null,
                declared = DerivedNetwork.MAINNET
            )
        )
    }

    @Test
    fun `empty wallet plus whispered testnet dojo sets testnet`() {
        assertEquals(
            PairingNetworkDecision.ProceedAndSetNetwork(targetIsTestnet = true),
            pairingNetworkDecision(
                appEstablished = false,
                appIsTestnet = false,
                whisper = DerivedNetwork.TESTNET,
                declared = null
            )
        )
    }

    @Test
    fun `empty wallet plus declared mainnet dojo sets mainnet`() {
        assertEquals(
            PairingNetworkDecision.ProceedAndSetNetwork(targetIsTestnet = false),
            pairingNetworkDecision(
                appEstablished = false,
                appIsTestnet = true,
                whisper = null,
                declared = DerivedNetwork.MAINNET
            )
        )
    }

    @Test
    fun `established declared mismatch refuses`() {
        assertEquals(
            PairingNetworkDecision.Refuse,
            pairingNetworkDecision(
                appEstablished = true,
                appIsTestnet = false,
                whisper = null,
                declared = DerivedNetwork.TESTNET
            )
        )
    }

    @Test
    fun `established whispered mismatch confirms loudly`() {
        assertEquals(
            PairingNetworkDecision.ConfirmMismatch,
            pairingNetworkDecision(
                appEstablished = true,
                appIsTestnet = false,
                whisper = DerivedNetwork.TESTNET,
                declared = null
            )
        )
    }

    @Test
    fun `declared beats whisper on conflict`() {
        assertEquals(
            PairingNetworkDecision.Proceed,
            pairingNetworkDecision(
                appEstablished = true,
                appIsTestnet = false,
                whisper = DerivedNetwork.TESTNET,
                declared = DerivedNetwork.MAINNET
            )
        )
    }

    @Test
    fun `refuse fires both directions when declared`() {
        assertEquals(
            PairingNetworkDecision.Refuse,
            pairingNetworkDecision(
                appEstablished = true,
                appIsTestnet = true,
                whisper = null,
                declared = DerivedNetwork.MAINNET
            )
        )
    }

    @Test
    fun `testnet label normalizes`() {
        assertEquals(DerivedNetwork.TESTNET, normalizeDeclaredNetwork("testnet"))
    }

    @Test
    fun `mainnet label normalizes case-insensitively`() {
        assertEquals(DerivedNetwork.MAINNET, normalizeDeclaredNetwork("Mainnet"))
    }

    @Test
    fun `label trims whitespace`() {
        assertEquals(DerivedNetwork.TESTNET, normalizeDeclaredNetwork(" testnet "))
    }

    @Test
    fun `signet is no declaration`() {
        assertEquals(null, normalizeDeclaredNetwork("signet"))
    }

    @Test
    fun `regtest is no declaration`() {
        assertEquals(null, normalizeDeclaredNetwork("regtest"))
    }

    @Test
    fun `null and blank are no declaration`() {
        assertEquals(null, normalizeDeclaredNetwork(null))
        assertEquals(null, normalizeDeclaredNetwork(""))
    }
}
