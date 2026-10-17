package com.samourai.sentinel.data.whirlpool

/**
 * Per-tx badbank autolabeler (issue #46): TX0 deduction write +
 * phase-1 transitive propagation. Pure JVM — all label state flows
 * through UtxoLabelSink, mirroring WhirlpoolAutoWriter over its Sink.
 *
 * TX0 path: the deduced badbank output gets the utxo-level label
 * (guarded by WhirlpoolLabelPolicy — manual/imported rows win).
 *
 * Propagation (phase 1): a tx ALL of whose inputs spend our
 * auto-badbank outpoints carries the label onto its outputs that
 * resolve to watched keys (wallet-derived only — the account map is
 * built from the collection's own pubs; no explorer lookups by
 * construction). Unknown lineage (missing outpoint, no row, foreign
 * or manual origin) is NOT badbank: phase 1 says nothing. Mixed
 * inputs (badbank + clean) are phase 2 — never silently implemented.
 *
 * Terminator ("a coin can stop being a badbank descendant; it can
 * never silently stay one"): any tx the detector classifies, or any
 * tx with premix/postmix-account OUTPUTS, is whirlpool territory —
 * no propagation. The output check is load-bearing: a badbank
 * descendant entering a NEW tx0 spends badbank-account coins, so the
 * detector itself says Unknown there (its TX0 requires a deposit
 * input); the premix-output check catches what classification misses.
 *
 * Idempotent by convergence (#6 precedent): guarded upserts write
 * the same row content on repeat, and the wiring feeds only the
 * current sync's in-memory tx list. deleteAutoUtxoLabel is not used
 * in phase 1 — spent-outpoint label rows persist as history by
 * design (UtxoLabel doc); supersession means new outputs never
 * inherit, not that history is rewritten.
 */
class BadbankLabeler(
    private val sink: UtxoLabelSink,
    private val config: WhirlpoolConfig = WhirlpoolConfig.DEFAULT,
) {

    enum class Outcome {
        /** TX0: badbank output labelled (or refreshed). */
        TX0_LABELLED,
        /** TX0: badbank output carries a manual/imported label. */
        TX0_HANDS_OFF,
        /** TX0 without a badbank-account output (detector's known
         *  false-negative shape — honest, says nothing). */
        TX0_NO_BADBANK,
        /** TX0 whose badbank output is ambiguous (deducer null). */
        TX0_AMBIGUOUS,
        /** All inputs badbank-descendants; watched outputs labelled. */
        PROPAGATED,
        /** All inputs badbank-descendants; no output resolves to a
         *  watched key — nothing to label, by design. */
        NO_WATCHED_OUTPUTS,
        /** Some input's lineage is not our auto-badbank (clean, unknown,
         *  or mixed) — phase 1 says nothing. */
        NOT_DESCENDANT_SPEND,
        /** Detector labels (or the new-tx0 terminator) own this tx. */
        WHIRLPOOL_TERRITORY,
        /** No inputs — degenerate, say nothing. */
        NO_INPUTS,
    }

    suspend fun process(view: WhirlpoolTxView): Outcome {
        val cls = WhirlpoolDetector.classify(view, config)

        // --- TX0: deduction write ---
        if (cls is WhirlpoolClassification.Tx0) {
            return when (
                val d = BadbankDeducer.deduce(view, cls, config)
            ) {
                is BadbankDeducer.Result.Badbank ->
                    when (
                        WhirlpoolLabelPolicy.decide(
                            sink.findUtxoLabel(view.txid, d.vout))
                    ) {
                        WhirlpoolLabelPolicy.Decision.Write -> {
                            sink.writeAutoUtxoLabel(
                                view.txid, d.vout,
                                config.labels.badbank, config.origin,
                            )
                            Outcome.TX0_LABELLED
                        }
                        WhirlpoolLabelPolicy.Decision.HandsOff ->
                            Outcome.TX0_HANDS_OFF
                    }
                BadbankDeducer.Result.NoBadbankOutput -> Outcome.TX0_NO_BADBANK
                null -> Outcome.TX0_AMBIGUOUS
            }
        }

        // --- Terminator: detector territory, or premix/postmix outputs
        //     (catches badbank -> new tx0, which classification misses).
        if (cls != WhirlpoolClassification.Unknown) {
            return Outcome.WHIRLPOOL_TERRITORY
        }
        val outAccounts = view.outputs.mapNotNull { it.account }.toSet()
        if (config.premixAccount in outAccounts ||
            config.postmixAccount in outAccounts
        ) {
            return Outcome.WHIRLPOOL_TERRITORY
        }

        // --- Propagation, phase 1: ALL inputs our auto-badbank. ---
        if (view.inputs.isEmpty()) return Outcome.NO_INPUTS
        for (input in view.inputs) {
            val spentTxid = input.spentTxid ?: return Outcome.NOT_DESCENDANT_SPEND
            val spentVout = input.spentVout ?: return Outcome.NOT_DESCENDANT_SPEND
            if (!isOurBadbank(spentTxid, spentVout)) {
                return Outcome.NOT_DESCENDANT_SPEND
            }
        }

        val targets = view.outputs.filter {
            it.account != null && it.vout != null
        }
        if (targets.isEmpty()) return Outcome.NO_WATCHED_OUTPUTS
        for (out in targets) {
            if (
                WhirlpoolLabelPolicy.decide(
                    sink.findUtxoLabel(view.txid, out.vout!!)
                ) == WhirlpoolLabelPolicy.Decision.Write
            ) {
                sink.writeAutoUtxoLabel(
                    view.txid, out.vout!!,
                    config.labels.badbank, config.origin,
                )
            }
        }
        return Outcome.PROPAGATED
    }

    /** Our auto-origin badbank lineage only — manual "Bad Bank" or a
     *  foreign import is never propagation evidence. */
    private suspend fun isOurBadbank(txid: String, vout: Int): Boolean {
        val existing = sink.findUtxoLabel(txid, vout) ?: return false
        // NB: a binary operator must never START a line — Kotlin ends
        // the expression at the newline (the `== true` line-start was
        // this file's only compile red). Parenthesized regions are exempt.
        val ours = existing.origin?.startsWith(WhirlpoolLabelPolicy.AUTO_NAMESPACE)
        return existing.label == config.labels.badbank && ours == true
    }
}
