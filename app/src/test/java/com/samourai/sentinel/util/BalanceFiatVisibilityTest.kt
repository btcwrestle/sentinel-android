package com.samourai.sentinel.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceFiatVisibilityTest {

    @Test
    fun `fiat shows under BTC and sats when nothing hides it`() {
        assertTrue(BalanceDisplayFormatter.showsFiat(BalanceDisplayMode.BTC, streetMode = false, fiatDisabled = false))
        assertTrue(BalanceDisplayFormatter.showsFiat(BalanceDisplayMode.SATS, streetMode = false, fiatDisabled = false))
    }

    @Test
    fun `hide fiat setting hides fiat in every mode`() {
        BalanceDisplayMode.values().forEach {
            assertFalse(it.name, BalanceDisplayFormatter.showsFiat(it, streetMode = false, fiatDisabled = true))
        }
    }

    @Test
    fun `street mode hides fiat in every mode`() {
        BalanceDisplayMode.values().forEach {
            assertFalse(it.name, BalanceDisplayFormatter.showsFiat(it, streetMode = true, fiatDisabled = false))
        }
    }

    @Test
    fun `masked mode never shows fiat`() {
        assertFalse(BalanceDisplayFormatter.showsFiat(BalanceDisplayMode.MASKED, streetMode = false, fiatDisabled = false))
    }

    @Test
    fun `tapping cycles BTC then sats then masked and back`() {
        assertTrue(BalanceDisplayMode.BTC.next() == BalanceDisplayMode.SATS)
        assertTrue(BalanceDisplayMode.SATS.next() == BalanceDisplayMode.MASKED)
        assertTrue(BalanceDisplayMode.MASKED.next() == BalanceDisplayMode.BTC)
    }
}
