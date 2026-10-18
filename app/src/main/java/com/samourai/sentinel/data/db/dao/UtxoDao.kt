package com.samourai.sentinel.data.db.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.Utxo

/**
 * sentinel-android
 *
 */
@Dao
interface UtxoDao {

    @Query("SELECT * from utxos WHERE idx=:idx")
    fun getUTXObyIdx(idx: String): LiveData<List<Utxo>>

    @Query("SELECT * from utxos WHERE collectionId=:collectionId")
    fun getUTXObyCollection(collectionId: String): LiveData<List<Utxo>>

    @Query("SELECT * from utxos WHERE collectionId=:collectionId")
    fun getUTXObyCollectionAsList(collectionId: String):  List<Utxo>

    @Query("SELECT * from utxos WHERE collectionId=:collectionId AND pubKey=:pubKey")
    fun getUTXObyCollectionAndPubKey(collectionId: String,pubKey: String): LiveData<List<Utxo>>

    @Query("SELECT * from utxos WHERE collectionId=:collectionId AND pubKey IN (:pubKeys)")
    fun getUTXObyCollectionAndPubKeys(collectionId: String, pubKeys: List<String>): LiveData<List<Utxo>>

    @Query("SELECT * from utxos WHERE pubKey=:pubKey")
    fun getUtxoWithPubKey(pubKey: String): LiveData<List<Utxo>>
    @Query("SELECT * from utxos WHERE pubKey IN (:pubKeys)")
    fun getUTXObyPubKeys(pubKeys: List<String>): LiveData<List<Utxo>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(utxo: Utxo)

    /**
     * #202: batch upsert for a sync round - the UtxoDao port of
     * TxDao.insertAll (#201). REPLACE (not IGNORE) so re-fetched
     * rows refresh in place: confirmations/value update on
     * unchanged outpoints.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(utxos: List<Utxo>)

    @Query("DELETE FROM utxos")
    suspend fun delete()

    @Query("DELETE FROM utxos WHERE txHash=:txHash AND txOutputN=:outputN")
    suspend fun delete(txHash: String,outputN:Int)

    @Query("DELETE FROM utxos WHERE pubKey=:pubKey")
    suspend fun deleteByPubKey(pubKey: String)

    @Query("DELETE FROM utxos WHERE collectionId=:collectionId")
    suspend fun deleteByCollection(collectionId: String)

    /**
     * #202: prune only rows that vanished from the fetched set,
     * scoped to the syncing collection. The NOT IN list is the idx
     * set ("txHash:outputN"), so spent outpoints disappear while
     * rows of other collections are never touched.
     */
    @Query("DELETE FROM utxos WHERE collectionId=:collectionId AND idx NOT IN (:idxs)")
    suspend fun deleteByCollectionNotIn(collectionId: String, idxs: List<String>)

    /**
     * #202: one transaction, one commit, ONE LiveData emission per
     * sync round - the exact mirror of TxDao.upsertCollectionTx
     * (#201). Replaces the caller-side pre-wipe + per-row insert
     * loop, which emitted an empty list plus one partial list per
     * row (the utxo flash - the tx-side twin of #201). Empty set =
     * empty wallet: a wipe scoped to this collection is then
     * correct.
     */
    @Transaction
    suspend fun upsertCollectionUtxos(collectionId: String, utxos: List<Utxo>) {
        if (utxos.isEmpty()) {
            deleteByCollection(collectionId)
        } else {
            insertAll(utxos)
            deleteByCollectionNotIn(collectionId, utxos.map { it.idx })
        }
    }
}
