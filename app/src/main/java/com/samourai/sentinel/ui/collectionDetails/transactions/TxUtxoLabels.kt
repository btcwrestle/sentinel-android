package com.samourai.sentinel.ui.collectionDetails.transactions

import com.samourai.sentinel.data.Tx

/**
 * #55: pure resolver for utxo-level label chips (details sheet).
 *
 * Contract: given a details-sheet [Tx] and the live map from
 * [com.samourai.sentinel.data.repository.LabelRepository.observeLabelMap]
 * ("txid:vout" -> label, active network), return the distinct non-blank
 * labels attached to THIS tx's own outputs, in output order - exactly
 * the chip texts to render. Empty list = render nothing.
 *
 * Key normalization mirrors the tx-label lookup in
 * TransactionAdapter.onBindViewHolder: tx.hash may carry the "-index"
 * association suffix and any case; utxo_labels keys are bare lowercased
 * txids (the #46 auto-writer stores them lowercased, same invariant the
 * tx-label path renders by).
 *
 * Composes, never replaces (#55 design decision): "Bad Bank" belongs to
 * an output, not a tx - the tx-level label keeps its row, these chips
 * ride alongside it. Nothing here writes or relabels anything.
 *
 * Dedup is deliberate: two outputs carrying the same label (both TX0
 * change outputs labeled "Bad Bank") read as ONE chip in the sheet's
 * group - "what sits on this tx", not "how many outputs match".
 *
 * Pure JVM, no Android imports (pattern: PubsFingerprintTest). Render
 * site - TransactionsDetailsBottomSheet only, per #227's scope-first
 * rule; it stays a dumb view over chipsFor().
 */
object TxUtxoLabels {

    fun chipsFor(tx: Tx, utxoLabels: Map<String, String>): List<String> {
        if (utxoLabels.isEmpty() || tx.out.isEmpty()) return emptyList()
        val txid = tx.hash.split("-")[0].lowercase()
        return tx.out
            .mapNotNull { utxoLabels["$txid:${it.n}"] }
            .filter { it.isNotBlank() }
            .distinct()
    }
}
