package com.samourai.sentinel.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * #208: the Network page row decision, choice A from the issue thread.
 *
 * A manual switch only sticks when #42's keychain derivation abstains
 * (an empty or mixed wallet); an established (unanimous) wallet pins
 * the network, and the row must not offer a switch the next launch
 * would silently revert. All four legs of the 2x2 are load-bearing
 * for the UI; plain JUnit, the NetworkClassifierTest precedent.
 */
class NetworkSwitchTest {

    @Test
    fun `empty wallet on mainnet offers switch to testnet`() {
        assertEquals(
            NetworkSwitch.Row.Switchable(targetIsTestnet = true),
            NetworkSwitch.rowState(currentIsTestnet = false, networkEstablished = false)
        )
    }

    @Test
    fun `empty wallet on testnet offers switch to mainnet`() {
        assertEquals(
            NetworkSwitch.Row.Switchable(targetIsTestnet = false),
            NetworkSwitch.rowState(currentIsTestnet = true, networkEstablished = false)
        )
    }

    @Test
    fun `established wallet pins the row - mainnet`() {
        assertSame(
            NetworkSwitch.Row.KeyPinned,
            NetworkSwitch.rowState(currentIsTestnet = false, networkEstablished = true)
        )
    }

    @Test
    fun `established wallet pins the row - testnet`() {
        assertSame(
            NetworkSwitch.Row.KeyPinned,
            NetworkSwitch.rowState(currentIsTestnet = true, networkEstablished = true)
        )
    }
}
