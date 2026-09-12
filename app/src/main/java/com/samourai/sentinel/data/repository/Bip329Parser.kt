package com.samourai.sentinel.data.repository

import com.samourai.sentinel.data.db.entity.LabelEntry
import com.samourai.sentinel.data.db.entity.LabelType
import com.samourai.sentinel.data.db.entity.UtxoLabel
import org.json.JSONObject

/**
 * Pure BIP-329 JSONL line parsing. No Android, Koin or database
 * dependencies, so it is unit-testable on the JVM.
 *
 *  - "output" records become Parsed.Output (utxo_labels row); all other
 *    types become Parsed.Other (label_entries row).
 *  - Records whose ref resolves to a different network than the active
 *    one return null (caller counts them as skipped).
 *  - A blank label still parses: the importer turns it into a delete
 *    (the "empty label = remove" convention from LabelRepository).
 *  - "origin" is opaque pass-through, emitted unchanged on export.
 */
object Bip329Parser {

    sealed interface Parsed {
        data class Output(val label: UtxoLabel) : Parsed
        data class Other(val entry: LabelEntry) : Parsed
    }

    fun parse(rawLine: String, network: String, now: Long): Parsed? {
        val line = rawLine.trim()
        if (line.isEmpty()) {
            return null
        }
        val obj = JSONObject(line)
        if (!obj.has("type") || !obj.has("ref") || !obj.has("label")) {
            return null
        }
        val type = LabelType.fromWire(obj.getString("type")) ?: return null
        val ref = obj.getString("ref").trim()
        val label = obj.getString("label")
        val origin = obj.optString("origin").takeIf { it.isNotBlank() }

        return if (type == LabelType.OUTPUT) {
            val outpoint = parseOutpoint(ref) ?: return null
            Parsed.Output(
                UtxoLabel(
                    network = network,   // txids carry no network info
                    txid = outpoint.first,
                    vout = outpoint.second,
                    label = label,
                    origin = origin,
                    createdAt = now,
                    updatedAt = now
                )
            )
        } else {
            val net = deriveNetwork(type, ref) ?: network
            if (net != network) {
                return null   // cross-network record
            }
            Parsed.Other(
                LabelEntry(
                    network = net,
                    type = type.wire,
                    ref = normalizeRef(type, ref),
                    label = label,
                    origin = origin,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    private fun parseOutpoint(ref: String): Pair<String, Int>? {
        val idx = ref.lastIndexOf(':')
        if (idx != 64) {
            return null   // txid must be 64 hex chars before the colon
        }
        val txid = ref.substring(0, idx).lowercase()
        if (txid.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            return null
        }
        val vout = ref.substring(idx + 1).toIntOrNull() ?: return null
        if (vout < 0) {
            return null
        }
        return txid to vout
    }

    private fun deriveNetwork(type: LabelType, ref: String): String? = when (type) {
        LabelType.ADDR -> when {
            ref.startsWith("bc1") || ref.startsWith("1") || ref.startsWith("3") -> "mainnet"
            ref.startsWith("tb1") || ref.startsWith("bcrt1")
                || ref.startsWith("m") || ref.startsWith("n") || ref.startsWith("2") -> "testnet"
            else -> null
        }
        LabelType.XPUB -> when (ref.take(4)) {
            "xpub", "ypub", "zpub" -> "mainnet"
            "tpub", "upub", "vpub" -> "testnet"
            else -> null
        }
        else -> null   // tx, pubkey, input: refs are network-agnostic
    }

    private fun normalizeRef(type: LabelType, ref: String): String = when (type) {
        LabelType.TX, LabelType.PUBKEY -> ref.lowercase()
        else -> ref
    }
}
