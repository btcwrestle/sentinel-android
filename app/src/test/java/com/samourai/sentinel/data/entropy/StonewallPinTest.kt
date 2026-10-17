package com.samourai.sentinel.data.entropy

import com.samourai.sentinel.data.Inputs
import com.samourai.sentinel.data.Out
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.prevOut
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log2

/**
 * #115's bar-ladder pin, measured fixture-first through
 * BoltzmannTxService.analyze on the desktop JVM (probe run
 * 2026-10-07: solo=5, x2=3; these assertions landed from those
 * measurements - engine truth, never relaxed to match).
 *
 * Samourai's docs describe Stonewall as 2 interpretations; this
 * engine also counts merged-entity stories (one entity holding
 * both inputs) - the same convention that makes its 5x5 oracle
 * 1496 rather than the bijection-only 120. We assert engine truth
 * per the honesty contract.
 *
 * Ladder consequence for the display work (PR-B):
 *  - grey: no verdict (declined / unparseable)
 *  - red: nbCmbn <= 2 (0-1 bit)
 *  - amber: 3-4 (honest, below Stonewall grade)
 *  - green: >= 5 (the Stonewall pin, ~2.32 bits)
 * Collision rule verdict: NOT fired - the solo pin (5) sits above
 * the red band; the x2 fallback was never needed.
 *
 * x2 corroboration: asymmetric participant values pin the change
 * outputs deterministically (cells at 1.0) - 3 interpretations
 * with TWO deterministic links. A real Stonewallx2 lands amber,
 * not green; the engine is saying the value asymmetry leaks
 * entity structure, and we display that honestly.
 *
 * Shapes are synthetic value vectors (Samourai's documented
 * Stonewall structure): no txids, no addresses, nothing
 * wallet-linked, per the standing privacy convention.
 */
class StonewallPinTest {

    private val service = BoltzmannTxService()

    private fun txOf(ins: List<Pair<String, Long>>, outs: List<Pair<String, Long>>): Tx =
        Tx(
            hash = "aa".repeat(32),
            time = 0L,
            version = 1,
            locktime = 0,
            result = null,
            inputs = ins.mapIndexed { vin, (addr, value) ->
                Inputs(
                    vin = vin,
                    sequence = null,
                    prev_out = prevOut(
                        addr = addr,
                        txid = "bb".repeat(32),
                        value = value,
                        vout = 0,
                        xpub = null,
                    ),
                )
            },
            out = outs.mapIndexed { n, (addr, value) ->
                Out(n = n, value = value, addr = addr, xpub = null)
            },
            block_height = null,
        )

    @Test
    fun stonewallSoloIsThePinAtFiveInterpretations() {
        // Solo Stonewall: two same-value inputs from different
        // addresses; outputs = denomination x2 + change x2. The 5 =
        // 1 merged-entity story + 4 pairing stories (each input takes
        // one denomination and one change, 2x2 ways). Uniform cells:
        // every input-output pair co-occurs in the merged story plus
        // 2 of the 4 pairings = 3/5. Zero deterministic links.
        val denomination = 500_000L
        val change = 400_000L
        val fee = 1_000L
        val perEntity = denomination + change + fee / 2
        val ins = listOf(
            "soloIn0" to perEntity,
            "soloIn1" to perEntity,
        )
        val outs = listOf(
            "d0" to denomination,
            "d1" to denomination,
            "c0" to change,
            "c1" to change,
        )
        val analysis = runBlocking { service.analyze(txOf(ins, outs)) }
        assertTrue("expected Honest, got $analysis", analysis is BoltzmannTxAnalysis.Honest)
        val honest = analysis as BoltzmannTxAnalysis.Honest
        assertEquals(5, honest.nbCmbn)
        assertEquals(log2(5.0), honest.entropyBits, 1e-6)
        assertEquals(4, honest.linkability.size)
        honest.linkability.forEach { row ->
            assertEquals(2, row.size)
            row.forEach { cell -> assertEquals(3.0 / 5.0, cell, 1e-9) }
        }
    }

    @Test
    fun stonewallX2CorroboratesBelowThePinWithDeterministicChanges() {
        // Stonewallx2: two participants, one input each, asymmetric
        // values; each walks away with one denomination and their own
        // change. The 3 = 1 merged story + 2 valid pairings - the fee
        // window (diff <= fees) admits only the pairings that give
        // each participant their own change, so both change outputs
        // are deterministic (cells 1.0). Matrix rows are outputs in
        // insertion order [d0, d1, c0, c1], cols inputs [in0, in1].
        val denomination = 500_000L
        val ins = listOf(
            "x2In0" to 901_000L,
            "x2In1" to 903_500L,
        )
        val outs = listOf(
            "d0" to denomination,
            "d1" to denomination,
            "c0" to 400_000L,
            "c1" to 402_500L,
        )
        val analysis = runBlocking { service.analyze(txOf(ins, outs)) }
        assertTrue("expected Honest, got $analysis", analysis is BoltzmannTxAnalysis.Honest)
        val honest = analysis as BoltzmannTxAnalysis.Honest
        assertEquals(3, honest.nbCmbn)
        assertEquals(log2(3.0), honest.entropyBits, 1e-6)
        val expected = listOf(
            listOf(2.0 / 3.0, 2.0 / 3.0),  // d0
            listOf(2.0 / 3.0, 2.0 / 3.0),  // d1
            listOf(1.0, 1.0 / 3.0),        // c0 - deterministic to in0
            listOf(1.0 / 3.0, 1.0),        // c1 - deterministic to in1
        )
        assertEquals(expected.size, honest.linkability.size)
        expected.forEachIndexed { r, row ->
            assertEquals(row.size, honest.linkability[r].size)
            row.forEachIndexed { c, cell ->
                assertEquals("cell[$r][$c]", cell, honest.linkability[r][c], 1e-9)
            }
        }
    }
}
