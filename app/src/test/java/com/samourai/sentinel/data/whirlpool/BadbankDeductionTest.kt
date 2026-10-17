package com.samourai.sentinel.data.whirlpool

import com.google.gson.reflect.TypeToken
import com.samourai.sentinel.data.Inputs
import com.samourai.sentinel.data.Out
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.helpers.fromJSON
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.lang.reflect.Type

/**
 * Badbank deduction goldens (issue #46): the two real testnet TX0s
 * from the device DB, as verbatim Dojo partial rows. Payloads are
 * never hand-edited; expect/badbankVout/badbankValue are the manifest
 * knobs. Double-parse (string -> Gson list) mirrors Room's
 * TxInputConverter read path.
 */
class BadbankDeductionTest {

    private val inputsType: Type = object : TypeToken<List<Inputs>>() {}.type
    private val outsType: Type = object : TypeToken<List<Out>>() {}.type

    // The account vpubs the payload echoes, mapped to the fixed
    // Samourai indices (WhirlpoolConfig). The sync wiring builds the
    // same map via x.child + 2^31 across all six serializations
    // (TransactionsRepository); the test maps the literal `m` strings.
    private val accountOfXpub = mapOf(
        "vpub5Yig39b82QaiLtyWHh1xQUUD8fdQNwoNvGEHzjWNLZHhw41DcXPMP4AQZ2oj3RQWdVGE27STV11qxXxvb7HSWbuyF4zeEPHMY8GLk5Vc8nd" to
            WhirlpoolConfig.DEFAULT.depositAccount,
        "vpub5Yig39bGN57gMEQ1Jcown1nhaHr9twE8L4xhRTSP6PxXnYdEhCGxrHx9BLWY4KHNicP5WBKxZ2AWx8jRXSZZfneJMSij6WbDxZp8i5ni2qe" to
            WhirlpoolConfig.DEFAULT.premixAccount,
        "vpub5Yig39bGN57gLvDjA3S9JjXE7KRueWiNRUGZUA5tzcFQ6m5EAziMCi9aFaySGvvrkCpTjqKyDJdsShHQRwyVnNMwupJhNkLYx69n4erkSyp" to
            WhirlpoolConfig.DEFAULT.badbankAccount,
    )

    private data class Row(val tx: Tx, val meta: JSONObject)

