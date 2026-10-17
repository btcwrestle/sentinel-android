package com.samourai.sentinel.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.db.dao.LabelEntryDao
import com.samourai.sentinel.data.db.SentinelRoomDb
import androidx.room.withTransaction
import com.samourai.sentinel.data.db.dao.UtxoLabelDao
import com.samourai.sentinel.data.db.entity.LabelEntry
import com.samourai.sentinel.data.db.entity.LabelType
import com.samourai.sentinel.data.db.entity.UtxoLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject

/**
 * User-defined labels (BIP329).
 *
 * UTXO labels ("output" records) live in utxo_labels; tx/addr/pubkey/xpub
 * labels live in label_entries. All access is scoped to the active network
 * ("mainnet"/"testnet") so labels can never leak across networks. Labels
 * are stored independently of the sync-managed utxos table and therefore
 * survive backend refreshes.
 *
 * Storage invariants (enforced here so UI callers can be naive):
 *  - tx refs are stored lowercased; observeTxLabel/setTxLabel normalize.
 *  - addr refs are stored verbatim; observeAddrLabel passes input through.
 *  - a blank label deletes the record (BIP329: empty label = remove).
 */
class LabelRepository {

    private val dao: UtxoLabelDao by inject(UtxoLabelDao::class.java)
    private val entryDao: LabelEntryDao by inject(LabelEntryDao::class.java)
    private val roomDb: SentinelRoomDb by inject(SentinelRoomDb::class.java)

    fun currentNetwork(): String =
        if (SentinelState.isTestNet()) "testnet" else "mainnet"

    //
    // UTXO labels (utxo_labels)
    //

    fun observe(txid: String, vout: Int): LiveData<UtxoLabel?> =
        dao.observe(currentNetwork(), txid, vout)

    /**
     * Live map of "txid:vout" -> label for the active network.
     *
     * MediatorLiveData instead of Transformations.map: the project's resolved
     * lifecycle version does not expose Transformations to Kotlin compilation,
     * while MediatorLiveData is already used elsewhere in the codebase.
     */
    fun observeLabelMap(): LiveData<Map<String, String>> {
        val result = MediatorLiveData<Map<String, String>>()
        result.addSource(dao.observeAll(currentNetwork())) { labels ->
            result.value = labels.associate { "${it.txid}:${it.vout}" to it.label }
        }
        return result
    }

    suspend fun getAll(): List<UtxoLabel> =
        withContext(Dispatchers.IO) { dao.getAll(currentNetwork()) }

    /**
     * Sets or updates a label. A blank label deletes the record
     * (agreed BIP329 semantics: empty label = remove).
     */
    suspend fun setLabel(txid: String, vout: Int, label: String) =
        withContext(Dispatchers.IO) {
            val network = currentNetwork()
            val trimmed = label.trim()
            if (trimmed.isEmpty()) {
                dao.delete(network, txid, vout)
            } else {
                val now = System.currentTimeMillis()
                val existing = dao.find(network, txid, vout)
                dao.upsert(
                    UtxoLabel(
                        network = network,
                        txid = txid,
                        vout = vout,
                        label = trimmed,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now
                    )
                )
            }
        }

    //
    // Transaction labels (label_entries, type "tx")
    //

    /**
     * Live label for a single transaction. Input is lowercased to match
     * the storage invariant from BIP329 import normalization.
     */
    fun observeTxLabel(txid: String): LiveData<LabelEntry?> =
        entryDao.observe(currentNetwork(), LabelType.TX.wire, txid.lowercase())

    /**
     * Live map of txid -> label for all tx labels on the active network.
     * Intended for list adapters: observe once, look up per row.
     */
    fun observeTxLabelMap(): LiveData<Map<String, String>> {
        val result = MediatorLiveData<Map<String, String>>()
        result.addSource(
            entryDao.observeAllForType(currentNetwork(), LabelType.TX.wire)
        ) { entries ->
            result.value = entries.associate { it.ref to it.label }
        }
        return result
    }

    /**
     * Sets, updates or removes (blank) a transaction label. Input txid is
     * lowercased to match storage. Preserves createdAt and origin from an
     * existing record (e.g. from a BIP329 import).
     */
    suspend fun setTxLabel(txid: String, label: String) =
        setEntryLabel(LabelType.TX, txid.lowercase(), label)

    /**
     * Existing tx label row, or null when none exists. Txid lowercased
     * to match storage. Distinguishes "no row" from "row with a null
     * origin" — the auto-writer's provenance guard depends on that
     * difference (issue #6 part 2).
     */
    suspend fun getTxLabel(txid: String): LabelEntry? =
        withContext(Dispatchers.IO) {
            entryDao.find(currentNetwork(), LabelType.TX.wire, txid.lowercase())
        }

