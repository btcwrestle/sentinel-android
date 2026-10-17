package com.samourai.sentinel.data.whirlpool

/**
 * Pure-JVM Whirlpool transaction classifier (pattern: Bip329Parser —
 * no Android, Koin, Room or org.json dependencies).
 *
 * Layer 1 (deterministic, cannot misfire): account membership via the
 * fixed Samourai/Ashigaru BIP44 indices in WhirlpoolConfig. Accounts
 * arrive pre-resolved on WhirlpoolTxView IOs (Dojo xpub paths at sync
 * time, fixture metadata in tests) — the detector never parses
 * wallet state.
 *
 * Layer 2 (tiered, AND'd — never OR'd): structural signals (uniform
 * N-in/N-out, exact denomination) are only consulted on top of a
 * Layer-1 our-coin anchor. A Wasabi/JoinMarket/STONEWALLx2 round
 * carries no whirlpool accounts and classifies Unknown by
 * construction, whatever its shape.
 *
 * If a class cannot be proven, the detector says nothing
 * (Unknown) and no label is written.
 *
 * v1 scope notes (deliberate):
 *  - Remix lineage (per-UTXO k -> k+1 chains) needs a UTXO graph;
 *    deferred. Single-tx remix = postmix-in + postmix-out + structure.
 *  - OP_RETURN on TX0 is tri-state: confirmed when the source
 *    supplies scriptpubkeys, skipped (documented) when it does not.
 *    The app Tx model carries no scripts today.
 *  - TX0 requires badbank change output; a TX0 without one is a false
 *    negative, never a false positive. Acceptable per spec.
 */
object WhirlpoolDetector {

    fun classify(
        view: WhirlpoolTxView,
        config: WhirlpoolConfig = WhirlpoolConfig.DEFAULT,
    ): WhirlpoolClassification {
        val inAccounts = view.inputs.mapNotNull { it.account }.toSet()
        val outAccounts = view.outputs.mapNotNull { it.account }.toSet()

        val depositIn = config.depositAccount in inAccounts
        val premixIn = config.premixAccount in inAccounts
        val postmixIn = config.postmixAccount in inAccounts
        val premixOut = config.premixAccount in outAccounts
        val postmixOut = config.postmixAccount in outAccounts
        val badbankOut = config.badbankAccount in outAccounts

        // --- TX0: account facts alone prove the class (L1). Uniform
        // premix outputs + OP_RETURN (when scripts exist) raise to L2.
        if (depositIn && premixOut && badbankOut) {
            val uniform = uniformPremixOutputs(view, config)
            val opReturn = opReturnConfirmed(view)
            return WhirlpoolClassification.Tx0(
                tier = if (uniform && opReturn != false)
                    WhirlpoolClassification.Tier.L2
                else WhirlpoolClassification.Tier.L1
            )
        }

        // --- Mixes: structure is part of the definition. An arbitrary
        // premix/postmix spend must never be labeled, so there is no
        // L1-only mix classification.
        val denom = mixDenomination(view, config)
        if (denom != null) {
            return when {
                premixIn && postmixOut ->
                    WhirlpoolClassification.FirstMix(
                        denom, WhirlpoolClassification.Tier.L2)
                postmixIn && postmixOut ->
                    WhirlpoolClassification.Remix(
                        denom, WhirlpoolClassification.Tier.L2)
                else -> WhirlpoolClassification.Unknown
            }
        }

        return WhirlpoolClassification.Unknown
    }

    /**
     * Uniform mix shape: N inputs of one equal value, N outputs of
     * one equal value, output value in the configured denomination
     * set, inputs >= outputs (miner-fee headroom). Returns the output
     * denomination, or null when the shape does not hold.
     */
    private fun mixDenomination(
        view: WhirlpoolTxView,
        config: WhirlpoolConfig,
    ): Long? {
        val n = config.mixParticipants
        if (view.inputs.size != n || view.outputs.size != n) return null
        val outVal = view.outputs[0].value
        if (outVal !in config.denominationsSats) return null
        if (view.outputs.any { it.value != outVal }) return null
        // Observed shape (mainnet 076a1dcc, 6fd8e282): inputs are a mix
        // of exact denomination (remixers) and denomination + tx0 fee
        // buffer (first-mixers, buffer varies with fee era). Uniform
        // inputs were a wrong assumption; every input >= denomination
        // is the real shape.
        if (view.inputs.any { it.value < outVal }) return null
        return outVal
    }

