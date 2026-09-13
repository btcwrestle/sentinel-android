package com.samourai.sentinel.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A user-defined label for a specific transaction output (BIP329 "output"
 * record), keyed by outpoint and network.
 *
 * Stored in a dedicated table (never merged into the sync-managed
 * [com.samourai.sentinel.data.Utxo] rows) so labels survive backend UTXO
 * replacement during syncs, and persist even after the output is spent -
 * useful wallet history and BIP329 round-tripping both require that.
 *
 * [network] ("mainnet"/"testnet") prevents label collisions if the install
 * is ever switched between networks.
 */
@Entity(
    tableName = "utxo_labels",
    primaryKeys = ["network", "txid", "vout"],
    indices = [Index("network")]
)
data class UtxoLabel(
    val network: String,
    val txid: String,
    val vout: Int,
    val label: String,
    val origin: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
