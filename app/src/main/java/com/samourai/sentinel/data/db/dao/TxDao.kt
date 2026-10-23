package com.samourai.sentinel.data.db.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.samourai.sentinel.data.Tx

/**
 * sentinel-android
 *
 */
const val TX_PAGE_SIZE = 200;
@Dao
interface TxDao {

    @Query("SELECT * from transactions WHERE collectionId=:collectionID  ORDER BY time ASC LIMIT $TX_PAGE_SIZE")
    fun getAllTx(collectionID: String): LiveData<List<Tx>>


    @Query("SELECT * from transactions WHERE collectionId=:collectionID  AND associatedPubKey=:associatedPubKey ORDER BY time DESC ,confirmations ASC ")
    fun observeTxForPubKey(collectionID: String, associatedPubKey: String): LiveData<List<Tx>>

    @Query("SELECT * FROM transactions WHERE collectionId=:collectionID AND associatedPubKey IN (:pubKeys)  ORDER BY time DESC ,confirmations ASC ")
    fun observeTxForPubKeys(collectionID: String, pubKeys: List<String>): LiveData<List<Tx>>

    @Query("SELECT * from transactions WHERE collectionId=:collectionID AND associatedPubKey=:pubKey  ORDER BY time DESC LIMIT $TX_PAGE_SIZE")
    fun getAssociated(collectionID: String, pubKey: String): List<Tx>

    @Query("SELECT * from transactions WHERE associatedPubKey=:pubKey  ORDER BY time DESC LIMIT $TX_PAGE_SIZE")
    fun getAssociated(pubKey: String): List<Tx>

    /**
     * #284: keychain "All" tab. Room LiveData, not a Paging DataSource.
     * The PagedList was built once per tab and could stay on its initial
     * empty snapshot for the life of the screen (opened in the
     * balance-before-saveTx window, or a first load that raced the sync
     * commit). Leaving and re-entering built a new snapshot, so the rows
     * appeared. Same invalidation path as [getAllTx], which #201 pins.
     * DESC here (newest first); [getAllTx] stays ASC for its caller.
     */
    @Query("SELECT * from transactions WHERE collectionId=:collectionID  ORDER BY time DESC LIMIT $TX_PAGE_SIZE")
    fun observeCollectionTx(collectionID: String): LiveData<List<Tx>>

    /**
     * #201: batch upsert for a sync round. REPLACE (not IGNORE) so
     * re-fetched rows refresh in place: confirmations/time update on
     * unchanged hashes. The old per-row IGNORE insert only worked
     * because the caller wiped the table first.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(txs: List<Tx>)

    @Query("DELETE FROM transactions")
    suspend fun delete()

    @Query("DELETE FROM transactions WHERE collectionId=:collectionID AND associatedPubKey=:pubKey")
    fun deleteByCollectionAndPubkey(collectionID: String, pubKey: String)

    @Query("DELETE FROM transactions WHERE collectionId=:collectionID")
    suspend fun deleteByCollectionID(collectionID: String)

    /**
     * #201: prune only rows that vanished from the fetched set, scoped
     * to the syncing collection. The NOT IN list is the post-mangle
     * hash set (hash-1, hash-2, ...), so when the pubkey set changes
     * between syncs the stale suffix variants are pruned; rows of
     * other collections are never touched.
     */
    @Query("DELETE FROM transactions WHERE collectionId=:collectionID AND hash NOT IN (:hashes)")
    suspend fun deleteByCollectionIDNotIn(collectionID: String, hashes: List<String>)

    /**
     * #201: one transaction, one commit, ONE LiveData emission per
     * sync round - the saveUtxos reconcile pattern applied to txs.
     * Replaces the wipe + per-row insert loop, which emitted an empty
     * list plus one partial list per row (~106 emissions for a
     * 105-tx collection: the empty one IS the flash, the re-query
     * storm IS the tab-switch freeze). Empty set = empty wallet:
     * a full wipe is then correct.
     */
    @Transaction
    suspend fun upsertCollectionTx(collectionID: String, txs: List<Tx>) {
        if (txs.isEmpty()) {
            deleteByCollectionID(collectionID)
        } else {
            insertAll(txs)
            deleteByCollectionIDNotIn(collectionID, txs.map { it.hash })
        }
    }

    @Query("UPDATE transactions SET confirmations=:confirmations where hash=:txHash")
    fun updateTxConfirmations(txHash: String, confirmations: Long)
}