    private fun rows(): List<Row> =
        javaClass.getResourceAsStream("/whirlpool/dojo_tx0_goldens.jsonl")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }
            .map { raw ->
                val obj = JSONObject(raw)
                Row(
                    Tx(
                        // DB rows carry the save-time row index
                        // (txid-collectionId-N); the backfill consumes the
                        // PRE-mangle in-memory list where hashes are
                        // txid-collectionId (TransactionsRepository: the
                        // whirlpool block runs BEFORE
                        // keepTransactionWithVariousPubkeys mangles hashes).
                        // Strip the index here; everything else verbatim.
                        hash = obj.getString("hash")
                            .replaceFirst("-\\d+$".toRegex(), ""),
                        time = obj.getLong("time"),
                        associatedPubKey = obj.getString("associatedPubKey"),
                        collectionId = obj.getString("collectionId"),
                        version = obj.getInt("version"),
                        locktime = obj.getInt("locktime"),
                        result = if (obj.isNull("result")) null
                                 else obj.getLong("result"),
                        inputs = fromJSON(obj.getString("inputs"), inputsType)!!,
                        out = fromJSON(obj.getString("out"), outsType)!!,
                        block_height = if (obj.isNull("block_height")) null
                                       else obj.getLong("block_height"),
                        confirmations = obj.getLong("confirmations"),
                    ),
                    obj,
                )
            }

    @Test fun deduction_goldens() = runBlocking {
        val all = rows()
        assertEquals(6, all.size)                    // 3 partial rows x 2 txids
        val collectionId = all.first().tx.collectionId
        val merged = WhirlpoolBackfill.mergePartialTxs(
            all.map { it.tx }, collectionId)
        assertEquals(2, merged.size)                 // 3 rows per txid -> 1

        val metaByTxid = all.associate {
            it.tx.hash.removeSuffix("-$collectionId") to it.meta
        }
        merged.forEach { tx ->
            val bareTxid = tx.hash.removeSuffix("-$collectionId")
            val meta = metaByTxid[bareTxid]!!
            val view = WhirlpoolTxAdapter.toView(tx, collectionId, accountOfXpub)
            val cls = WhirlpoolDetector.classify(view)
            // App Tx model carries no scripts: OP_RETURN tri-state is
            // "skipped" — NOT disconfirming — so uniform premix classifies
            // at L2 (detector's own matrix: L1 is the scripts-supplied-and-
            // no-null-data case). The golden caught a wrong L1 prediction
            // here; the fixture doing its job.
            assertEquals(
                "txid $bareTxid",
                WhirlpoolClassification.Tx0(WhirlpoolClassification.Tier.L2),
                cls,
            )
            val deduced = BadbankDeducer.deduce(
                view, cls as WhirlpoolClassification.Tx0)
            assertNotNull("txid $bareTxid: expected a badbank deduction", deduced)
            deduced as BadbankDeducer.Result.Badbank
            assertEquals("txid $bareTxid", meta.getInt("badbankVout"), deduced.vout)
            assertEquals("txid $bareTxid", meta.getLong("badbankValue"), deduced.valueSats)
        }
    }

    // --- Branch oracles (synthetic; deduce's contract given Tx0) ---

    @Test fun noBadbankOutput_whenTx0LacksBadbankAccountOutput() {
        // Detector false-negative shape documented in its v1 notes.
        // Classification passed as given: this tests deduce's contract.
        val view = WhirlpoolTxView(
            "aa".repeat(32),
            listOf(WhirlpoolTxView.IO(100_000_000, account = 0L)),
            listOf(
                WhirlpoolTxView.IO(2_500_605, account = 2_147_483_645L, vout = 0),
                WhirlpoolTxView.IO(2_500_605, account = 2_147_483_645L, vout = 1),
            ),
        )
        assertEquals(
            BadbankDeducer.Result.NoBadbankOutput,
            BadbankDeducer.deduce(
                view, WhirlpoolClassification.Tx0(WhirlpoolClassification.Tier.L1)),
        )
    }

    @Test fun ambiguous_multipleBadbankOutputs_sayNothing() {
        val view = WhirlpoolTxView(
            "bb".repeat(32),
            listOf(WhirlpoolTxView.IO(100_000_000, account = 0L)),
            listOf(
                WhirlpoolTxView.IO(2_500_605, account = 2_147_483_645L, vout = 0),
                WhirlpoolTxView.IO(90_000_000, account = 2_147_483_644L, vout = 1),
                WhirlpoolTxView.IO(5_000_000, account = 2_147_483_644L, vout = 2),
            ),
        )
        assertEquals(
            null,
            BadbankDeducer.deduce(
                view, WhirlpoolClassification.Tx0(WhirlpoolClassification.Tier.L1)),
        )
    }

    @Test fun missingVout_sayNothing() {
        // vout lost (e.g. a source that drops output positions):
        // cannot address a utxo label, so no deduction.
        val view = WhirlpoolTxView(
            "cc".repeat(32),
            listOf(WhirlpoolTxView.IO(100_000_000, account = 0L)),
            listOf(
                WhirlpoolTxView.IO(2_500_605, account = 2_147_483_645L),
                WhirlpoolTxView.IO(90_000_000, account = 2_147_483_644L),
            ),
        )
        assertEquals(
            null,
            BadbankDeducer.deduce(
                view, WhirlpoolClassification.Tx0(WhirlpoolClassification.Tier.L1)),
        )
    }

    /** Golden round-trip through the full labeler write path: both
     *  device TX0s label exactly (txid, 22) with our origin, converge
     *  on a second run, and write nothing else. */
    @Test fun labeler_goldenRoundTrip() = runBlocking {
        class FakeSink(
            var rows: MutableMap<Pair<String, Int>, ExistingLabel> = mutableMapOf()
        ) : UtxoLabelSink {
            override suspend fun findUtxoLabel(txid: String, vout: Int) =
                rows[txid to vout]
            override suspend fun writeAutoUtxoLabel(
                txid: String, vout: Int, label: String, origin: String,
            ) { rows[txid to vout] = ExistingLabel(label, origin) }
            override suspend fun deleteAutoUtxoLabel(
                txid: String, vout: Int, ourLabel: String) {}
        }
        val all = rows()
        val collectionId = all.first().tx.collectionId
        val merged = WhirlpoolBackfill.mergePartialTxs(
            all.map { it.tx }, collectionId)
        val sink = FakeSink()
        val labeler = BadbankLabeler(sink)
        val expected = mutableMapOf<Pair<String, Int>, ExistingLabel>()
        merged.forEach { tx ->
            val bareTxid = tx.hash.removeSuffix("-$collectionId")
            val view = WhirlpoolTxAdapter.toView(tx, collectionId, accountOfXpub)
            assertEquals(
                BadbankLabeler.Outcome.TX0_LABELLED, labeler.process(view))
            expected[bareTxid to 22] =
                ExistingLabel(WhirlpoolConfig.DEFAULT.labels.badbank,
                    WhirlpoolConfig.DEFAULT.origin)
        }
        assertEquals(expected, sink.rows)   // exactly (txid, 22) x 2
        merged.forEach { tx ->
            val view = WhirlpoolTxAdapter.toView(tx, collectionId, accountOfXpub)
            labeler.process(view)           // idempotent convergence
        }
        assertEquals(expected, sink.rows)
    }
}
