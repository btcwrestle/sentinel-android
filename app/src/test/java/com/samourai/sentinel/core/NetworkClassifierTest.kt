package com.samourai.sentinel.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #42: network state must derive from the keychain, not from a
 * first-run dialog default. The classifier is the derivation
 * primitive: version-byte prefixes are unambiguous for the
 * xpub family.
 *
 * UNKNOWN is load-bearing: defaulting unrecognized input to
 * MAINNET would recreate #42's shape (a wrong verdict that
 * rejects correct keys). Unknown input must stay unknown.
 */
class NetworkClassifierTest {

    @Test
    fun `tpub derives testnet`() {
        assertEquals(
            DerivedNetwork.TESTNET,
            NetworkClassifier.fromXpub("tpubDE8Lou1dA9nLc")
        )
    }

    @Test
    fun `upub derives testnet`() {
        assertEquals(
            DerivedNetwork.TESTNET,
            NetworkClassifier.fromXpub("upub5P6Bf")
        )
    }

    @Test
    fun `vpub derives testnet`() {
        assertEquals(
            DerivedNetwork.TESTNET,
            NetworkClassifier.fromXpub("vpub5Vhkk")
        )
    }

    @Test
    fun `xpub derives mainnet`() {
        assertEquals(
            DerivedNetwork.MAINNET,
            NetworkClassifier.fromXpub("xpub661MyMxAeaAR")
        )
    }

    @Test
    fun `ypub derives mainnet`() {
        assertEquals(
            DerivedNetwork.MAINNET,
            NetworkClassifier.fromXpub("ypub6WZub")
        )
    }

    @Test
    fun `zpub derives mainnet`() {
        assertEquals(
            DerivedNetwork.MAINNET,
            NetworkClassifier.fromXpub("zpub6rFR7")
        )
    }

    /**
     * Case-insensitivity is inherited from the legacy
     * isPublicKeyTesnet (payload.lowercase().startsWith) — the
     * wrapper must not change behavior for mixed-case input.
     */
    @Test
    fun `prefix match is case-insensitive`() {
        assertEquals(
            DerivedNetwork.TESTNET,
            NetworkClassifier.fromXpub("VPub5Vhkk")
        )
    }

    @Test
    fun `empty string is unknown`() {
        assertEquals(DerivedNetwork.UNKNOWN, NetworkClassifier.fromXpub(""))
    }

    @Test
    fun `bare pub prefix is unknown`() {
        assertEquals(DerivedNetwork.UNKNOWN, NetworkClassifier.fromXpub("pubDE8"))
    }

    /**
     * Address-like input must be UNKNOWN, never MAINNET. This is
     * the case that guards the bug's shape: a heuristic that
     * answers "mainnet" for anything unrecognized would silently
     * misflag wallets again.
     */
    @Test
    fun `address-like string is unknown not mainnet`() {
        assertEquals(
            DerivedNetwork.UNKNOWN,
            NetworkClassifier.fromXpub("1AbCdefGhijKlMnOpQrStUvWxYz")
        )
    }

    @Test
    fun `bech32-looking string is unknown not testnet`() {
        assertEquals(
            DerivedNetwork.UNKNOWN,
            NetworkClassifier.fromXpub("tb1qw508d6qejxtdg4y5r3zarvary0c5")
        )
    }

    @Test
    fun `raw hex blob is unknown`() {
        assertEquals(
            DerivedNetwork.UNKNOWN,
            NetworkClassifier.fromXpub("0488B21E043587CF")
        )
    }

    /**
     * #42 vote aggregation — the derivation rule over a wallet's keys.
     * UNKNOWN abstains; empty, mixed, or all-UNKNOWN yields null
     * (no derivation, keep current state); unanimous yields the network.
     * Null-on-mixed is load-bearing: a deliberately mixed wallet must
     * not have its state silently overwritten.
     */
    @Test
    fun `aggregate of empty votes yields null`() {
        assertEquals(null, NetworkClassifier.aggregate(emptyList()))
    }

    @Test
    fun `aggregate of one testnet vote is testnet`() {
        assertEquals(
            DerivedNetwork.TESTNET,
            NetworkClassifier.aggregate(listOf(DerivedNetwork.TESTNET))
        )
    }

    @Test
    fun `aggregate of one mainnet vote is mainnet`() {
        assertEquals(
            DerivedNetwork.MAINNET,
            NetworkClassifier.aggregate(listOf(DerivedNetwork.MAINNET))
        )
    }

    @Test
    fun `unknown votes abstain - unanimous testnet stands`() {
        assertEquals(
            DerivedNetwork.TESTNET,
            NetworkClassifier.aggregate(
                listOf(DerivedNetwork.TESTNET, DerivedNetwork.UNKNOWN)
            )
        )
    }

    @Test
    fun `unknown votes abstain - unanimous mainnet stands`() {
        assertEquals(
            DerivedNetwork.MAINNET,
            NetworkClassifier.aggregate(
                listOf(DerivedNetwork.UNKNOWN, DerivedNetwork.MAINNET)
            )
        )
    }

    @Test
    fun `mixed testnet and mainnet votes yield null`() {
        assertEquals(
            null,
            NetworkClassifier.aggregate(
                listOf(DerivedNetwork.TESTNET, DerivedNetwork.MAINNET)
            )
        )
    }

    @Test
    fun `all unknown votes yield null`() {
        assertEquals(
            null,
            NetworkClassifier.aggregate(
                listOf(DerivedNetwork.UNKNOWN, DerivedNetwork.UNKNOWN)
            )
        )
    }
}
