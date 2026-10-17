package com.samourai.sentinel.data.db.entity

import androidx.room.Entity
import androidx.room.Index

enum class LabelType(val wire: String) {
    TX("tx"), ADDR("addr"), XPUB("xpub"), PUBKEY("pubkey"),
    INPUT("input"), OUTPUT("output");

    companion object {
        fun fromWire(s: String?) =
            entries.firstOrNull { it.wire == s?.trim()?.lowercase() }
    }
}

/**
 * BIP-329 label records other than "output" (output labels live in
 * utxo_labels). "type" holds the BIP-329 wire string ("tx", "addr", ...).
 *
 * Network-scoped like UtxoLabel: prevents label collisions if the
 * install is ever switched between networks.
 */
@Entity(
    tableName = "label_entries",
    primaryKeys = ["network", "type", "ref"],
    indices = [Index("network"), Index("network", "type")]
)
data class LabelEntry(
    val network: String,
    val type: String,
    val ref: String,
    val label: String,
    val origin: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
