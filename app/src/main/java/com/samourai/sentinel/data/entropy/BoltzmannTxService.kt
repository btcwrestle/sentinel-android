package com.samourai.sentinel.data.entropy

import com.samourai.boltzmann.Boltzmann
import com.samourai.boltzmann.beans.BoltzmannSettings
import com.samourai.boltzmann.beans.Txos
import com.samourai.boltzmann.linker.TxosLinkerOptionEnum
import com.samourai.sentinel.data.Tx
import it.unimi.dsi.fastutil.doubles.DoubleBigList
import it.unimi.dsi.fastutil.objects.ObjectBigList
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.log2

/**
 * Per-tx Boltzmann entropy + linkability over the vendored
 * boltzmann-java engine (issue #7). Pure JVM (pattern:
 * WhirlpoolDetector / Bip329Parser).
 *
 * The engine call is local-only: analyze() feeds engine-built
 * [Txos] maps; the engine's txid-string entry point (network
 * fetch) is never touched from app code.
 *
 * Honesty contract (issue #7):
 *  - Numbers displayed are exactly what the vendored engine
 *    computes — never derived, never shaped into marketing.
 *    Engine truth for the reference shape (uniform 5x5 mix):
 *    1496 interpretations, log2(1496) ~= 10.55 bits — the
 *    same figure boltzmann-c documents for a real Whirlpool
 *    0.05 pool tx. The engine counts interpretations where
 *    several inputs belong to one entity: real possibilities,
 *    demanded by the issue's own clustering argument. The
 *    issue's illustrative "120 = 5!" (bijections only) is not
 *    a number this engine can produce for any 5x5; see the PR
 *    body. "Anon set 5^n" is never displayed — nbCmbn is.
 *  - nbCmbn == 0 is the engine's not-processed sentinel (txos
 *    cap tripped, or the duration bailout - guarded to this
 *    same state): reported as TooComplex. Never hang, never lie.
 *  - Inputs spending the same address are ONE entity: merged
 *    upfront (values summed) — same semantics as the engine's
 *    MERGE_INPUTS. Not merging would overstate entropy in the
 *    flattering direction.
 *  - Outputs to the same address are NOT merged (the engine's
 *    MERGE_OUTPUTS is explicitly not recommended): unique keys
 *    per output instead.
 *  - Time-box: the engine's own maxDuration is in SECONDS
 *    (default 600) and is pinned to 1 here as defense in depth.
 *    The caller-facing box is [timeBoxMs] via withTimeoutOrNull.
 *    Blocking CPU work cannot be interrupted — with the txos
 *    cap the worst case is a bounded thread that drains while
 *    the caller already holds TooComplex.
 */
sealed interface BoltzmannTxAnalysis {
    /** nbCmbn >= 2 interpretations: the honest numbers. */
    data class Honest(
        val nbCmbn: Int,
        val entropyBits: Double,
        /**
         * Linkability matrix indexed [output][input];
         * cell = P(input -> output). Uniform 5x5 mix: every
         * cell 0.2. Deterministic links = cells at 1.0.
         */
        val linkability: List<List<Double>>,
    ) : BoltzmannTxAnalysis

    /** Engine declined (txos cap, nbCmbn == 0) or time-box exceeded. */
    object TooComplex : BoltzmannTxAnalysis

    /** nbCmbn == 1: single interpretation, zero entropy. */
    object ZeroEntropy : BoltzmannTxAnalysis

    /**
     * Inputs present but prev_out absent (values unknown to us —
     * observed on-device from Dojo): any number computed on partial
     * data would be a guess. Say nothing: nothing is cached, nothing
     * is displayed (detector convention). Re-attempted per sync.
     */
    object InsufficientData : BoltzmannTxAnalysis
}

/**
 * Test seam only (#102): open class + open analyze() exist so
 * TxEntropyIngestTest can subclass (ExplodingService) and prove
 * engine-failure containment. No other subclass exists — this
 * is not a public extension point.
 */
