package com.samourai.sentinel.data.whirlpool

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhirlpoolAutoWriterTest {

    // ---- fixtures (accounts from WhirlpoolConfig.DEFAULT) ----

    private val txid = "a".repeat(64)
    private val otherTxid = "b".repeat(64)

    private fun io(value: Long, account: Long?) =
        WhirlpoolTxView.IO(value = value, account = account)

    /** Remix: 5 postmix-in / 5 postmix-out at 0.025 BTC. */
    private fun remixView(tx: String = txid) = WhirlpoolTxView(
        txid = tx,
        inputs = List(5) { io(2_500_000L, 2_147_483_646L) },
        outputs = List(5) { io(2_500_000L, 2_147_483_646L) },
    )

    /** First mix: 5 premix-in / 5 postmix-out at 0.025 BTC. */
    private fun firstMixView(tx: String = otherTxid) = WhirlpoolTxView(
        txid = tx,
        inputs = List(5) { io(2_518_000L, 2_147_483_645L) },
        outputs = List(5) { io(2_500_000L, 2_147_483_646L) },
    )

    /** TX0: deposit-in, 5 uniform premix-out, badbank change. */
    private fun tx0View(tx: String = txid) = WhirlpoolTxView(
        txid = tx,
        inputs = listOf(io(12_700_000L, 0L)),
        outputs = List(5) { io(2_500_000L, 2_147_483_645L) } +
            listOf(io(180_000L, 2_147_483_644L)),
    )

    /**
     * Negative fixture: an ordinary badbank spend (1-in/2-out, no mix
     * shape). Must classify Unknown -> UNCLASSIFIED, sink untouched.
     */
    private fun badbankSpendView() = WhirlpoolTxView(
        txid = txid,
        inputs = listOf(io(2_500_000L, 2_147_483_644L)),
        outputs = listOf(io(1_000_000L, null), io(1_400_000L, null)),
    )

    private class FakeSink(var existing: ExistingLabel? = null) :
        WhirlpoolAutoWriter.Sink {

        val writes = mutableListOf<Triple<String, String, String>>()
        var reads = 0

        override suspend fun findTxLabel(txid: String): ExistingLabel? {
            reads++
            return existing
        }

        override suspend fun writeAutoTxLabel(
            txid: String, label: String, origin: String,
        ) {
            writes += Triple(txid, label, origin)
            existing = ExistingLabel(label, origin)
        }
    }

    // ---- policy truth table ----

    @Test fun policy_noRow_writes() =
        assertEquals(
            WhirlpoolLabelPolicy.Decision.Write,
            WhirlpoolLabelPolicy.decide(null),
        )

    @Test fun policy_autoNamespace_writes() =
        assertEquals(
            WhirlpoolLabelPolicy.Decision.Write,
            WhirlpoolLabelPolicy.decide(
                ExistingLabel("Whirlpool Remix", "auto:whirlpool:v1")),
        )

    @Test fun policy_otherAuto_writes() =
        assertEquals(
            WhirlpoolLabelPolicy.Decision.Write,
            WhirlpoolLabelPolicy.decide(
                ExistingLabel("x", "auto:somethingelse")),
        )

    @Test fun policy_nullOriginRow_handsOff() =
        assertEquals(
            WhirlpoolLabelPolicy.Decision.HandsOff,
            WhirlpoolLabelPolicy.decide(
                ExistingLabel("My own label", null)),
        )

    @Test fun policy_foreignOrigin_handsOff() =
        assertEquals(
            WhirlpoolLabelPolicy.Decision.HandsOff,
            WhirlpoolLabelPolicy.decide(
                ExistingLabel("imported", "sparrow")),
        )

    @Test fun policy_emptyOrigin_handsOff() =
        assertEquals(
            WhirlpoolLabelPolicy.Decision.HandsOff,
            WhirlpoolLabelPolicy.decide(ExistingLabel("weird", "")),
        )

    // ---- writer: classification -> label ----

    @Test fun writer_remix_writesRemixLabel() = runBlocking {
        val sink = FakeSink()
        val out = WhirlpoolAutoWriter(sink).process(remixView())
        assertEquals(WhirlpoolAutoWriter.Outcome.WRITTEN, out)
        assertEquals(1, sink.writes.size)
        assertEquals(txid, sink.writes[0].first)
        assertEquals("Whirlpool Remix", sink.writes[0].second)
        assertEquals("auto:whirlpool:v1", sink.writes[0].third)
    }

    @Test fun writer_firstMix_writesMixLabel() = runBlocking {
        val sink = FakeSink()
        val out = WhirlpoolAutoWriter(sink).process(firstMixView())
        assertEquals(WhirlpoolAutoWriter.Outcome.WRITTEN, out)
        assertEquals("Whirlpool Mix", sink.writes[0].second)
    }

    @Test fun writer_tx0_writesTx0Label() = runBlocking {
        val sink = FakeSink()
        val out = WhirlpoolAutoWriter(sink).process(tx0View())
        assertEquals(WhirlpoolAutoWriter.Outcome.WRITTEN, out)
        assertEquals("Whirlpool TX0", sink.writes[0].second)
    }

    // ---- writer: guard ----

    @Test fun writer_badbankSpend_unclassified_sinkUntouched() = runBlocking {
        val sink = FakeSink()
        val out = WhirlpoolAutoWriter(sink).process(badbankSpendView())
        assertEquals(WhirlpoolAutoWriter.Outcome.UNCLASSIFIED, out)
        assertEquals(0, sink.reads)
        assertEquals(0, sink.writes.size)
    }

    @Test fun writer_manualLabel_handsOff() = runBlocking {
        val sink = FakeSink(ExistingLabel("My own label", null))
        val out = WhirlpoolAutoWriter(sink).process(remixView())
        assertEquals(WhirlpoolAutoWriter.Outcome.HANDS_OFF, out)
        assertEquals(0, sink.writes.size)
    }

    @Test fun writer_foreignImport_handsOff() = runBlocking {
        val sink = FakeSink(ExistingLabel("from sparrow", "sparrow"))
        val out = WhirlpoolAutoWriter(sink).process(remixView())
        assertEquals(WhirlpoolAutoWriter.Outcome.HANDS_OFF, out)
        assertEquals(0, sink.writes.size)
    }

    @Test fun writer_ownNamespace_refreshes() = runBlocking {
        val sink = FakeSink(
            ExistingLabel("Whirlpool Mix", "auto:whirlpool:v1"))
        val out = WhirlpoolAutoWriter(sink).process(remixView())
        assertEquals(WhirlpoolAutoWriter.Outcome.WRITTEN, out)
        assertEquals("Whirlpool Remix", sink.writes[0].second)
    }

    /**
     * Issue #41 fan-in: the same tx delivered over parallel sockets.
     * Every attempt re-decides and re-writes identical content — final
     * state converges. Source-side dedup is #41's fix, not the
     * writer's; this pins that the writer is safe meanwhile.
     */
    @Test fun writer_duplicateEvents_converge() = runBlocking {
        val sink = FakeSink()
        val writer = WhirlpoolAutoWriter(sink)
        repeat(4) {
            assertEquals(
                WhirlpoolAutoWriter.Outcome.WRITTEN, writer.process(remixView()))
        }
        assertTrue(sink.writes.all { it == sink.writes[0] })
    }
}
