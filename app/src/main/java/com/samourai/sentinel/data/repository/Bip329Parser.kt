package com.samourai.sentinel.data.repository

import com.samourai.sentinel.data.db.entity.LabelEntry
import com.samourai.sentinel.data.db.entity.LabelType
import com.samourai.sentinel.data.db.entity.UtxoLabel
import org.json.JSONException
import org.json.JSONObject

/**
 * Pure BIP-329 JSONL line parsing. No Android, Koin or database
 * dependencies, so it is unit-testable on the JVM.
 *
 * Semantics:
 *  - "output" records become Parsed.Output (utxo_labels row); all other
 *    types become Parsed.Other (label_entries row).
 *  - ABSENT "label" field: Parsed.Ignored. Per BIP-329, an omitted label
 *    means "do not alter" — Sparrow exports metadata-only lines
 *    (value/height/time/rate/keypath) that are valid but carry no label
 *    statement. The importer counts these as noLabel; nothing is written.
 *  - EMPTY label (""): parses normally; the importer deletes any
 *    existing record for the ref (explicit clear).
 *  - Returns null for genuinely unusable lines: malformed JSON, missing
 *    type/ref, unknown type, bad outpoint, or cross-network addr/xpub
 *    ref. The importer counts null as skipped and logs the line number.
 *  - "origin" is opaque pass-through, emitted unchanged on export.
 */
object Bip329Parser {

    sealed interface Parsed {
        data class Output(val label: UtxoLabel) : Parsed
        data class Other(val entry: LabelEntry) : Parsed
        object Ignored : Parsed
    }

    fun parse(rawLine: String, network: String, now: Long): Parsed? {
        val line = rawLine.trim()
        if (line.isEmpty()) {
            return Parsed.Ignored
        }
        val obj = try {
            JSONObject(line)
        } catch (ex: JSONException) {
            return null
        }
        if (!obj.has("type") || !obj.has("ref")) {
            return null
        }
        val type = LabelType.fromWire(obj.getString("type")) ?: return null
        val ref = obj.getString("ref").trim()
        if (!obj.has("label")) {
            // BIP-329: omitted label = "do not alter". Valid line, no
            // label statement — not an error, not data.
            return Parsed.Ignored
        }
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
            // tx/pubkey/input refs are network-agnostic: keep the target
            // network. addr/xpub refs resolving to the OTHER network are
            // rejected; unclassifiable refs pass through unmodified.
            val net = deriveNetwork(type, ref) ?: network
            if (net != network) {
                return null
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
        else -> null   // tx, pubkey, input: network-agnostic
    }

    private fun normalizeRef(type: LabelType, ref: String): String = when (type) {
        LabelType.TX, LabelType.PUBKEY -> ref.lowercase()
        else -> ref
    }
}
