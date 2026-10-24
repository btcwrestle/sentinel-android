package com.samourai.sentinel.ui.settings

import com.samourai.sentinel.core.DerivedNetwork.MAINNET
import com.samourai.sentinel.core.DerivedNetwork.TESTNET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #272: the backup door's network plan, table-driven like
 * PairingNetworkDecisionTest. The device leg (2026-10-22 comment on
 * #272) is pinned as the regression: a mixed-keychain backup
 * flipping the flag through the prefs blob must be ANNOUNCED, not
 * silent.
 */
class BackupImportNetworkPlanTest {

    @Test
    fun `same-network backup into an established wallet is silent`() {
        val plan = backupImportNetworkPlan(
            currentTestnet = false,
            existingVotes = listOf(MAINNET),
            backupVotes = listOf(MAINNET, MAINNET),
            importCollections = true,
            clearExisting = false,
            importPrefs = false,
            backupPrefsTestnet = null,
            dojoWhisper = null
        )
        assertNull(plan.refusal)
        assertNull(plan.flipNote)
        assertTrue(plan.importDojo)
        assertFalse(plan.dojoSetsNetwork)
        assertFalse(plan.postImportTestnet)
    }

    @Test
    fun `cross-network backup into an established wallet is refused`() {
        val plan = backupImportNetworkPlan(
            currentTestnet = false,
            existingVotes = listOf(MAINNET),
            backupVotes = listOf(TESTNET, TESTNET),
            importCollections = true,
            clearExisting = false,
            importPrefs = false,
            backupPrefsTestnet = null,
            dojoWhisper = null
        )
        assertEquals(
            "Import stopped: this backup holds Testnet keys, but this " +
                "wallet already holds Mainnet keys. Tick 'Clear existing " +
                "collections' to replace them, or import a Mainnet backup.",
            plan.refusal
        )
    }

    @Test
    fun `cross-network backup with the clear tick proceeds and announces the flip`() {
        val plan = backupImportNetworkPlan(
            currentTestnet = false,
            existingVotes = listOf(MAINNET),
            backupVotes = listOf(TESTNET, TESTNET),
            importCollections = true,
            clearExisting = true,
            importPrefs = false,
            backupPrefsTestnet = null,
            dojoWhisper = null
        )
        assertNull(plan.refusal)
        assertTrue(plan.postImportTestnet)
        assertEquals(
            " Network switched to Testnet - set by your imported keys.",
            plan.flipNote
        )
    }

    @Test
    fun `unestablished wallet takes its first keys and the flip is announced`() {
        val plan = backupImportNetworkPlan(
            currentTestnet = true,
            existingVotes = emptyList(),
            backupVotes = listOf(MAINNET),
            importCollections = true,
            clearExisting = false,
            importPrefs = false,
            backupPrefsTestnet = null,
            dojoWhisper = null
        )
        assertNull(plan.refusal)
        assertFalse(plan.postImportTestnet)
        assertEquals(
            " Network switched to Mainnet - set by your imported keys.",
            plan.flipNote
        )
    }

    @Test
    fun `prefs-only flip is announced as the backup's settings`() {
        val plan = backupImportNetworkPlan(
            currentTestnet = true,
            existingVotes = emptyList(),
            backupVotes = emptyList(),
            importCollections = false,
            clearExisting = false,
            importPrefs = true,
            backupPrefsTestnet = false,
            dojoWhisper = null
        )
        assertFalse(plan.postImportTestnet)
        assertEquals(
            " Network switched to Mainnet - set by the backup's settings.",
            plan.flipNote
        )
    }

    /**
     * The device chain from the #272 comment: flag already testnet,
     * empty wallet, mixed backup (2 mainnet + 2 testnet), clear tick
     * on (the checkbox default), prefs blob saying mainnet. The key
     * gate abstains (mixed backup), the derivation abstains (mixed
     * keychain), the prefs blob flips - and now it is announced.
     */
    @Test
    fun `device chain - mixed-keychain backup flips the flag through the prefs blob, announced`() {
        val plan = backupImportNetworkPlan(
            currentTestnet = true,
            existingVotes = emptyList(),
            backupVotes = listOf(MAINNET, MAINNET, TESTNET, TESTNET),
            importCollections = true,
            clearExisting = true,
            importPrefs = true,
            backupPrefsTestnet = false,
            dojoWhisper = null
        )
        assertNull(plan.refusal)
        assertFalse(plan.postImportTestnet)
        assertEquals(
            " Network switched to Mainnet - set by the backup's settings.",
            plan.flipNote
        )
    }

    @Test
    fun `mixed backup into an established wallet abstains - no refusal, no re-flag`() {
        val plan = backupImportNetworkPlan(
            currentTestnet = false,
            existingVotes = listOf(MAINNET),
            backupVotes = listOf(MAINNET, TESTNET),
            importCollections = true,
            clearExisting = false,
            importPrefs = false,
            backupPrefsTestnet = null,
            dojoWhisper = null
        )
        assertNull(plan.refusal)
        assertNull(plan.flipNote)
        assertFalse(plan.postImportTestnet)
    }

    @Test
    fun `testnet-whispering dojo into a pinned mainnet wallet is skipped loudly`() {
        val plan = backupImportNetworkPlan(
            currentTestnet = false,
            existingVotes = listOf(MAINNET),
            backupVotes = listOf(MAINNET),
            importCollections = true,
            clearExisting = false,
            importPrefs = false,
            backupPrefsTestnet = null,
            dojoWhisper = TESTNET
        )
        assertFalse(plan.importDojo)
        assertFalse(plan.dojoSetsNetwork)
        assertTrue(plan.dojoObjection!!.contains("Testnet mount"))
        assertTrue(plan.dojoObjection!!.contains("Mainnet"))
    }

    @Test
    fun `testnet dojo into an unestablished mainnet wallet sets the network`() {
        val plan = backupImportNetworkPlan(
            currentTestnet = false,
            existingVotes = emptyList(),
            backupVotes = emptyList(),
            importCollections = true,
            clearExisting = false,
            importPrefs = false,
            backupPrefsTestnet = null,
            dojoWhisper = TESTNET
        )
        assertTrue(plan.importDojo)
        assertTrue(plan.dojoSetsNetwork)
        assertTrue(plan.postImportTestnet)
        assertEquals(
            " Network switched to Testnet - set by the backup's Dojo.",
            plan.dojoSetNote
        )
        assertNull(plan.dojoObjection)
    }

    @Test
    fun `testnet dojo into a mixed mainnet wallet sets the network loudly`() {
        val plan = backupImportNetworkPlan(
            currentTestnet = false,
            existingVotes = listOf(MAINNET, TESTNET),
            backupVotes = emptyList(),
            importCollections = true,
            clearExisting = false,
            importPrefs = false,
            backupPrefsTestnet = null,
            dojoWhisper = TESTNET
        )
        assertTrue(plan.importDojo)
        assertTrue(plan.dojoSetsNetwork)
        assertTrue(plan.postImportTestnet)
    }

    @Test
    fun `dojo evidence matching the post-import network proceeds`() {
        val plan = backupImportNetworkPlan(
            currentTestnet = true,
            existingVotes = listOf(TESTNET),
            backupVotes = listOf(TESTNET),
            importCollections = true,
            clearExisting = false,
            importPrefs = false,
            backupPrefsTestnet = null,
            dojoWhisper = TESTNET
        )
        assertTrue(plan.importDojo)
        assertFalse(plan.dojoSetsNetwork)
        assertNull(plan.dojoObjection)
    }

    @Test
    fun `no dojo evidence means no objection`() {
        val plan = backupImportNetworkPlan(
            currentTestnet = false,
            existingVotes = listOf(MAINNET),
            backupVotes = listOf(MAINNET),
            importCollections = true,
            clearExisting = false,
            importPrefs = false,
            backupPrefsTestnet = null,
            dojoWhisper = null
        )
        assertTrue(plan.importDojo)
        assertNull(plan.dojoObjection)
        assertNull(plan.dojoSetNote)
    }
}
