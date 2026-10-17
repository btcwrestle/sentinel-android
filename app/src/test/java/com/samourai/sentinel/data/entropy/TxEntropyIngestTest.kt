package com.samourai.sentinel.data.entropy

import com.samourai.sentinel.data.Inputs
import com.samourai.sentinel.data.Out
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.db.entity.TxEntropy
import com.samourai.sentinel.data.prevOut
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log2

/**
 * Red-first fixture for the #72 fix arc: pins the FIXED ingest
 * contract against the flaw-preserved extraction
 * (TxEntropyIngest, step 1) and fails on purpose there - the
 * red run is the flaw proof for the PR body.
 *
 * Contract cases (red against the flaw):
 *  - a (1,1) wallet slice of a 5x5 mix must not cache the
 *    slice answer (1|0.0) as the tx's answer: the full tx is
 *    fetched and 1496 - the engine's canonical uniform-5x5
 *    count, BoltzmannTxServiceTest's own oracle - is cached;
 *  - a failed fetch writes no row (the InsufficientData
 *    convention extended to fetch failure: say nothing,
 *    re-attempted per sync);
 *  - slices of one txid group to exactly one fetch.
 *
 * Pin cases (green in both worlds, by design):
 *  - a TX0-shape tx stays 1|0.0 - correct by shape (row only;
 *    fetch discipline is a contract-case concern);
 *  - a pre-cached txid is never re-fetched or overwritten
 *    (the guard, preserved verbatim from the repository).
 *
 * #102 pins (the regression's defects, pinned green):
 *  - an engine crash (the vendored bailout NPE class, the
 *    45-txid device exhibit) is contained as a tooComplex
 *    row - never silence, never a crash;
 *  - an engine-crashed txid is never re-fetched: the row
 *    exists, the guard skips - the exact device defect
 *    (pre-fix: no row, re-fetched every sync forever);
 *  - attempted = the sum of the five counters - every
 *    guard-passed txid in exactly one.
 */
class TxEntropyIngestTest {

    private val cid = "c-72"

    private class FakeCache(
        // #102: the failed-counter leg - insert throws for the
        // named txids; default empty keeps every existing
        // construction unchanged.
        private val failInsertFor: Set<String> = emptySet(),
    ) : TxEntropyIngest.TxEntropyCache {
        val rows = mutableMapOf<String, TxEntropy>()
        override suspend fun findByTxid(txid: String): TxEntropy? = rows[txid]
        override suspend fun insert(entry: TxEntropy) {
            if (entry.txid in failInsertFor) {
                throw IllegalStateException("insert failed for ${entry.txid}")
            }
            rows[entry.txid] = entry
        }
    }

    /**
     * #102 test seam (rides E2's open class/analyze): throws
     * the vendored engine's own failure class - the bailout
     * NPE from TxosAggregator.findDtrmLinks, the 45-txid
     * device exhibit - for the named hashes; delegates to the
     * real service otherwise. The ingest must contain it as a
     * tooComplex row.
     */
    private class ExplodingService(
        private val explodeFor: Set<String> = emptySet(),
    ) : BoltzmannTxService() {
        override suspend fun analyze(tx: Tx): BoltzmannTxAnalysis =
            if (tx.hash in explodeFor) {
                throw NullPointerException(
                    "ObjectBigList.size64() on a null reference",
                )
            } else {
                super.analyze(tx)
            }
    }

    private fun input(vin: Int, addr: String, value: Long) = Inputs(
        vin = vin,
        sequence = null,
        prev_out = prevOut(
            addr = addr, txid = "ff".repeat(32), value = value, vout = 0, xpub = null,
        ),
    )

    private fun output(n: Int, addr: String, value: Long) =
        Out(n = n, value = value, addr = addr, xpub = null)

    /** Uniform 5x5 mix (the oracle shape): 1496, log2(1496) bits. */
    private fun fullFiveByFive(txid: String): Tx = Tx(
        hash = "$txid-$cid",
        time = 0L, version = 1, locktime = 0, result = null, block_height = null,
        inputs = (0 until 5).map { input(it, "in$it", 1_000_100L) },
        out = (0 until 5).map { output(it, "out$it", 1_000_000L) },
    )

