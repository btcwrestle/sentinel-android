package com.samourai.sentinel.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.db.dao.LabelEntryDao
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
}
