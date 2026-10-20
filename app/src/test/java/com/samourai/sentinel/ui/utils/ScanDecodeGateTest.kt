package com.samourai.sentinel.ui.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDecodeGateTest {

    @Test
    fun `first decode admits with no prior payload`() {
        assertTrue(
            shouldAdmitScan(
                lastPayload = null,
                lastAdmittedAtMs = 0L,
                payload = "garbage",
                nowMs = 500L
            )
        )
    }

    @Test
    fun `same payload inside the window is not admitted`() {
        assertFalse(shouldAdmitScan("garbage", 1000L, "garbage", 2999L))
    }

    @Test
    fun `same payload at the exact window edge is admitted`() {
        assertTrue(shouldAdmitScan("garbage", 1000L, "garbage", 3000L))
    }

    @Test
    fun `same payload at the same instant is not admitted`() {
        // The library's same-frame double-fire: delta 0.
        assertFalse(shouldAdmitScan("garbage", 1000L, "garbage", 1000L))
    }

    @Test
    fun `different payload admits regardless of the window`() {
        assertTrue(shouldAdmitScan("first", 1000L, "second", 1000L))
    }

    @Test
    fun `empty payload follows the same window`() {
        assertTrue(shouldAdmitScan(null, 0L, "", 500L))
        assertFalse(shouldAdmitScan("", 1000L, "", 1500L))
    }

    @Test
    fun `window override suppresses sooner`() {
        assertFalse(shouldAdmitScan("x", 1000L, "x", 1100L, windowMs = 200L))
    }

    @Test
    fun `window override admits at its own edge`() {
        assertTrue(shouldAdmitScan("x", 1000L, "x", 1200L, windowMs = 200L))
    }
}
