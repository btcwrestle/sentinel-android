package com.samourai.sentinel.data.whirlpool

/**
 * Origin-aware auto-writer (issue #6, part 2). Consumes the sealed
 * WhirlpoolClassification from part 1 and writes through a Sink that
 * sets origin explicitly — never via the manual setTxLabel path,
 * which preserves existing origin and cannot carry provenance.
 *
 * Idempotent in effect: duplicate events (issue #41's parallel
 * sockets) converge to the same stored row content. The guard lives
 * here, not in callers.
 *
 * Remix hands-off (issue #207, landed ahead of #197): a tx
 * classified Remix NEVER receives an auto tx label — the upstream
 * hardcoded "Whirlpool remix" render (05d12e5, 1f70866) is the sole
 * remix presentation, and an auto:whirlpool row on a remix would
 * duplicate it on the row and ride the BIP-329 export. Remixes
 * stay classifiable — detector scope is unchanged (lineage /
 * entropy / terminator consume the class) — and the writer routes
 * them to HANDS_OFF before touching the sink. The discriminator is
 * the Classification itself, never the render's result==0
 * heuristic.
 *
 * Pure JVM (pattern: part 1 / Bip329Parser). The Android Sink
 * implementation over LabelRepository lands in the wiring commit;
 * that adapter lowercases txids to match the storage invariant.
 */
class WhirlpoolAutoWriter(
    private val sink: Sink,
    private val config: WhirlpoolConfig = WhirlpoolConfig.DEFAULT,
) {

    interface Sink {
        /** Existing row for txid, or null when no row exists. */
        suspend fun findTxLabel(txid: String): ExistingLabel?

        /** Writes label + origin explicitly (never origin-preserving). */
        suspend fun writeAutoTxLabel(txid: String, label: String, origin: String)
    }

    /** Result of one processed tx; backfill counts these. */
    enum class Outcome { WRITTEN, HANDS_OFF, UNCLASSIFIED }

    suspend fun process(view: WhirlpoolTxView): Outcome {
        val classification = WhirlpoolDetector.classify(view, config)

        // #207: hands-off BEFORE the sink — a classified remix is
        // counted, never read, never written.
        if (classification is WhirlpoolClassification.Remix) {
            return Outcome.HANDS_OFF
        }

        val label = labelFor(classification) ?: return Outcome.UNCLASSIFIED
        return when (WhirlpoolLabelPolicy.decide(sink.findTxLabel(view.txid))) {
            WhirlpoolLabelPolicy.Decision.Write -> {
                sink.writeAutoTxLabel(view.txid, label, config.origin)
                Outcome.WRITTEN
            }
            WhirlpoolLabelPolicy.Decision.HandsOff -> Outcome.HANDS_OFF
        }
    }

    /**
     * Label text per classification; Unknown -> null. The detector
     * said nothing, so the writer writes nothing — and never touches
     * the sink (no find, no write). Remix maps to null the same way
     * — no remix label exists by design (#207) — though process()
     * routes remixes to HANDS_OFF before reaching here.
     */
    fun labelFor(classification: WhirlpoolClassification): String? =
        when (classification) {
            is WhirlpoolClassification.Tx0 -> config.labels.tx0
            is WhirlpoolClassification.FirstMix -> config.labels.firstMix
            is WhirlpoolClassification.Remix -> null
            WhirlpoolClassification.Unknown -> null
        }
}
