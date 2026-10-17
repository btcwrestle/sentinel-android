package com.samourai.sentinel.ui.dojo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the per-key cooldown that keeps "Rescan Xpub" from letting a user
 * queue repeated full scans on a Dojo they do not own.
 *
 * Only the pure half of [RescanCooldown] is exercised here; loading and saving
 * need a Context and are covered by using the object through the UI.
 */
class RescanCooldownTest {

    private val xpub = "xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz"
    private val other = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
    private val now = 1_700_000_000_000L

    @Test
    fun `an unseen key may be rescanned`() {
        assertTrue(RescanCooldown.isAllowed(JSONObject(), xpub, now))
        assertEquals(0L, RescanCooldown.remainingMillis(JSONObject(), xpub, now))
    }

    @Test
    fun `a key just rescanned is blocked for the full window`() {
        val store = RescanCooldown.record(JSONObject(), xpub, now)
        assertFalse(RescanCooldown.isAllowed(store, xpub, now))
        assertEquals(RescanCooldown.COOLDOWN_MILLIS, RescanCooldown.remainingMillis(store, xpub, now))
    }

    @Test
    fun `remaining time counts down`() {
        val store = RescanCooldown.record(JSONObject(), xpub, now)
        val fiveMinutes = 5 * 60 * 1000L
        assertEquals(
            RescanCooldown.COOLDOWN_MILLIS - fiveMinutes,
            RescanCooldown.remainingMillis(store, xpub, now + fiveMinutes)
        )
    }

    @Test
    fun `the key is free once the window has passed`() {
        val store = RescanCooldown.record(JSONObject(), xpub, now)
        assertTrue(RescanCooldown.isAllowed(store, xpub, now + RescanCooldown.COOLDOWN_MILLIS))
    }

    /** A cooldown is per key: one wrong balance must not block the others. */
    @Test
    fun `a cooldown on one key does not block another`() {
        val store = RescanCooldown.record(JSONObject(), xpub, now)
        assertFalse(RescanCooldown.isAllowed(store, xpub, now))
        assertTrue(RescanCooldown.isAllowed(store, other, now))
    }

    /**
     * A timestamp in the future - a clock that moved backwards, or a store
     * restored from a backup taken on another device - must not lock a key out
     * until the clock catches up.
     */
    @Test
    fun `a future timestamp is treated as expired`() {
        val store = RescanCooldown.record(JSONObject(), xpub, now + 60 * 60 * 1000L)
        assertTrue(RescanCooldown.isAllowed(store, xpub, now))
    }

    @Test
    fun `recording prunes entries that have expired`() {
        var store = RescanCooldown.record(JSONObject(), xpub, now)
        store = RescanCooldown.record(store, other, now + RescanCooldown.COOLDOWN_MILLIS + 1)
        assertEquals(1, store.length())
        assertTrue(store.has(RescanCooldown.fingerprint(other)))
        assertFalse(store.has(RescanCooldown.fingerprint(xpub)))
    }

    /** Prefs must not end up holding key material a second time. */
    @Test
    fun `the stored key is a fingerprint and not the public key`() {
        val store = RescanCooldown.record(JSONObject(), xpub, now)
        val serialised = store.toString()
        assertFalse(serialised.contains(xpub))
        assertEquals(16, RescanCooldown.fingerprint(xpub).length)
        assertNotEquals(RescanCooldown.fingerprint(xpub), RescanCooldown.fingerprint(other))
    }

    @Test
    fun `fingerprints are stable across calls`() {
        assertEquals(RescanCooldown.fingerprint(xpub), RescanCooldown.fingerprint(xpub))
    }
}
