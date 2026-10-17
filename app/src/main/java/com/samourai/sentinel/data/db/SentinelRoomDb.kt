package com.samourai.sentinel.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.TxInputConverter
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.db.dao.TxDao
import com.samourai.sentinel.data.db.dao.UtxoDao
import com.samourai.sentinel.data.db.dao.UtxoLabelDao
import com.samourai.sentinel.data.db.dao.LabelEntryDao
import com.samourai.sentinel.data.db.dao.TxEntropyDao
import com.samourai.sentinel.data.db.entity.UtxoLabel
import com.samourai.sentinel.data.db.entity.LabelEntry
import com.samourai.sentinel.data.db.entity.TxEntropy
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Tx::class, Utxo::class, UtxoLabel::class, LabelEntry::class, TxEntropy::class], version = 5, exportSchema = false)
@TypeConverters(TxInputConverter::class)
abstract class SentinelRoomDb : RoomDatabase() {

    abstract fun txDao(): TxDao
    abstract fun utxoDao(): UtxoDao
    abstract fun utxoLabelDao(): UtxoLabelDao
    abstract fun labelEntryDao(): LabelEntryDao
    abstract fun txEntropyDao(): TxEntropyDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `utxo_labels` (" +
                            "`network` TEXT NOT NULL, " +
                            "`txid` TEXT NOT NULL, " +
                            "`vout` INTEGER NOT NULL, " +
                            "`label` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`network`, `txid`, `vout`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_utxo_labels_network` " +
                            "ON `utxo_labels` (`network`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `label_entries` (" +
                            "`network` TEXT NOT NULL, " +
                            "`type` TEXT NOT NULL, " +
                            "`ref` TEXT NOT NULL, " +
                            "`label` TEXT NOT NULL, " +
                            "`origin` TEXT, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`network`, `type`, `ref`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_label_entries_network` " +
                            "ON `label_entries` (`network`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_label_entries_network_type` " +
                            "ON `label_entries` (`network`, `type`)"
                )
                // v2 -> v3: additive only. Existing utxo_labels rows keep
                // NULL origin; label_entries starts empty.
                db.execSQL("ALTER TABLE `utxo_labels` ADD COLUMN `origin` TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v3 -> v4: add tx_entropy (issue #7). CREATE-only: the
                // cache is regenerable, existing tables are never touched.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tx_entropy` (" +
                            "`txid` TEXT NOT NULL, " +
                            "`nbCmbn` INTEGER NOT NULL, " +
                            "`entropyBits` REAL NOT NULL, " +
                            "`linkabilityJson` TEXT NOT NULL, " +
                            "`tooComplex` INTEGER NOT NULL, " +
                            "`computedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`txid`))"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v4 -> v5 (#72): wipe tx_entropy rows. Every v4 row
                // was computed on a per-pubkey wallet slice, not the
                // full tx (on-device: 155 of 155 rows at nbCmbn = 1,
                // zero honest numbers ever computed), and a wrong row
                // is byte-identical to a correct-by-shape 1x1 row - no
                // query can tell them apart, so a blanket wipe is the
                // only sound invalidation. The cache is regenerable
                // by design (MIGRATION_3_4's own comment): the fixed
                // ingest recomputes every row from the full tx on
                // the next sync. Schema unchanged; other tables
                // never touched.
                db.execSQL("DELETE FROM `tx_entropy`")
            }
        }

        @Volatile
        private var INSTANCE: SentinelRoomDb? = null
        fun getDatabase(context: Context): SentinelRoomDb {
            val tempInstance = INSTANCE
            if (tempInstance != null) {
                return tempInstance
            }
            synchronized(this) {
                val instance = Room.databaseBuilder(
                        context.applicationContext,
                        SentinelRoomDb::class.java,
                        "sentinel_database"
                )
                        // v1 -> v2: add utxo_labels. CREATE-only migration so
                        // existing user data (utxos, txs) is never touched.
                        // v2 -> v3: add label_entries + utxo_labels.origin.
                        // v4 -> v5: wipe tx_entropy rows (regenerable cache).
                        // Destructive fallback stays disabled on purpose.
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                        .build()
                INSTANCE = instance
                return instance
            }
        }
    }
}
