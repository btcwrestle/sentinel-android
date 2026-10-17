package com.samourai.sentinel.data.whirlpool

/**
 * Deduces the badbank output of a classified TX0 (issue #46).
 * Pure JVM (pattern: WhirlpoolDetector / Bip329Parser).
 *
 * Discriminator is account identity: the single output resolving to
 * the badbank account (xpub identity, same resolution as #6 part 2).
 * "Amount not a pool denomination" is unsafe as a discriminator —
 * real premix outputs carry a per-TX0 fee buffer (device goldens:
 * 2,500,605 / 250,605 sats), so they never equal a denomination
 * exactly. The non-uniform value vs the premix group is a confirming
 * signal: if the badbank-account output MATCHES the uniform premix
 * value, the shape is ambiguous and the deducer says nothing.
 *
 * Say-nothing (null) cases, in the detector's philosophy:
 *  - multiple badbank-account outputs (ambiguous)
 *  - missing vout (outpoint not addressable, no label possible)
 *  - badbank value equals the uniform premix value (ambiguous)
 */
object BadbankDeducer {

    sealed interface Result {
        /** The deduced badbank output, addressable as (txid, vout). */
        data class Badbank(val vout: Int, val valueSats: Long) : Result

        /** Classified Tx0 but no output resolves to the badbank account
         *  (the detector's documented false-negative shape). */
        object NoBadbankOutput : Result
    }

    fun deduce(
        view: WhirlpoolTxView,
        classification: WhirlpoolClassification.Tx0,
        config: WhirlpoolConfig = WhirlpoolConfig.DEFAULT,
    ): Result? {
        val candidates = view.outputs.filter { it.account == config.badbankAccount }
        if (candidates.isEmpty()) return Result.NoBadbankOutput
        if (candidates.size > 1) return null
        val badbank = candidates[0]
        val vout = badbank.vout ?: return null

        // Confirming signal: the premix group is uniform (the detector
        // already required this for L2) and the badbank value differs.
        val premixValues = view.outputs
            .filter { it.account == config.premixAccount }
            .map { it.value }
            .toSet()
        if (premixValues.size == 1 && premixValues.single() == badbank.value) {
            return null
        }
        return Result.Badbank(vout, badbank.value)
    }
}
