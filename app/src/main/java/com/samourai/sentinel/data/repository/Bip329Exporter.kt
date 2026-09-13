package com.samourai.sentinel.data.repository

import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.db.dao.LabelEntryDao
import com.samourai.sentinel.data.db.dao.UtxoLabelDao
import com.samourai.sentinel.data.db.entity.UtxoLabel
import com.samourai.sentinel.data.db.entity.LabelEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import java.io.PrintWriter

/**
 * Exports labels as BIP-329 JSONL.
 *
 *  - "output" records from utxo_labels (sorted by txid, vout)
 *  - all other types from label_entries (sorted by type, ref)
 *
 * Sorting makes exports deterministic: re-exporting an unchanged
 * wallet produces byte-identical files, so diffs between exports
 * show only real label changes. "origin" is emitted only when
 * present, exactly as imported (opaque pass-through).
 */
class Bip329Exporter {

    private val utxoLabelDao: UtxoLabelDao by inject(UtxoLabelDao::class.java)
    private val labelEntryDao: LabelEntryDao by inject(LabelEntryDao::class.java)

    fun currentNetwork(): String =
        if (SentinelState.isTestNet()) "testnet" else "mainnet"

    suspend fun export(writer: PrintWriter) {
        withContext(Dispatchers.IO) {
            val network = currentNetwork()
            writeOutputs(writer, utxoLabelDao.getAll(network))
            writeOthers(writer, labelEntryDao.getAll(network))
            writer.flush()
        }
    }

    private fun writeOutputs(writer: PrintWriter, rows: List<UtxoLabel>) {
        rows.sortedWith(compareBy({ it.txid }, { it.vout }))
            .forEach { row ->
                val o = JSONObject()
                    .put("type", "output")
                    .put("ref", "${row.txid}:${row.vout}")
                    .put("label", row.label)
                row.origin?.let { o.put("origin", it) }
                writer.println(o.toString())
            }
    }

    private fun writeOthers(writer: PrintWriter, rows: List<LabelEntry>) {
        rows.sortedWith(compareBy({ it.type }, { it.ref }))
            .forEach { row ->
                val o = JSONObject()
                    .put("type", row.type)
                    .put("ref", row.ref)
                    .put("label", row.label)
                row.origin?.let { o.put("origin", it) }
                writer.println(o.toString())
            }
    }
}