    /**
     * Writes a detector-originated tx label. Unlike setTxLabel this
     * sets the provided origin explicitly instead of preserving an
     * existing one, and never deletes (auto labels are never blank).
     * The manual-provenance guard lives in WhirlpoolAutoWriter, not
     * here — callers must have passed it before calling this.
     */
    suspend fun setAutoTxLabel(txid: String, label: String, origin: String) =
        withContext(Dispatchers.IO) {
            val network = currentNetwork()
            val ref = txid.lowercase()
            val trimmed = label.trim()
            check(trimmed.isNotEmpty()) { "auto label must not be blank" }
            val now = System.currentTimeMillis()
            val existing = entryDao.find(network, LabelType.TX.wire, ref)
            entryDao.upsert(
                LabelEntry(
                    network = network,
                    type = LabelType.TX.wire,
                    ref = ref,
                    label = trimmed,
                    origin = origin,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now
                )
            )
        }

    //
    // Address labels (label_entries, type "addr")
    //

    /**
     * Live label for a single address. Refs are stored verbatim (no
     * case normalization applies to addr records), input passes through.
     */
    fun observeAddrLabel(address: String): LiveData<LabelEntry?> =
        entryDao.observe(currentNetwork(), LabelType.ADDR.wire, address)

    /**
     * Sets, updates or removes (blank) an address label. Ref stored
     * verbatim, matching importer normalization rules.
     */
    suspend fun setAddrLabel(address: String, label: String) =
        setEntryLabel(LabelType.ADDR, address, label)

    private suspend fun setEntryLabel(type: LabelType, ref: String, label: String) =
        withContext(Dispatchers.IO) {
            val network = currentNetwork()
            val trimmed = label.trim()
            if (trimmed.isEmpty()) {
                entryDao.delete(network, type.wire, ref)
            } else {
                val now = System.currentTimeMillis()
                val existing = entryDao.find(network, type.wire, ref)
                entryDao.upsert(
                    LabelEntry(
                        network = network,
                        type = type.wire,
                        ref = ref,
                        label = trimmed,
                        origin = existing?.origin,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now
                    )
                )
            }
        }

    //
    // Auto UTXO labels (utxo_labels, issue #46) — mirror setAutoTxLabel
    //

    /**
     * Existing utxo label row, or null when none exists. The
     * null-vs-null-origin distinction matters to the provenance guard,
     * same as tx labels (issue #6 part 2).
     */
    suspend fun getUtxoLabel(txid: String, vout: Int): UtxoLabel? =
        withContext(Dispatchers.IO) {
            dao.find(currentNetwork(), txid.lowercase(), vout)
        }

    /**
     * Detector-originated utxo label: explicit origin, never blank,
     * never deletes, preserves createdAt. The manual-provenance guard
     * lives in the caller (the #46 writer), not here — same contract
     * as setAutoTxLabel.
     */
    suspend fun setAutoUtxoLabel(
        txid: String, vout: Int, label: String, origin: String,
    ) = withContext(Dispatchers.IO) {
        val network = currentNetwork()
        val ref = txid.lowercase()
        val trimmed = label.trim()
        check(trimmed.isNotEmpty()) { "auto label must not be blank" }
        val now = System.currentTimeMillis()
        val existing = dao.find(network, ref, vout)
        dao.upsert(
            UtxoLabel(
                network = network,
                txid = ref,
                vout = vout,
                label = trimmed,
                origin = origin,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )
    }

    /**
     * Terminator supersession (issue #46): remove OUR auto label only —
     * a manual or imported row is never touched. "A coin can stop being
     * a badbank descendant; it can never silently stay one."
     */
    suspend fun deleteAutoUtxoLabelIfOurs(
        txid: String, vout: Int, ourLabel: String,
    ) = withContext(Dispatchers.IO) {
        dao.deleteIfLabelEquals(currentNetwork(), txid.lowercase(), vout, ourLabel)
    }

    /**
     * #42: move label rows written under a wrong network to the real
     * one. Runs the LabelRescopePlanner's plan copy-then-purge inside
     * a single withTransaction - copy first so the rows are never
     * absent, then purge the distinct foreign networks. Idempotent:
     * an empty plan (all rows already on target) does nothing.
     *
     * Called after keychain derivation; the planner's golden covers
     * the semantics, this executor is carried by planner coverage +
     * device QA (no Room harness exists in the JVM suite).
     */
    suspend fun rescopeToNetwork(target: String) = withContext(Dispatchers.IO) {
        val plan = LabelRescopePlanner.plan(
            target,
            dao.getAllNetworks(),
            entryDao.getAllNetworks()
        )
        if (plan.utxoCopies.isEmpty() && plan.entryCopies.isEmpty() &&
            plan.purgeNetworks.isEmpty()
        ) return@withContext
        roomDb.withTransaction {
            if (plan.utxoCopies.isNotEmpty()) dao.upsertAll(plan.utxoCopies)
            if (plan.entryCopies.isNotEmpty()) entryDao.upsertAll(plan.entryCopies)
            plan.purgeNetworks.forEach { network ->
                if (network != target) {
                    dao.deleteAll(network)
                    entryDao.deleteAll(network)
                }
            }
        }
    }
}
