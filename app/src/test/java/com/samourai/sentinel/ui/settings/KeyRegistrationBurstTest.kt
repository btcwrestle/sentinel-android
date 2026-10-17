package com.samourai.sentinel.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #158: the vocabulary seam + the single-flight holder. Pure JVM - no
 * Android surface, no Robolectric.
 */
class KeyRegistrationBurstTest {

    @Before
    fun resetBurstState() {
        KeyRegistrationBurst.markFinished()
    }

    @Test
    fun progress_counts_attempts_under_the_renamed_vocabulary() {
        assertEquals(
            "Registering derivation paths with Dojo\u2026 14 of 19",
            KeyRegistrationBurst.progressText(14, 19)
        )
    }

    @Test
    fun full_pass_states_the_plain_tally() {
        assertEquals(
            "Dojo connected (19 derivation paths registered)",
            KeyRegistrationBurst.resultText(19, 0, 19)
        )
    }

    @Test
    fun partial_pass_states_the_failed_delta_out_loud() {
        // The witnessed case: the row read "19 of 19" (attempts) while an
        // item failed - the completion must name the delta, not just say
        // "partial".
        assertEquals(
            "Dojo connected (18 of 19 derivation paths registered, 1 failed - retry pairing to re-register)",
            KeyRegistrationBurst.resultText(18, 1, 19)
        )
    }

    @Test
    fun erase_skip_shape_gets_the_retry_note_without_a_zero_failed_wart() {
        val text = KeyRegistrationBurst.resultText(17, 0, 19)
        assertTrue(text.contains("17 of 19 derivation paths registered"))
        assertFalse(text.contains("0 failed"))
        assertTrue(text.contains("retry pairing to re-register"))
    }

    @Test
    fun single_flight_blocks_a_second_burst_while_one_is_live() {
        assertTrue(KeyRegistrationBurst.markStarted())
        assertFalse(KeyRegistrationBurst.markStarted())
    }

    @Test
    fun completion_re_arms_the_gate_so_every_retry_round_reports() {
        // The every-time condition: each round fires its own report, so
        // the gate must re-arm at completion - never stick.
        assertTrue(KeyRegistrationBurst.markStarted())
        KeyRegistrationBurst.complete(18, 1, 19)
        assertFalse(KeyRegistrationBurst.inFlight)
        assertTrue(KeyRegistrationBurst.markStarted())
    }

    @Test
    fun in_flight_row_renders_the_re_entry_marker_from_state() {
        assertTrue(KeyRegistrationBurst.markStarted())
        KeyRegistrationBurst.begin(19)
        KeyRegistrationBurst.onItemStarted(14)
        assertEquals(
            "Registering derivation paths with Dojo\u2026 14 of 19",
            KeyRegistrationBurst.statusText("Enabled")
        )
    }

    @Test
    fun partial_round_leaves_residue_on_the_rest_state_until_displaced() {
        KeyRegistrationBurst.markStarted()
        KeyRegistrationBurst.complete(18, 1, 19)
        assertEquals(
            "Enabled (18 of 19 registered)",
            KeyRegistrationBurst.residueText(18, 19)
        )
        assertEquals(
            "Enabled (18 of 19 registered)",
            KeyRegistrationBurst.statusText("Enabled")
        )
        KeyRegistrationBurst.markStarted()
        KeyRegistrationBurst.complete(19, 0, 19)
        assertEquals("Enabled", KeyRegistrationBurst.statusText("Enabled"))
    }

    @Test
    fun empty_wallet_launch_resets_clean_with_no_residue() {
        assertTrue(KeyRegistrationBurst.markStarted())
        KeyRegistrationBurst.markFinished()
        assertEquals("Enabled", KeyRegistrationBurst.statusText("Enabled"))
        assertTrue(KeyRegistrationBurst.markStarted())
    }
}
