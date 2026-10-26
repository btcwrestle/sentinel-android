package com.samourai.sentinel.data.db

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

internal object SqlCipherLibraries {
    @Volatile
    private var loaded = false

    fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("sqlcipher")
            loaded = true
        }
    }
}

internal object SqlCipherOpener : DatabaseOpener {
    override fun open(path: String, rawKey: ByteArray?): SqlSession {
        SqlCipherLibraries.load()
        val password = if (rawKey == null) {
            ByteArray(0)
        } else {
            DatabasePassphrase.utf8(rawKey)
        }
        val flags = SQLiteDatabase.OPEN_READWRITE or
            SQLiteDatabase.CREATE_IF_NECESSARY or
            SQLiteDatabase.NO_LOCALIZED_COLLATORS
        // Never delete the file from this opener. A bad page during the
        // plaintext copy must leave the caller's database where it was.
        val db = SQLiteDatabase.openDatabase(
            path,
            password,
            null,
            flags,
            { _, _ -> },
            null
        )
        return ZeteticSqlSession(db)
    }
}

internal class EncryptedRoomFactory(
    private val passphrase: ByteArray,
    private val dbFile: File,
    private val rawKey: ByteArray
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        SqlCipherLibraries.load()
        val delegate = SupportOpenHelperFactory(passphrase).create(configuration)
        return EncryptedOpenHelper(delegate, dbFile, rawKey)
    }
}

internal class EncryptedOpenHelper(
    private val delegate: SupportSQLiteOpenHelper,
    private val dbFile: File,
    private val rawKey: ByteArray
) : SupportSQLiteOpenHelper {
    private val lock = Any()

    @Volatile
    private var migrated = false

    override val databaseName: String?
        get() = delegate.databaseName

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        delegate.setWriteAheadLoggingEnabled(enabled)
    }

    override val writableDatabase: androidx.sqlite.db.SupportSQLiteDatabase
        get() = open { delegate.writableDatabase }

    override val readableDatabase: androidx.sqlite.db.SupportSQLiteDatabase
        get() = open { delegate.readableDatabase }

    override fun close() {
        delegate.close()
    }

    private fun open(
        block: () -> androidx.sqlite.db.SupportSQLiteDatabase
    ): androidx.sqlite.db.SupportSQLiteDatabase {
        synchronized(lock) {
            if (!migrated) {
                if (dbFile.exists() && dbFile.length() == 0L) {
                    dbFile.delete()
                }
                DatabaseMigrator.migrateIfNeeded(dbFile, rawKey, SqlCipherOpener)
                migrated = true
            }
        }
        try {
            val db = block()
            PlaintextSqlite.shredPlaintextResidue(dbFile)
            return db
        } catch (e: DatabaseKeyException) {
            throw e
        } catch (e: Exception) {
            if (looksLikeKeyFailure(e)) throw DatabaseKeyException(cause = e)
            throw e
        }
    }

    private fun looksLikeKeyFailure(error: Throwable): Boolean {
        var cur: Throwable? = error
        while (cur != null) {
            val msg = cur.message?.lowercase() ?: ""
            if ("not a database" in msg || "is encrypted" in msg || "hmac check failed" in msg) {
                return true
            }
            cur = cur.cause
        }
        return false
    }
}

private class ZeteticSqlSession(private val db: SQLiteDatabase) : SqlSession {
    override fun exec(sql: String, args: List<SqlValue>) {
        if (args.isEmpty()) {
            db.execSQL(sql)
        } else {
            val binds = arrayOfNulls<Any>(args.size)
            args.forEachIndexed { i, value -> binds[i] = value.toBindArg() }
            db.execSQL(sql, binds)
        }
    }

    override fun queryEach(sql: String, consumer: (SqlRow) -> Unit) {
        db.rawQuery(sql).use { cursor ->
            while (cursor.moveToNext()) {
                consumer(SqlRow(read(cursor)))
            }
        }
    }

    override fun close() {
        db.close()
    }

    private fun read(cursor: Cursor): List<SqlValue> {
        val out = ArrayList<SqlValue>(cursor.columnCount)
        for (i in 0 until cursor.columnCount) {
            out.add(
                when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> SqlValue.Null
                    Cursor.FIELD_TYPE_INTEGER -> SqlValue.Integer(cursor.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> SqlValue.Real(cursor.getDouble(i))
                    Cursor.FIELD_TYPE_BLOB -> SqlValue.Blob(cursor.getBlob(i) ?: ByteArray(0))
                    else -> SqlValue.Text(cursor.getString(i) ?: "")
                }
            )
        }
        return out
    }
}

internal class SupportSqlSession(private val db: SupportSQLiteDatabase) : SqlSession {
    override fun exec(sql: String, args: List<SqlValue>) {
        if (args.isEmpty()) {
            db.execSQL(sql)
        } else {
            val binds = arrayOfNulls<Any>(args.size)
            args.forEachIndexed { i, value -> binds[i] = value.toBindArg() }
            db.execSQL(sql, binds)
        }
    }

    override fun queryEach(sql: String, consumer: (SqlRow) -> Unit) {
        db.query(sql).use { cursor ->
            while (cursor.moveToNext()) {
                val values = ArrayList<SqlValue>(cursor.columnCount)
                for (i in 0 until cursor.columnCount) {
                    values.add(
                        when (cursor.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> SqlValue.Null
                            Cursor.FIELD_TYPE_INTEGER -> SqlValue.Integer(cursor.getLong(i))
                            Cursor.FIELD_TYPE_FLOAT -> SqlValue.Real(cursor.getDouble(i))
                            Cursor.FIELD_TYPE_BLOB -> SqlValue.Blob(cursor.getBlob(i) ?: ByteArray(0))
                            else -> SqlValue.Text(cursor.getString(i) ?: "")
                        }
                    )
                }
                consumer(SqlRow(values))
            }
        }
    }

    override fun close() {
        // The Room connection stays open for the process.
    }
}
