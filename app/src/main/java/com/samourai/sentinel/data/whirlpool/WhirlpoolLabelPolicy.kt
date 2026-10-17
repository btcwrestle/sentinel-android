package com.samourai.sentinel.data.whirlpool

/**
 * Manual-provenance guard for the auto-writer (issue #6, part 2).
 * Pure JVM — no Android, Koin or Room dependencies.
 *
 * Truth table, on an EXISTING label row:
 *  - no row at all         -> Write    (fresh: auto may label)
 *  - origin starts auto:   -> Write    (our namespace, safe to refresh)
 *  - origin null           -> HandsOff (manual user label)
 *  - origin foreign        -> HandsOff (BIP-329 / Sparrow import)
 *  - origin empty string   -> HandsOff (never widen our namespace to it)
 *
 * The null ambiguity is load-bearing: a missing row and a row with a
 * null origin are DIFFERENT facts. Callers must distinguish them —
 * see WhirlpoolAutoWriter.Sink.
 */
object WhirlpoolLabelPolicy {

    const val AUTO_NAMESPACE = "auto:"

    sealed interface Decision {
        object Write : Decision
        object HandsOff : Decision
    }

    fun decide(existing: ExistingLabel?): Decision = when {
        existing == null -> Decision.Write
        existing.origin?.startsWith(AUTO_NAMESPACE) == true -> Decision.Write
        else -> Decision.HandsOff
    }
}

/** A pre-existing tx label row, as the writer sees it. */
data class ExistingLabel(val label: String, val origin: String?)
