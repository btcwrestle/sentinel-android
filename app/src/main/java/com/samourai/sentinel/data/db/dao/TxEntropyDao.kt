package com.samourai.sentinel.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.samourai.sentinel.data.db.entity.TxEntropy

@Dao
interface TxEntropyDao {

    @Query("SELECT * FROM tx_entropy WHERE txid = :txid")
    suspend fun findByTxid(txid: String): TxEntropy?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TxEntropy)
}
