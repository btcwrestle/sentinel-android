package com.samourai.sentinel.data.repository

import androidx.room.withTransaction
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.db.dao.LabelEntryDao
import com.samourai.sentinel.data.db.dao.UtxoLabelDao
import com.samourai.sentinel.data.db.entity.LabelEntry
import com.samourai.sentinel.data.db.entity.UtxoLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject
import java.io.BufferedReader

data class ImportResult(val imported: Int, val updated: Int, val skipped: Int)

/**
 * Imports BIP-329 JSONL label data. Line parsing lives in [Bip329Parser];
 * this class owns database merge semantics.
 *
 *  - "output" records land in utxo_labels; every other type in label_entries.
 *  - Merge-only: insert or update by primary key; never creates gaps.
 *  - A blank label deletes an existing record (empty label = remove,
 *    the same rule LabelRepository.setLabel uses).
 *  - createdAt is preserved when a record already exists.
 *  - The whole batch applies inside one transaction: all-or-nothing.
 */
class Bip329Importer {

    private val db: SentinelRoomDb by inject(SentinelRoomDb::class.java)
    private val utxoLabelDao: UtxoLabelDao by inject(UtxoLabelDao::class.java)
    private val labelEntryDao: LabelEntryDao by inject(LabelEntryDao::class.java)

    fun currentNetwork(): String =
        if (SentinelState.isTestNet()) "testnet" else "mainnet"

    suspend fun import(reader: BufferedReader): ImportResult =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            var imported = 0
            var updated = 0
            var skipped = 0

            val outputs = mutableListOf<UtxoLabel>()
            val others = mutableListOf<LabelEntry>()

            reader.lineSequence().forEach { raw ->
                when (val p = Bip329Parser.parse(raw, currentNetwork(), now)) {
                    null -> skipped++
                    is Bip329Parser.Parsed.Output -> outputs += p.label
                    is Bip329Parser.Parsed.Other -> others += p.entry
                }
            }

            db.withTransaction {
                outputs.forEach { row ->
                    val existing = utxoLabelDao.find(row.network, row.txid, row.vout)
                    if (row.label.isBlank()) {
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
}
