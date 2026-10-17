package com.samourai.sentinel.data.whirlpool

import com.samourai.sentinel.data.Tx

/**
 * Flag-gated, idempotent backfill over fetched transactions
 * (issue #6, part 2). Pure JVM. The caller (sync wiring) supplies
 * the in-memory tx list from fetchFromServer — no DB read, so no
 * race with saveTx's async launch and no new DAO surface.
 *
 * The same underlying tx can arrive once per watched pubkey
 * (keepTransactionWithVariousPubkeys); counts are deduped by txid
 * so the reported number is honest, while the writer itself stays
 * idempotent under repeats regardless.
 */
class WhirlpoolBackfill(private val writer: WhirlpoolAutoWriter) {
    companion object {
        /**
         * Dojo returns one partial tx per watched pubkey (inputs-only,
         * outputs-only, ...). Merge rows sharing a bare txid: union
         * inputs by vin and outputs by n. Order-independent.
         */
        fun mergePartialTxs(txs: Collection<Tx>, collectionId: String): List<Tx> {
            val merged = LinkedHashMap<String, Tx>()
            for (tx in txs) {
                val txid = tx.hash.removeSuffix("-$collectionId")
                val prior = merged[txid]
                merged[txid] = if (prior == null) tx else prior.copy(
                    inputs = prior.inputs + tx.inputs.filter { new ->
                        prior.inputs.none { it.vin == new.vin }
                    },
                    out = prior.out + tx.out.filter { new ->
                        prior.out.none { it.n == new.n }
                    },
                )
            }
            return merged.values.toList()
        }
    }

    data class Result(
        val processed: Int,
        val written: Int,
        val handsOff: Int,
        val unclassified: Int,
    )

    suspend fun run(
        txs: Collection<Tx>,
        collectionId: String,
        accountOfXpub: Map<String, Long> = emptyMap(),
    ): Result {
        val merged = mergePartialTxs(txs, collectionId)
        var written = 0
        var handsOff = 0
        var unclassified = 0
        for (tx in merged) {
            val view = WhirlpoolTxAdapter.toView(tx, collectionId, accountOfXpub)
            when (writer.process(view)) {
                WhirlpoolAutoWriter.Outcome.WRITTEN -> written++
                WhirlpoolAutoWriter.Outcome.HANDS_OFF -> handsOff++
                WhirlpoolAutoWriter.Outcome.UNCLASSIFIED -> unclassified++
            }
        }
        return Result(merged.size, written, handsOff, unclassified)
    }
}
