package com.samourai.sentinel.data.whirlpool

import com.samourai.sentinel.data.repository.LabelRepository

/**
 * Android Sink: LabelRepository -> WhirlpoolAutoWriter.Sink and
 * UtxoLabelSink (issues #6 part 2, #46). This is the whirlpool
 * package's only Android-side file; txid lowercasing is enforced by
 * the repository layer's storage invariant, not duplicated here.
 */
class WhirlpoolLabelSink(
    private val labelRepository: LabelRepository,
) : WhirlpoolAutoWriter.Sink, UtxoLabelSink {

    // --- tx labels (#6 part 2) ---

    override suspend fun findTxLabel(txid: String): ExistingLabel? =
        labelRepository.getTxLabel(txid)?.let {
            ExistingLabel(it.label, it.origin)
        }

    override suspend fun writeAutoTxLabel(
        txid: String, label: String, origin: String,
    ) = labelRepository.setAutoTxLabel(txid, label, origin)

    // --- utxo labels (#46) ---

    override suspend fun findUtxoLabel(txid: String, vout: Int) =
        labelRepository.getUtxoLabel(txid, vout)?.let {
            ExistingLabel(it.label, it.origin)
        }

    override suspend fun writeAutoUtxoLabel(
        txid: String, vout: Int, label: String, origin: String,
    ) = labelRepository.setAutoUtxoLabel(txid, vout, label, origin)

    override suspend fun deleteAutoUtxoLabel(
        txid: String, vout: Int, ourLabel: String,
    ) = labelRepository.deleteAutoUtxoLabelIfOurs(txid, vout, ourLabel)
}