open class BoltzmannTxService(
    /** Caller-facing time-box in milliseconds (issue #7: ~500ms). */
    private val timeBoxMs: Long = DEFAULT_TIME_BOX_MS,
    /**
     * The engine's internal duration box, in SECONDS (#166).
     * Default 1s, unchanged in production. The engine checks
     * duration per surge cycle, not continuously, so a cycle
     * boundary landing just before the deadline lets the tail
     * run long - on a JVM where the 7x7 oracle needs ~1.3-1.4s,
     * red/green was decided by cycle alignment (isolated
     * 1-in-3 red on a quiet box, the red run FASTER than both
     * greens). Tests pinning real oracle numbers construct
     * with a raised box so the engine's deadline never fires
     * mid-oracle; deadline behavior itself stays pinned by
     * BoltzmannEngineBailoutTest (maxDuration=0, deterministic)
     * and the surge-cap refusal tests (TooComplex before the
     * engine). The bailout comment in the vendored TxosAggregator
     * documents the per-cycle check; the settings object exposes
     * this parameter natively.
     */
    private val engineDurationSeconds: Int = 1,
) {

    private val engine = Boltzmann(
        BoltzmannSettings().apply {
            maxDuration = engineDurationSeconds
            maxTxos = MAX_TXOS
            maxCjIntrafeesRatio = 0f
            // PRECHECK deliberately OFF: the vendored checkDtrmLinks()
            // increments shared counters from unsynchronized parallel
            // streams (read-modify-write on matCmbn/inCmbn) — a data
            // race whose outcome perturbs deterministic-link detection
            // and reroutes compute into the TODO-test packing path
            // (observed: intermittent ZeroEntropy on the canonical 2x2).
            // It is an optimization only: with LINKABILITY on, dtrm
            // links are recomputed from the final matrix, so oracle
            // numbers are unchanged on a deterministic path.
            options = arrayOf(
                TxosLinkerOptionEnum.LINKABILITY,
                TxosLinkerOptionEnum.MERGE_INPUTS,
            )
        }
    )

    open suspend fun analyze(tx: Tx): BoltzmannTxAnalysis {
        // Coinbase shape (no inputs): provably a single interpretation —
        // and the engine divides by zero downstream on 0-input txs
        // (getClosestPerfectCoinjoin: nbOuts % nbIns), so this shape
        // never reaches the engine call.
        if (tx.inputs.isEmpty()) return BoltzmannTxAnalysis.ZeroEntropy
        // Inputs with absent prev_out (value unknown): a number computed
        // on partial data is a guess. Never a guess — say nothing.
        if (tx.inputs.any { it.prev_out == null }) {
            return BoltzmannTxAnalysis.InsufficientData
        }
        val txos = toTxos(tx)
        // Degenerate after known-zero filtering (all input values <= 0,
        // or no positive outputs): single interpretation; same engine
        // div-by-zero guarded.
        if (txos.inputs.isEmpty() || txos.outputs.isEmpty()) {
            return BoltzmannTxAnalysis.ZeroEntropy
        }
        // Surge cap (#102): refuse shapes wider than the largest round
        // this engine budgets for, BEFORE the engine is called.
        // Post-filter counts — raw != computed (the 2x2 oracle: 3 raw
        // outputs, 2 computed; a 5x5 remix carries a sixth zero-value
        // output, dropped here). Boundary facts (#105 census + the
        // repo's own CI artifact): 7x7 = 426,833 interpretations /
        // ~18.70 bits — the 7x7 testcase runs 936ms on the desktop
        // JVM, OVER the 500ms caller box (the earlier "completes
        // inside" claim here was falsified by that artifact); 8x8 =
        // 9,934,563 interpretations / ~23.24 bits (the same count
        // boltzmann-rs's bench states) trips the engine's 1s
        // maxDuration — the bailout now returns the txos-cap sentinel
        // state (guarded; formerly null, the #102 NPE class). The cap
        // value 7 as "largest round ever produced" is census-falsified
        // (28 8x8s refused in the #105 wallet alone) and is retained
        // pending #107's re-enable design (tier 3 routes 6x6-9x9 to a
        // faster engine).
        if (txos.inputs.size > SURGE_CAP || txos.outputs.size > SURGE_CAP) {
            return BoltzmannTxAnalysis.TooComplex
        }
        val result = withTimeoutOrNull(timeBoxMs) { engine.process(txos) }
            ?: return BoltzmannTxAnalysis.TooComplex
        return when (result.nbCmbn) {
            0 -> BoltzmannTxAnalysis.TooComplex
            1 -> BoltzmannTxAnalysis.ZeroEntropy
            else -> BoltzmannTxAnalysis.Honest(
                nbCmbn = result.nbCmbn,
                // entropy is log2(nbCmbn) by definition; the fallback
                // computes the same number if the engine ever nulls it.
                entropyBits = result.entropy ?: log2(result.nbCmbn.toDouble()),
                linkability = result.matLnkProbabilities.toNestedLists(),
            )
        }
    }

    /**
     * Tx (Dojo wallet-response shape) -> engine Txos.
     *
     * Insertion order is load-bearing: the engine indexes
     * deterministic links positionally against these keys.
     * Zero-value IOs (OP_RETURN & friends) are dropped — the
     * engine filters them anyway; dropping early keeps the
     * positional story short.
     */
    fun toTxos(tx: Tx): Txos {
        val inputs = LinkedHashMap<String, Long>()
        tx.inputs.forEach { input ->
            val prev = input.prev_out ?: return@forEach
            if (prev.value <= 0L) return@forEach
            val key = prev.addr ?: "${prev.txid}:${prev.vout}"
            // Same address = one entity: values sum (honest merge).
            inputs.merge(key, prev.value, Long::plus)
        }
        val outputs = LinkedHashMap<String, Long>()
        tx.out.forEach { out ->
            if (out.value <= 0L) return@forEach
            outputs[uniqueKey(outputs, out.addr ?: "o${out.n}")] = out.value
        }
        return Txos(inputs, outputs)
    }

    private fun uniqueKey(map: Map<String, Long>, key: String): String {
        if (key !in map) return key
        var i = 2
        while ("$key#$i" in map) i++
        return "$key#$i"
    }

    private fun ObjectBigList<DoubleBigList>.toNestedLists(): List<List<Double>> {
        val rows = ArrayList<List<Double>>(size64().toInt())
        for (o in 0L until size64()) {
            val row = get(o)
            val cells = ArrayList<Double>(row.size64().toInt())
            for (i in 0L until row.size64()) cells.add(row.getDouble(i))
            rows.add(cells)
        }
        return rows
    }

    companion object {
        const val DEFAULT_TIME_BOX_MS = 500L

        /** Complexity cap: max inputs OR outputs (engine default, kept). */
        const val MAX_TXOS = 12

        /**
         * Surge cap (#102): max inputs OR outputs, post-filter.
         * Retained at 7 pending #107's re-enable design. Two earlier
         * claims here were falsified: "7 = the largest round Whirlpool
         * produced" (the #105 census refused 28 8x8s in one wallet)
         * and "426,833 completes inside the 500ms caller box" (the CI
         * artifact's own 7x7 testcase runs 936ms on the desktop JVM).
         * Beyond the cap is the refused class: 8x8 = 9,934,563
         * interpretations (the count boltzmann-rs's bench states)
         * trips the engine's internal 1s maxDuration; the bailout is
         * guarded to the txos-cap sentinel state (formerly the #102
         * NPE class; reproducers:
         * BoltzmannTxServiceTest.beyondTheSurgeCapIsRefusedBeforeTheEngine,
         * BoltzmannEngineBailoutTest).
         */
        const val SURGE_CAP = 7
    }
}
