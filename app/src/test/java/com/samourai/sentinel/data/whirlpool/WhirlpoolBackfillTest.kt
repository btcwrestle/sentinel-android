package com.samourai.sentinel.data.whirlpool

import com.samourai.sentinel.data.Inputs
import com.samourai.sentinel.data.Out
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.Xpub
import com.samourai.sentinel.data.prevOut
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhirlpoolBackfillTest {

    private val collectionId = "col-uuid-1"   // contains dashes on purpose
    private val remixTxid = "c".repeat(64)
    private val tx0Txid = "d".repeat(64)

    private fun path(account: Long) = "m/84'/0'/$account'/0/7"

    private fun remixTx() = Tx(
        hash = "$remixTxid-$collectionId", time = 0L, version = 1,
        locktime = 0, result = null,
        inputs = List(5) {
            Inputs(vin = it, sequence = null,
                prevOut(addr = null, txid = "e".repeat(64),
                    value = 2_500_000L, vout = 0,
                    xpub = Xpub(m = null, path = path(2_147_483_646L))))
        },
        out = List(5) {
            Out(n = it, value = 2_500_000L, addr = null,
                xpub = Xpub(m = null, path = path(2_147_483_646L)))
        },
        block_height = null,
    )

    private fun tx0Tx() = Tx(
        hash = "$tx0Txid-$collectionId", time = 0L, version = 1,
        locktime = 0, result = null,
        inputs = listOf(
            Inputs(vin = 0, sequence = null,
                prevOut(addr = null, txid = "f".repeat(64),
                    value = 12_700_000L, vout = 0,
                    xpub = Xpub(m = null, path = path(0L))))
        ),
        out = List(5) {
            Out(n = it, value = 2_500_000L, addr = null,
                xpub = Xpub(m = null, path = path(2_147_483_645L)))
        } + listOf(
            Out(n = 5, value = 180_000L, addr = null,
                xpub = Xpub(m = null, path = path(2_147_483_644L)))
        ),
        block_height = null,
    )

    /** Ordinary badbank spend: 1-in/2-out, no xpub tags. */
    private fun badbankSpendTx() = Tx(
        hash = "ab-$collectionId", time = 0L, version = 1,
        locktime = 0, result = null,
        inputs = listOf(
            Inputs(vin = 0, sequence = null,
                prevOut(addr = null, txid = "0".repeat(64),
                    value = 2_500_000L, vout = 0,
                    xpub = Xpub(m = null, path = path(2_147_483_644L))))
        ),
        out = listOf(
            Out(n = 0, value = 1_000_000L, addr = "addr1", xpub = null),
            Out(n = 1, value = 1_400_000L, addr = "addr2", xpub = null),
        ),
        block_height = null,
    )

    private class FakeSink(var existing: ExistingLabel? = null) :
        WhirlpoolAutoWriter.Sink {

        val writes = mutableListOf<Triple<String, String, String>>()

        override suspend fun findTxLabel(txid: String) = existing

        override suspend fun writeAutoTxLabel(
            txid: String, label: String, origin: String,
        ) {
            writes += Triple(txid, label, origin)
            existing = ExistingLabel(label, origin)
        }
    }

    @Test fun backfill_remix_writtenWithBareTxid() = runBlocking {
        val sink = FakeSink()
        val result = WhirlpoolBackfill(WhirlpoolAutoWriter(sink))
            .run(listOf(remixTx()), collectionId)
        assertEquals(1, result.written)
        assertEquals(remixTxid, sink.writes[0].first)   // suffix stripped
        assertEquals("Whirlpool Remix", sink.writes[0].second)
    }

    @Test fun backfill_tx0_written() = runBlocking {
        val sink = FakeSink()
        val result = WhirlpoolBackfill(WhirlpoolAutoWriter(sink))
            .run(listOf(tx0Tx()), collectionId)
        assertEquals(1, result.written)
        assertEquals("Whirlpool TX0", sink.writes[0].second)
        assertEquals(tx0Txid, sink.writes[0].first)
    }

    @Test fun backfill_badbankSpend_unclassified() = runBlocking {
        val sink = FakeSink()
        val result = WhirlpoolBackfill(WhirlpoolAutoWriter(sink))
            .run(listOf(badbankSpendTx()), collectionId)
        assertEquals(1, result.unclassified)
        assertEquals(0, result.written)
        assertTrue(sink.writes.isEmpty())
    }

    @Test fun backfill_duplicateRows_dedupedCount() = runBlocking {
        val sink = FakeSink()
        val result = WhirlpoolBackfill(WhirlpoolAutoWriter(sink))
            .run(listOf(remixTx(), remixTx()), collectionId)
        assertEquals(1, result.processed)
        assertEquals(1, result.written)
        assertEquals(1, sink.writes.size)
    }

    @Test fun backfill_manualLabel_protectedAndCounted() = runBlocking {
        val sink = FakeSink(ExistingLabel("My own label", null))
        val result = WhirlpoolBackfill(WhirlpoolAutoWriter(sink))
            .run(listOf(remixTx()), collectionId)
        assertEquals(1, result.handsOff)
        assertEquals(0, result.written)
        assertTrue(sink.writes.isEmpty())
    }

    @Test fun backfill_mixedBatch_countsPartition() = runBlocking {
        val sink = FakeSink()
        val result = WhirlpoolBackfill(WhirlpoolAutoWriter(sink))
            .run(listOf(remixTx(), tx0Tx(), badbankSpendTx()), collectionId)
        assertEquals(3, result.processed)
        assertEquals(2, result.written)
        assertEquals(1, result.unclassified)
    }
}
