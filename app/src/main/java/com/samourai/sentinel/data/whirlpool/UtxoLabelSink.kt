package com.samourai.sentinel.data.whirlpool

/**
 * Utxo-label sink (issue #46). Pure JVM — mirrors WhirlpoolAutoWriter.Sink
 * so the badbank engines stay DB-free; the Android implementation lives
 * in WhirlpoolLabelSink over LabelRepository.
 */
interface UtxoLabelSink {

    /** Existing row for the outpoint, or null when no row exists. */
    suspend fun findUtxoLabel(txid: String, vout: Int): ExistingLabel?

    /** Writes label + origin explicitly (never origin-preserving). */
    suspend fun writeAutoUtxoLabel(
        txid: String, vout: Int, label: String, origin: String)

    /** Removes our auto label only — terminator supersession. */
    suspend fun deleteAutoUtxoLabel(txid: String, vout: Int, ourLabel: String)
}
