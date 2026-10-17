package com.samourai.sentinel.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached per-tx Boltzmann analysis (issue #7). Keyed by BARE txid:
 * the transactions table PK is txid-collectionId and gets wiped on
 * every sync — this cache must survive that (no collectionId, no
 * FK), so rows are computed once and never orphaned.
 *
 * tooComplex = engine declined (txos cap / time-box): displayed as
 * "too complex to analyze". Deterministic, so never recomputed.
 * ZeroEntropy is cached as nbCmbn = 1, entropyBits = 0.0.
 * linkabilityJson = Gson List<List<Double>>, [output][input] cells.
 */
@Entity(tableName = "tx_entropy")
data class TxEntropy(
    @PrimaryKey var txid: String,
    val nbCmbn: Int,
    val entropyBits: Double,
    val linkabilityJson: String,
    val tooComplex: Boolean,
    val computedAt: Long,
)