    /** TX0 shape (1 deposit in, 2 premix outs): ZeroEntropy - a row. */
    private fun tx0Shape(txid: String): Tx = Tx(
        hash = "$txid-$cid", time = 0L, version = 1, locktime = 0,
        result = null, block_height = null,
        inputs = listOf(input(0, "deposit", 100_000_000L)),
        out = listOf(
            output(0, "premix0", 2_500_605L),
            output(1, "premix1", 2_500_605L),
        ),
    )

    @Test
    fun sliceAnswerIsNotCachedAsTheTxsAnswer() {
        val txid = "ee".repeat(32)
        val full = fullFiveByFive(txid)
        val slice = Tx(
            hash = full.hash, time = 0L, version = 1, locktime = 0,
            result = null, block_height = null,
            inputs = full.inputs.take(1),
            out = full.out.take(1),
        )
        var fetches = 0
        val fetcher: suspend (String) -> Tx? = { fetches++; full }
        val cache = FakeCache()
        runBlocking { TxEntropyIngest(cache, fetcher).ingest(listOf(slice), cid) }
        val row = cache.rows[txid]
        assertTrue("no row cached for the tx", row != null)
        assertEquals(1496, row?.nbCmbn)
        assertEquals(log2(1496.0), row?.entropyBits ?: -1.0, 1e-6)
        assertEquals(1, fetches)
    }

    @Test
    fun fetchFailureWritesNoRow() {
        val txid = "aa".repeat(32)
        val slice = Tx(
            hash = "$txid-$cid", time = 0L, version = 1, locktime = 0,
            result = null, block_height = null,
            inputs = listOf(input(0, "in0", 1_000_100L)),
            out = listOf(output(0, "out0", 1_000_000L)),
        )
        val fetcher: suspend (String) -> Tx? = { null }
        val cache = FakeCache()
        runBlocking { TxEntropyIngest(cache, fetcher).ingest(listOf(slice), cid) }
        assertNull("fetch failure must cache nothing", cache.rows[txid])
    }

    @Test
    fun slicesOfOneTxidFetchExactlyOnce() {
        val txid = "bb".repeat(32)
        val full = fullFiveByFive(txid)
        val insOnly = Tx(
            hash = full.hash, time = 0L, version = 1, locktime = 0,
            result = null, block_height = null,
            inputs = full.inputs.take(1), out = emptyList(),
        )
        val outsOnly = Tx(
            hash = full.hash, time = 0L, version = 1, locktime = 0,
            result = null, block_height = null,
            inputs = emptyList(), out = full.out.take(2),
        )
        val both = Tx(
            hash = full.hash, time = 0L, version = 1, locktime = 0,
            result = null, block_height = null,
            inputs = full.inputs.take(1), out = full.out.take(1),
        )
        var fetches = 0
        val fetcher: suspend (String) -> Tx? = { fetches++; full }
        val cache = FakeCache()
        runBlocking {
            TxEntropyIngest(cache, fetcher).ingest(listOf(insOnly, outsOnly, both), cid)
        }
        assertEquals(1, fetches)
        assertEquals(1496, cache.rows[txid]?.nbCmbn)
    }

    @Test
    fun tx0ShapeStaysZeroEntropyCorrectByShape() {
        val txid = "cc".repeat(32)
        val tx0 = Tx(
            hash = "$txid-$cid", time = 0L, version = 1, locktime = 0,
            result = null, block_height = null,
            inputs = listOf(input(0, "deposit", 100_000_000L)),
            out = listOf(
                output(0, "premix0", 2_500_605L),
                output(1, "premix1", 2_500_605L),
            ),
        )
        val fetcher: suspend (String) -> Tx? = { tx0 }
        val cache = FakeCache()
        runBlocking { TxEntropyIngest(cache, fetcher).ingest(listOf(tx0), cid) }
        val row = cache.rows[txid]
        assertTrue(row != null)
        assertEquals(1, row?.nbCmbn)
        assertEquals(0.0, row?.entropyBits ?: -1.0, 1e-9)
    }

    @Test
    fun preCachedTxidSkipsFetchAndWrite() {
        val txid = "dd".repeat(32)
        val pre = TxEntropy(
            txid = txid, nbCmbn = 1496, entropyBits = log2(1496.0),
            linkabilityJson = "[]", tooComplex = false, computedAt = 42L,
        )
        val slice = Tx(
            hash = "$txid-$cid", time = 0L, version = 1, locktime = 0,
            result = null, block_height = null,
            inputs = listOf(input(0, "in0", 1_000_100L)),
            out = listOf(output(0, "out0", 1_000_000L)),
        )
        var fetches = 0
        val fetcher: suspend (String) -> Tx? = { fetches++; fullFiveByFive(txid) }
        val cache = FakeCache()
        cache.rows[txid] = pre
        runBlocking { TxEntropyIngest(cache, fetcher).ingest(listOf(slice), cid) }
        assertEquals(0, fetches)
        assertEquals(pre, cache.rows[txid])
    }