    /** TX0: all premix-account outputs carry the same value. */
    private fun uniformPremixOutputs(
        view: WhirlpoolTxView,
        config: WhirlpoolConfig,
    ): Boolean {
        val premixOuts = view.outputs.filter { it.account == config.premixAccount }
        if (premixOuts.size < 2) return false
        return premixOuts.all { it.value == premixOuts[0].value }
    }

    /**
     * Tri-state OP_RETURN: true = null-data output present, false =
     * scripts supplied and none is null-data, null = no scripts
     * supplied (app Tx model) — check skipped.
     */
    private fun opReturnConfirmed(view: WhirlpoolTxView): Boolean? {
        if (view.outputs.all { it.script == null }) return null
        return view.outputs.any { it.script != null && it.script.startsWith("6a") }
    }

    /**
     * Account index from a BIP44/84 path ("m/84'/0'/2147483645'/0/7"
     * -> 2147483645). Hardened markers (' or h/H) optional. Null on
     * non-account-shaped paths.
     */
    fun accountFromPath(path: String): Long? {
        val parts = path.trim().split("/")
            .map { it.removeSuffix("'").removeSuffix("h").removeSuffix("H") }
        if (parts.size < 4 || parts[0] != "m") return null
        return parts[3].toLongOrNull()
    }
}

/** Single versioned source of constants (issue #6). */
data class WhirlpoolConfig(
    val version: String = "v1",
    val origin: String = "auto:whirlpool:v1",
    val depositAccount: Long = 0L,
    val badbankAccount: Long = 2_147_483_644L,   // 2^31 - 4
    val premixAccount: Long = 2_147_483_645L,    // 2^31 - 3
    val postmixAccount: Long = 2_147_483_646L,   // 2^31 - 2
    val mixParticipants: Int = 5,
    // Ashigaru observed pools: 0.025 / 0.25 BTC. Verify against the
    // external Ashigaru client fork before merge (not vendored in-tree).
    val denominationsSats: Set<Long> = setOf(2_500_000L, 25_000_000L),
    val labels: Labels = Labels(),
) {
    data class Labels(
        val tx0: String = "Whirlpool TX0",
        val firstMix: String = "Whirlpool Mix",
        val remix: String = "Whirlpool Remix",
        /** #46: utxo-level badbank label — the tx0 change output and,
         *  transitively, its wallet-descendant outputs. */
        val badbank: String = "Bad Bank",
    )

    companion object { val DEFAULT = WhirlpoolConfig() }
}

/**
 * Reduced transaction view. `account` = resolved BIP44/84 index for
 * that IO (Dojo xpub path or fixture metadata), null when unresolved.
 * `script` = scriptPubKey hex when the source supplies it.
 */
data class WhirlpoolTxView(
    val txid: String,
    val inputs: List<IO>,
    val outputs: List<IO>,
) {
    data class IO(
        val value: Long,
        val addr: String? = null,
        val account: Long? = null,
        val script: String? = null,
        /** Output position (n); utxo labels key on (txid, vout). Inputs: null. */
        val vout: Int? = null,
        /** Spent outpoint (prev_out txid/vout), inputs only — propagation lineage. */
        val spentTxid: String? = null,
        val spentVout: Int? = null,
    )
}

sealed interface WhirlpoolClassification {
    enum class Tier { L1, L2 }

    data class Tx0(val tier: Tier) : WhirlpoolClassification
    data class FirstMix(val denominationSats: Long, val tier: Tier) : WhirlpoolClassification
    data class Remix(val denominationSats: Long, val tier: Tier) : WhirlpoolClassification
    object Unknown : WhirlpoolClassification
}
