package com.samourai.sentinel.data.repository

import com.samourai.sentinel.data.db.entity.LabelEntry
import com.samourai.sentinel.data.db.entity.UtxoLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #42: when the keychain derivation corrects the network flag, the
 * label rows written under the old network must move to the real
 * one — without this, the fix orphans every existing label row.
 *
 * The planner is pure: rows in, plan out. The repository executor
 * (getAllNetworks -> plan -> withTransaction upserts + purge) is
 * carried by this coverage plus device QA, same division of proof
 * as the Whirlpool engine chain (Sink seam).
 *
 * Idempotency is the acceptance oracle for the guarded delete: a
 * second run finds no foreign rows, so the purge set is empty and
 * the delete cannot fire on good rows.
 */
class LabelRescopePlannerTest {

    private val txid = "a".repeat(64)
    private val otherTxid = "b".repeat(64)

    private fun mainnetUtxo() = UtxoLabel(
        network = "mainnet", txid = txid, vout = 22,
        label = "Bad Bank", origin = "auto:whirlpool:v1",
        createdAt = 111L, updatedAt = 222L
    )

    private fun mainnetEntry() = LabelEntry(
        network = "mainnet", type = "tx", ref = otherTxid,
        label = "Whirlpool TX0", origin = "auto:whirlpool:v1",
        createdAt = 333L, updatedAt = 444L
    )

    @Test
    fun `foreign utxo row is copied to target with all fields preserved`() {
        val plan = LabelRescopePlanner.plan(
            target = "testnet",
            utxoLabels = listOf(mainnetUtxo()),
            entries = emptyList()
        )
        assertEquals(1, plan.utxoCopies.size)
        val copy = plan.utxoCopies[0]
        assertEquals("testnet", copy.network)
        assertEquals(txid, copy.txid)
        assertEquals(22, copy.vout)
        assertEquals("Bad Bank", copy.label)
        assertEquals("auto:whirlpool:v1", copy.origin)
        assertEquals(111L, copy.createdAt)
        assertEquals(222L, copy.updatedAt)
        assertEquals(setOf("mainnet"), plan.purgeNetworks)
    }

    @Test
    fun `foreign entry row is copied to target with all fields preserved`() {
        val plan = LabelRescopePlanner.plan(
            target = "testnet",
            utxoLabels = emptyList(),
            entries = listOf(mainnetEntry())
        )
        assertEquals(1, plan.entryCopies.size)
        val copy = plan.entryCopies[0]
        assertEquals("testnet", copy.network)
        assertEquals("tx", copy.type)
        assertEquals(otherTxid, copy.ref)
        assertEquals("Whirlpool TX0", copy.label)
        assertEquals(333L, copy.createdAt)
        assertEquals(setOf("mainnet"), plan.purgeNetworks)
    }

    /**
     * Idempotency: rows already on the target produce an empty plan.
     * This is the case that makes the guarded delete safe — the
     * executor must be a no-op when reconciliation runs twice.
     */
    @Test
    fun `rows already on target produce an empty plan`() {
        val plan = LabelRescopePlanner.plan(
            target = "testnet",
            utxoLabels = listOf(mainnetUtxo().copy(network = "testnet")),
            entries = listOf(mainnetEntry().copy(network = "testnet"))
        )
        assertTrue(plan.utxoCopies.isEmpty())
        assertTrue(plan.entryCopies.isEmpty())
        assertTrue(plan.purgeNetworks.isEmpty())
    }

    @Test
    fun `tables move independently - clean utxo does not drag a foreign entry`() {
        val plan = LabelRescopePlanner.plan(
            target = "testnet",
            utxoLabels = listOf(mainnetUtxo().copy(network = "testnet")),
            entries = listOf(mainnetEntry())
        )
        assertTrue(plan.utxoCopies.isEmpty())
        assertEquals(1, plan.entryCopies.size)
        assertEquals(setOf("mainnet"), plan.purgeNetworks)
    }

    @Test
    fun `empty input yields an empty plan`() {
        val plan = LabelRescopePlanner.plan(
            target = "mainnet",
            utxoLabels = emptyList(),
            entries = emptyList()
        )
        assertTrue(plan.utxoCopies.isEmpty())
        assertTrue(plan.entryCopies.isEmpty())
        assertTrue(plan.purgeNetworks.isEmpty())
    }

    @Test
    fun `mixed foreign networks purge each distinct source`() {
        val plan = LabelRescopePlanner.plan(
            target = "testnet",
            utxoLabels = listOf(
                mainnetUtxo(),
                mainnetUtxo().copy(network = "regtest", txid = otherTxid)
            ),
            entries = emptyList()
        )
        assertEquals(2, plan.utxoCopies.size)
        assertEquals(setOf("mainnet", "regtest"), plan.purgeNetworks)
        assertTrue(plan.utxoCopies.all { it.network == "testnet" })
    }
}
