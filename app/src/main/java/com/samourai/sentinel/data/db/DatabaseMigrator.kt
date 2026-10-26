package com.samourai.sentinel.data.db

import java.io.File
import java.io.IOException

/**
 * Copies a plaintext SQLite file into a SQLCipher database with the
 * same schema, rows, and user_version, then replaces the original.
 * A failed attempt leaves the plaintext file in place.
 */
internal object DatabaseMigrator {
    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val lock = Any()

    fun migrateIfNeeded(
        dbFile: File,
        rawKey: ByteArray,
        opener: DatabaseOpener,
        checkpoint: (File) -> Unit = { PlaintextSqlite.checkpoint(it) }
    ) {
        synchronized(lock) {
            if (rawKey.size != DatabasePassphrase.RAW_BYTES) throw DatabaseKeyException()
            recoverInterrupted(dbFile)
            if (!dbFile.exists() || !PlaintextSqlite.isPlaintext(dbFile)) return
            checkpoint(dbFile)
            PlaintextSqlite.shredSidecars(dbFile)
            val parent = dbFile.parentFile ?: throw IOException("Could not update the local database.")
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Could not update the local database.")
            }
            val tmp = File(parent, dbFile.name + ".encrypting")
            PlaintextSqlite.shred(tmp)
            PlaintextSqlite.shredSidecars(tmp)
            var plain: SqlSession? = null
            var encrypted: SqlSession? = null
            try {
                plain = opener.open(dbFile.absolutePath, null)
                try {
                    plain.exec("PRAGMA query_only = ON")
                } catch (_: Exception) {
                }
                encrypted = opener.open(tmp.absolutePath, rawKey)
                copy(plain, encrypted)
                var version = 0
                plain.queryEach("PRAGMA user_version") { row -> version = row.long(0).toInt() }
                if (version !in 0..1_000_000) throw IOException("Could not update the local database.")
                encrypted.exec("PRAGMA user_version = $version")
                encrypted.queryEach("PRAGMA wal_checkpoint(TRUNCATE)") { }
            } catch (e: Exception) {
                closeQuietly(encrypted)
                closeQuietly(plain)
                encrypted = null
                plain = null
                PlaintextSqlite.shred(tmp)
                PlaintextSqlite.shredSidecars(tmp)
                throw e
            } finally {
                closeQuietly(encrypted)
                closeQuietly(plain)
            }
            // The copy connection can recreate -wal/-shm beside the plaintext
            // name. Those names are reused by the encrypted file, so they have
            // to be gone before that rename.
            PlaintextSqlite.shredSidecars(tmp)
            PlaintextSqlite.shredSidecars(dbFile)
            replace(dbFile, tmp, rawKey, opener)
        }
    }

    fun openEncrypted(opener: DatabaseOpener, path: String, rawKey: ByteArray?): SqlSession {
        if (rawKey == null || rawKey.size != DatabasePassphrase.RAW_BYTES) {
            throw DatabaseKeyException()
        }
        val session = try {
            opener.open(path, rawKey)
        } catch (e: DatabaseKeyException) {
            throw e
        } catch (e: Exception) {
            throw DatabaseKeyException(cause = scrub(rawKey, e))
        }
        try {
            session.queryEach("SELECT count(*) FROM sqlite_master") { }
        } catch (e: Exception) {
            closeQuietly(session)
            if (e is DatabaseKeyException) throw e
            throw DatabaseKeyException(cause = scrub(rawKey, e))
        }
        return session
    }

    fun userTableNames(session: SqlSession): List<String> {
        val names = mutableListOf<String>()
        session.queryEach(
            "SELECT name FROM sqlite_master " +
                "WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'android_%' " +
                "AND name NOT LIKE 'room_%' " +
                "ORDER BY name"
        ) { row ->
            names.add(row.text(0))
        }
        return names
    }

    fun deleteFrom(session: SqlSession, tables: List<String>) {
        for (name in tables) {
            session.exec("DELETE FROM ${quote(name)}")
        }
    }

    fun compact(session: SqlSession) {
        session.queryEach("PRAGMA wal_checkpoint(TRUNCATE)") { }
        session.exec("VACUUM")
    }

    private fun copy(src: SqlSession, dst: SqlSession) {
        dst.exec("PRAGMA foreign_keys = OFF")
        dropObjects(dst)
        val objects = schema(src)
        val tables = objects.filter { it.type == "table" }
        for (table in tables) {
            dst.exec(table.sql)
        }
        for (table in tables) {
            var insertSql: String? = null
            src.queryEach("SELECT * FROM ${quote(table.name)}") { row ->
                val sql = insertSql ?: run {
                    val placeholders = Array(row.values.size) { "?" }.joinToString(",")
                    "INSERT INTO ${quote(table.name)} VALUES ($placeholders)".also { insertSql = it }
                }
                dst.exec(sql, row.values)
            }
        }
        for (obj in objects) {
            if (obj.type != "table") dst.exec(obj.sql)
        }
    }

    private data class SchemaObject(val type: String, val name: String, val sql: String)

    private fun schema(session: SqlSession): List<SchemaObject> {
        val out = mutableListOf<SchemaObject>()
        session.queryEach(
            "SELECT type, name, sql FROM sqlite_master " +
                "WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' " +
                "ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'index' THEN 1 " +
                "WHEN 'trigger' THEN 2 WHEN 'view' THEN 3 ELSE 4 END, name"
        ) { row ->
            val sql = row.text(2).trim().trimEnd(';').trim()
            if (sql.isNotEmpty()) out.add(SchemaObject(row.text(0), row.text(1), sql))
        }
        return out
    }

    private fun dropObjects(session: SqlSession) {
        val objects = mutableListOf<Pair<String, String>>()
        session.queryEach(
            "SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"
        ) { row ->
            objects.add(row.text(0) to row.text(1))
        }
        for ((type, name) in objects) {
            when (type) {
                "view" -> session.exec("DROP VIEW IF EXISTS ${quote(name)}")
                "trigger" -> session.exec("DROP TRIGGER IF EXISTS ${quote(name)}")
                "index" -> if (!name.startsWith("sqlite_autoindex")) {
                    session.exec("DROP INDEX IF EXISTS ${quote(name)}")
                }
            }
        }
        for ((type, name) in objects) {
            if (type == "table") session.exec("DROP TABLE IF EXISTS ${quote(name)}")
        }
    }

    private fun replace(dbFile: File, tmp: File, rawKey: ByteArray, opener: DatabaseOpener) {
        val parent = dbFile.parentFile ?: throw IOException("Could not update the local database.")
        val backup = File(parent, dbFile.name + ".plaintext")
        if (backup.exists()) {
            val stale = File(parent, dbFile.name + ".plaintext-old")
            PlaintextSqlite.shred(stale)
            PlaintextSqlite.shredSidecars(stale)
            if (!backup.renameTo(stale)) {
                PlaintextSqlite.shred(backup)
                PlaintextSqlite.shredSidecars(backup)
            } else {
                PlaintextSqlite.shred(stale)
                PlaintextSqlite.shredSidecars(stale)
            }
        }
        if (!dbFile.renameTo(backup)) {
            throw IOException("Could not update the local database.")
        }
        PlaintextSqlite.shredSidecars(dbFile)
        if (!tmp.renameTo(dbFile)) {
            if (!dbFile.exists()) backup.renameTo(dbFile)
            throw IOException("Could not update the local database.")
        }
        try {
            openEncrypted(opener, dbFile.absolutePath, rawKey).close()
        } catch (e: Exception) {
            val bad = File(parent, dbFile.name + ".encrypting-failed")
            PlaintextSqlite.shred(bad)
            PlaintextSqlite.shredSidecars(bad)
            if (dbFile.exists() && !dbFile.renameTo(bad)) {
                PlaintextSqlite.shred(dbFile)
            }
            PlaintextSqlite.shred(bad)
            PlaintextSqlite.shredSidecars(bad)
            if (!dbFile.exists() && backup.exists()) {
                backup.renameTo(dbFile)
            }
            throw e
        }
        PlaintextSqlite.shred(backup)
        PlaintextSqlite.shredSidecars(backup)
        PlaintextSqlite.shredPlaintextResidue(dbFile)
    }

    private fun recoverInterrupted(dbFile: File) {
        if (dbFile.exists()) return
        val parent = dbFile.parentFile ?: return
        val backup = File(parent, dbFile.name + ".plaintext")
        val tmp = File(parent, dbFile.name + ".encrypting")
        PlaintextSqlite.shred(tmp)
        PlaintextSqlite.shredSidecars(tmp)
        if (!backup.exists()) return
        if (!backup.renameTo(dbFile)) {
            throw IOException("Could not update the local database.")
        }
    }

    internal fun quote(name: String): String {
        if (!IDENT.matches(name)) throw IOException("Could not update the local database.")
        return "`$name`"
    }

    private fun scrub(rawKey: ByteArray, error: Exception): Exception {
        val secret = try {
            DatabasePassphrase.hex(rawKey)
        } catch (_: Exception) {
            return error
        }
        if (secret.isEmpty()) return error
        return if (mentions(error, secret)) Exception("Could not open the local database.") else error
    }

    private fun mentions(error: Throwable?, secret: String): Boolean {
        var cur = error
        while (cur != null) {
            if (cur.message?.contains(secret) == true) return true
            cur = cur.cause
        }
        return false
    }

    private fun closeQuietly(session: SqlSession?) {
        try {
            session?.close()
        } catch (_: Exception) {
        }
    }
}
