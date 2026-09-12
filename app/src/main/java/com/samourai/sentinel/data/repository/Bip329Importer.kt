package com.samourai.sentinel.data.repository

import androidx.room.withTransaction
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.db.dao.LabelEntryDao
import com.samourai.sentinel.data.db.dao.UtxoLabelDao
import com.samourai.sentinel.data.db.entity.LabelEntry
import com.samourai.sentinel.data.db.entity.LabelType
import com.samourai.sentinel.data.db.entity.UtxoLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import java.io.BufferedReader

data class ImportResult(val imported: Int, val updated: Int, val skipped: Int)

/**
 * Imports BIP-329 JSONL label data.
 *
 * Semantics (matching LabelRepository conventions):
 *  - "output" records land in utxo_labels; every other type in label_entries.
 *  - Merge-only: records are inserted or updated by primary key; an import
 *    never creates gaps. A blank label deletes an existing record (the same
 *    "empty label = remove" rule setLabel uses).
 *  - createdAt is preserved when a record already exists; only updatedAt moves.
 *  - Records that resolve to a different network than the active one are
 *    skipped (counted), never imported.
 *  - The whole batch is applied inside one transaction: all-or-nothing.
 */
class Bip329Importer {

    private val db: SentinelRoomDb by inject(SentinelRoomDb::class.java)
    private val utxoLabelDao: UtxoLabelDao by inject(UtxoLabelDao::class.java)
    private val labelEntryDao: LabelEntryDao by inject(LabelEntryDao::class.java)

    fun currentNetwork(): String =
        if (SentinelState.isTestNet()) "testnet" else "mainnet"

    sealed interface Parsed {
        data class Output(val label: UtxoLabel) : Parsed
        data class Other(val entry: LabelEntry) : Parsed
    }

    suspend fun import(reader: BufferedReader): ImportResult =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            var imported = 0
            var updated = 0
            var skipped = 0

            val outputs = mutableListOf<UtxoLabel>()
            val others = mutableListOf<LabelEntry>()

            reader.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) {
                    return@forEach
                }
                when (val p = parseLine(line, now)) {
                    null -> skipped++
                    is Parsed.Output -> outputs += p.label
                    is Parsed.Other -> others += p.entry
                }
            }

            db.withTransaction {
                outputs.forEach { row ->
                    val existing = utxoLabelDao.find(row.network, row.txid, row.vout)
                    if (row.label.isBlank()) {
                        // BIP-329 / setLabel semantics: empty label removes.
                        if (existing != null) {
                            utxoLabelDao.delete(row.network, row.txid, row.vout)
                            updated++
                        } else {
                            skipped++
                        }
                    } else {
                        if (existing == null) imported++ else updated++
                        utxoLabelDao.upsert(
                            row.copy(createdAt = existing?.createdAt ?: row.createdAt)
                        )
                    }
                }
                others.forEach { row ->
                    val existing = labelEntryDao.find(row.network, row.type, row.ref)
                    if (row.label.isBlank()) {
                        if (existing != null) {
                            labelEntryDao.delete(row.network, row.type, row.ref)
                            updated++
                        } else {
                            skipped++
                        }
                    } else {
                        if (existing == null) imported++ else updated++
                        labelEntryDao.upsert(
                            row.copy(createdAt = existing?.createdAt ?: row.createdAt)
                        )
                    }
                }
            }

            ImportResult(imported, updated, skipped)
        }

    private fun parseLine(line: String, now: Long): Parsed? {
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
                    network = currentNetwork(),   // txids carry no network info
                    txid = outpoint.first,
                    vout = outpoint.second,
                    label = label,
                    origin = origin,
                    createdAt = now,
                    updatedAt = now
                )
            )
        } else {
            val net = deriveNetwork(type, ref) ?: currentNetwork()
            if (net != currentNetwork()) {
                return null   // cross-network record: skipped, counted by caller
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