    @Test
    fun engineFailureIsContainedAsATooComplexRow() {
        val txid = "11".repeat(32)
        val full = fullFiveByFive(txid)
        var fetches = 0
        val fetcher: suspend (String) -> Tx? = { fetches++; full }
        val cache = FakeCache()
        val ingest = TxEntropyIngest(
            cache, fetcher, ExplodingService(setOf(full.hash)),
        )
        val result = runBlocking { ingest.ingestTxids(listOf(txid)) }
        val row = cache.rows[txid]
        assertTrue("engine failure must row the txid", row != null)
        assertEquals(0, row?.nbCmbn)
        assertEquals(0.0, row?.entropyBits ?: -1.0, 1e-9)
        assertEquals("[]", row?.linkabilityJson)
        assertTrue("row must be tooComplex", row?.tooComplex == true)
        assertEquals(1, fetches)
        assertEquals(1, result.engineFailed)
        assertEquals(0, result.analyzed)
        assertEquals(1, result.attempted)
    }

    @Test
    fun engineFailedTxidIsNeverRefetched() {
        val txid = "22".repeat(32)
        val full = fullFiveByFive(txid)
        var fetches = 0
        val fetcher: suspend (String) -> Tx? = { fetches++; full }
        val cache = FakeCache()
        val ingest = TxEntropyIngest(
            cache, fetcher, ExplodingService(setOf(full.hash)),
        )
        runBlocking { ingest.ingestTxids(listOf(txid)) }
        val second = runBlocking { ingest.ingestTxids(listOf(txid)) }
        assertEquals("the row must end the re-fetch loop", 1, fetches)
        assertEquals(0, second.attempted)
        assertEquals(0, second.analyzed)
        assertEquals(0, second.engineFailed)
    }

    @Test
    fun attemptedEqualsTheSumOfAllFiveCounters() {
        val analyzedTxid = "33".repeat(32)
        val unresolvableTxid = "44".repeat(32)
        val fetchFailedTxid = "55".repeat(32)
        val engineFailedTxid = "66".repeat(32)
        val failedTxid = "77".repeat(32)
        val unresolvable = Tx(
            hash = "$unresolvableTxid-$cid", time = 0L, version = 1,
            locktime = 0, result = null, block_height = null,
            // prev_out null: the service's own InsufficientData
            // check (values unknown) - unresolvable, no row.
            inputs = listOf(Inputs(vin = 0, sequence = null, prev_out = null)),
            out = listOf(output(0, "out0", 1_000_000L)),
        )
        val fetcher: suspend (String) -> Tx? = { txid ->
            when (txid) {
                analyzedTxid -> tx0Shape(txid)
                unresolvableTxid -> unresolvable
                fetchFailedTxid -> null
                engineFailedTxid -> fullFiveByFive(txid)
                failedTxid -> tx0Shape(txid)
                else -> null
            }
        }
        val cache = FakeCache(failInsertFor = setOf(failedTxid))
        val ingest = TxEntropyIngest(
            cache, fetcher,
            ExplodingService(setOf("${engineFailedTxid}-$cid")),
        )
        val result = runBlocking {
            ingest.ingestTxids(
                listOf(
                    analyzedTxid, unresolvableTxid, fetchFailedTxid,
                    engineFailedTxid, failedTxid,
                )
            )
        }
        assertEquals(1, result.analyzed)
        assertEquals(1, result.unresolvable)
        assertEquals(1, result.fetchFailed)
        assertEquals(1, result.engineFailed)
        assertEquals(1, result.failed)
        assertEquals(5, result.attempted)
        assertEquals(
            5,
            result.analyzed + result.unresolvable + result.fetchFailed +
                result.engineFailed + result.failed,
        )
        assertTrue(cache.rows[analyzedTxid] != null)
        assertTrue(cache.rows[engineFailedTxid]?.tooComplex == true)
        assertNull(cache.rows[unresolvableTxid])
        assertNull(cache.rows[fetchFailedTxid])
        assertNull(cache.rows[failedTxid])
    }
}
