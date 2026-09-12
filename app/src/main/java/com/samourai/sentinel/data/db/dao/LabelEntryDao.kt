package com.samourai.sentinel.data.db.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.samourai.sentinel.data.db.entity.LabelEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface LabelEntryDao {

    @Query("SELECT * FROM label_entries WHERE network=:network AND type=:type AND ref=:ref")
    suspend fun find(network: String, type: String, ref: String): LabelEntry?

    @Query("SELECT * FROM label_entries WHERE network=:network AND type=:type AND ref=:ref")
    fun observe(network: String, type: String, ref: String): LiveData<LabelEntry?>

    @Query("SELECT * FROM label_entries WHERE network=:network AND type=:type")
    fun observeAllForType(network: String, type: String): LiveData<List<LabelEntry>>

    @Query("SELECT * FROM label_entries WHERE network=:network")
    suspend fun getAll(network: String): List<LabelEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LabelEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<LabelEntry>)

    @Query("DELETE FROM label_entries WHERE network=:network AND type=:type AND ref=:ref")
    suspend fun delete(network: String, type: String, ref: String)

    @Query("SELECT * FROM label_entries WHERE network=:network")
    fun observeAllFlow(network: String): Flow<List<LabelEntry>>
}
