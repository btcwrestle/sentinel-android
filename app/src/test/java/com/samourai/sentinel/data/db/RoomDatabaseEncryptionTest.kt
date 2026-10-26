package com.samourai.sentinel.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.db.entity.LabelEntry
import com.samourai.sentinel.data.db.entity.TxEntropy
import com.samourai.sentinel.data.db.entity.UtxoLabel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.sqlite.mc.SQLiteMCSqlCipherConfig
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.util.UUID
import java.util.concurrent.Executor

/**
 * Opens a real Room file, copies it through [DatabaseMigrator] into a
 * SQLCipher database, and checks the rows survive. The cipher engine
 * is SQLCipher v4 (PBKDF2-HMAC-SHA512, 256000 iterations), the same
 * parameters the on-device library uses. The device opener is the
 * SQLCipher Android binding; this test supplies the JVM binding of
 * that engine so the copy, key check, and erase statements actually run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomDatabaseEncryptionTest {

    private val direct = Executor { it.run() }
    private lateinit var context: Context
    private lateinit var dbFile: File
    private var room: SentinelRoomDb? = null
    private val opener = JdbcDatabaseOpener()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val name = "atrest_" + UUID.randomUUID().toString().replace("-", "")
        dbFile = context.getDatabasePath(name)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()
    }

    @After
    fun tearDown() {
        try {
            room?.close()
        } catch (_: Exception) {
        }
        val parent = dbFile.parentFile ?: return
        parent.listFiles()?.forEach { file ->
            if (file.name.startsWith(dbFile.name)) file.delete()
        }
    }

    @Test
    fun plaintextRoomDatabaseMigratesAndOpensWithTheKey() = runBlocking {
        val key = DatabasePassphrase.random()
        seedRoom()
        assertTrue(PlaintextSqlite.isPlaintext(dbFile))

        DatabaseMigrator.migrateIfNeeded(dbFile, key, opener)

        assertFalse(PlaintextSqlite.isPlaintext(dbFile))
        assertFalse(File(dbFile.path + ".plaintext").exists())
        assertFalse(File(dbFile.path + ".encrypting").exists())
        assertFalse(File(dbFile.path + "-wal").exists())
        val encrypted = dbFile.readBytes()
        DatabaseMigrator.migrateIfNeeded(dbFile, key, opener)
        assertArrayEquals(encrypted, dbFile.readBytes())

        DatabaseMigrator.openEncrypted(opener, dbFile.absolutePath, key).use { session ->
            assertEquals("l1'xpub", singleText(session, "SELECT associatedPubKey FROM transactions"))
            assertEquals("utxo'pub", singleText(session, "SELECT pubKey FROM utxos"))
            assertEquals("it's a label", singleText(session, "SELECT label FROM utxo_labels"))
            assertEquals("addr label", singleText(session, "SELECT label FROM label_entries"))
            assertEquals(1, singleLong(session, "SELECT count(*) FROM tx_entropy WHERE txid = 'ab'"))
            assertEquals(1, singleLong(session, "SELECT count(*) FROM room_master_table"))
            assertEquals(5, singleLong(session, "PRAGMA user_version").toInt())
        }
    }

    @Test
    fun wrongKeyFailsCleanlyAndLeavesRowsIntact() = runBlocking {
        val key = DatabasePassphrase.random()
        val wrong = DatabasePassphrase.random()
        seedRoom()
        DatabaseMigrator.migrateIfNeeded(dbFile, key, opener)
        val before = dbFile.readBytes()

        val missing = assertThrows(DatabaseKeyException::class.java) {
            DatabaseMigrator.openEncrypted(opener, dbFile.absolutePath, null)
        }
        val empty = assertThrows(DatabaseKeyException::class.java) {
            DatabaseMigrator.openEncrypted(opener, dbFile.absolutePath, ByteArray(0))
        }
        val rejected = assertThrows(DatabaseKeyException::class.java) {
            DatabaseMigrator.openEncrypted(opener, dbFile.absolutePath, wrong).close()
        }
        for (error in listOf(missing, empty, rejected)) {
            assertEquals("Could not open the local database.", error.message)
            assertFalse(error.toString().contains(DatabasePassphrase.hex(key)))
            assertFalse(error.toString().contains(DatabasePassphrase.hex(wrong)))
        }
        assertArrayEquals(before, dbFile.readBytes())
        DatabaseMigrator.openEncrypted(opener, dbFile.absolutePath, key).use { session ->
            assertEquals("l1'xpub", singleText(session, "SELECT associatedPubKey FROM transactions"))
        }
    }

    @Test
    fun eraseRemovesRowsAndLeavesTheFileEncrypted() = runBlocking {
        val key = DatabasePassphrase.random()
        seedRoom()
        DatabaseMigrator.migrateIfNeeded(dbFile, key, opener)
        val residue = File(dbFile.path + ".plaintext")
        residue.writeBytes("xpub-left-behind".toByteArray())

        DatabaseMigrator.openEncrypted(opener, dbFile.absolutePath, key).use { session ->
            val tables = DatabaseMigrator.userTableNames(session)
            assertTrue(tables.contains("transactions"))
            assertTrue(tables.contains("utxos"))
            assertTrue(tables.contains("utxo_labels"))
            assertTrue(tables.contains("label_entries"))
            assertTrue(tables.contains("tx_entropy"))
            assertFalse(tables.contains("room_master_table"))
            DatabaseMigrator.deleteFrom(session, tables)
            DatabaseMigrator.compact(session)
        }
        PlaintextSqlite.shredPlaintextResidue(dbFile)

        assertFalse(residue.exists())
        assertFalse(PlaintextSqlite.isPlaintext(dbFile))
        DatabaseMigrator.openEncrypted(opener, dbFile.absolutePath, key).use { session ->
            assertEquals(0, singleLong(session, "SELECT count(*) FROM transactions"))
            assertEquals(0, singleLong(session, "SELECT count(*) FROM utxos"))
            assertEquals(0, singleLong(session, "SELECT count(*) FROM utxo_labels"))
            assertEquals(0, singleLong(session, "SELECT count(*) FROM label_entries"))
            assertEquals(0, singleLong(session, "SELECT count(*) FROM tx_entropy"))
            assertEquals(1, singleLong(session, "SELECT count(*) FROM room_master_table"))
            assertEquals(5, singleLong(session, "PRAGMA user_version").toInt())
        }
    }

    @Test
    fun failedMigrationKeepsThePlaintextFile() {
        val key = DatabasePassphrase.random()
        opener.open(dbFile.absolutePath, null).use { session ->
            session.exec("CREATE TABLE transactions (hash TEXT PRIMARY KEY, associatedPubKey TEXT)")
            session.exec(
                "INSERT INTO transactions VALUES (?, ?)",
                listOf(SqlValue.Text("h1"), SqlValue.Text("xpub-kept"))
            )
        }
        val failing = object : DatabaseOpener {
            override fun open(path: String, rawKey: ByteArray?): SqlSession {
                if (rawKey != null) throw java.io.IOException("boom")
                return opener.open(path, rawKey)
            }
        }
        try {
            DatabaseMigrator.migrateIfNeeded(dbFile, key, failing)
            fail("expected the copy to fail")
        } catch (_: Exception) {
        }
        assertTrue(PlaintextSqlite.isPlaintext(dbFile))
        assertFalse(File(dbFile.path + ".encrypting").exists())
        opener.open(dbFile.absolutePath, null).use { session ->
            assertEquals("xpub-kept", singleText(session, "SELECT associatedPubKey FROM transactions"))
        }
    }

    @Test
    fun eraseStoredRowsClearsEveryUserTable() = runBlocking {
        val db = Room.databaseBuilder(context, SentinelRoomDb::class.java, dbFile.name)
            .allowMainThreadQueries()
            .setQueryExecutor(direct)
            .setTransactionExecutor(direct)
            .build()
        room = db
        db.txDao().insertAll(
            listOf(
                Tx(
                    hash = "ab",
                    time = 1L,
                    version = 1,
                    locktime = 0,
                    result = 1L,
                    inputs = emptyList(),
                    out = emptyList(),
                    block_height = 1L,
                    confirmations = 1,
                    associatedPubKey = "xpub-erase",
                    collectionId = "c1"
                )
            )
        )
        db.utxoDao().insert(Utxo(idx = "i", pubKey = "pk", value = 1L, collectionId = "c1"))
        db.utxoLabelDao().upsert(
            UtxoLabel("mainnet", "ab", 0, "lbl", null, 1L, 1L)
        )
        db.labelEntryDao().upsert(
            LabelEntry("mainnet", "tx", "ab", "lbl", null, 1L, 1L)
        )
        db.txEntropyDao().insert(TxEntropy("ab", 1, 0.0, "[]", false, 1L))

        db.eraseStoredRows()

        assertEquals(0, db.txDao().getAssociated("xpub-erase").size)
        assertEquals(0, db.utxoDao().getUTXObyCollectionAsList("c1").size)
        assertEquals(0, db.utxoLabelDao().getAllNetworks().size)
        assertEquals(0, db.labelEntryDao().getAllNetworks().size)
        assertEquals(null, db.txEntropyDao().findByTxid("ab"))
        val session = SupportSqlSession(db.openHelper.writableDatabase)
        assertEquals(5, singleLong(session, "PRAGMA user_version").toInt())
        assertEquals(1, singleLong(session, "SELECT count(*) FROM room_master_table"))
    }

    @Test
    fun sidecarLeftByThePlaintextCopyIsGoneBeforeTheEncryptedFileTakesItsName() {
        val key = DatabasePassphrase.random()
        opener.open(dbFile.absolutePath, null).use { session ->
            session.exec("CREATE TABLE transactions (hash TEXT PRIMARY KEY, associatedPubKey TEXT)")
            session.exec(
                "INSERT INTO transactions VALUES (?, ?)",
                listOf(SqlValue.Text("h1"), SqlValue.Text("xpub-wal"))
            )
            session.exec("PRAGMA user_version = 5")
        }
        val planting = object : DatabaseOpener {
            override fun open(path: String, rawKey: ByteArray?): SqlSession {
                val session = opener.open(path, rawKey)
                if (rawKey != null) return session
                return object : SqlSession by session {
                    override fun close() {
                        session.close()
                        File(path + "-wal").writeBytes("plaintext-wal".toByteArray())
                        File(path + "-shm").writeBytes(byteArrayOf(1))
                        File(path + "-journal").writeBytes("journal".toByteArray())
                    }
                }
            }
        }

        DatabaseMigrator.migrateIfNeeded(dbFile, key, planting)

        assertFalse(File(dbFile.path + "-wal").exists())
        assertFalse(File(dbFile.path + "-shm").exists())
        assertFalse(File(dbFile.path + "-journal").exists())
        assertFalse(File(dbFile.path + ".plaintext").exists())
        assertFalse(PlaintextSqlite.isPlaintext(dbFile))
        DatabaseMigrator.openEncrypted(opener, dbFile.absolutePath, key).use { session ->
            assertEquals("xpub-wal", singleText(session, "SELECT associatedPubKey FROM transactions"))
            assertEquals(5, singleLong(session, "PRAGMA user_version").toInt())
        }
    }

    @Test
    fun failedProbeRestoresThePlaintextFile() {
        val key = DatabasePassphrase.random()
        opener.open(dbFile.absolutePath, null).use { session ->
            session.exec("CREATE TABLE transactions (hash TEXT PRIMARY KEY, associatedPubKey TEXT)")
            session.exec(
                "INSERT INTO transactions VALUES (?, ?)",
                listOf(SqlValue.Text("h1"), SqlValue.Text("xpub-kept"))
            )
        }
        var encryptedOpens = 0
        val failingProbe = object : DatabaseOpener {
            override fun open(path: String, rawKey: ByteArray?): SqlSession {
                if (rawKey != null) {
                    encryptedOpens += 1
                    if (encryptedOpens > 1) throw java.io.IOException("probe")
                }
                return opener.open(path, rawKey)
            }
        }
        try {
            DatabaseMigrator.migrateIfNeeded(dbFile, key, failingProbe)
            fail("expected the probe to fail")
        } catch (_: Exception) {
        }
        assertTrue(PlaintextSqlite.isPlaintext(dbFile))
        assertFalse(File(dbFile.path + ".encrypting").exists())
        assertFalse(File(dbFile.path + ".encrypting-failed").exists())
        assertFalse(File(dbFile.path + ".plaintext").exists())
        opener.open(dbFile.absolutePath, null).use { session ->
            assertEquals("xpub-kept", singleText(session, "SELECT associatedPubKey FROM transactions"))
        }
    }

    @Test
    fun interruptedRenameIsRetriedWithoutLosingTheRow() {
        val key = DatabasePassphrase.random()
        JdbcDatabaseOpener().open(dbFile.absolutePath, null).use { session ->
            session.exec("CREATE TABLE transactions (hash TEXT PRIMARY KEY, associatedPubKey TEXT)")
            session.exec(
                "INSERT INTO transactions VALUES (?, ?)",
                listOf(SqlValue.Text("h1"), SqlValue.Text("xpub-recovered"))
            )
            session.exec("PRAGMA user_version = 5")
        }
        val backup = File(dbFile.path + ".plaintext")
        assertTrue(dbFile.renameTo(backup))
        assertFalse(dbFile.exists())

        DatabaseMigrator.migrateIfNeeded(dbFile, key, opener)

        assertTrue(dbFile.exists())
        assertFalse(backup.exists())
        assertFalse(PlaintextSqlite.isPlaintext(dbFile))
        DatabaseMigrator.openEncrypted(opener, dbFile.absolutePath, key).use { session ->
            assertEquals("xpub-recovered", singleText(session, "SELECT associatedPubKey FROM transactions"))
            assertEquals(5, singleLong(session, "PRAGMA user_version").toInt())
        }
    }

    private fun seedRoom() {
        room = Room.databaseBuilder(context, SentinelRoomDb::class.java, dbFile.name)
            .allowMainThreadQueries()
            .setQueryExecutor(direct)
            .setTransactionExecutor(direct)
            .build()
        val db = room!!
        runBlocking {
            db.txDao().insertAll(
                listOf(
                    Tx(
                        hash = "ab",
                        time = 1000L,
                        version = 1,
                        locktime = 0,
                        result = 100L,
                        inputs = emptyList(),
                        out = emptyList(),
                        block_height = 700000L,
                        confirmations = 6,
                        associatedPubKey = "l1'xpub",
                        collectionId = "c1"
                    )
                )
            )
            db.utxoDao().insert(
                Utxo(
                    idx = "idx1",
                    addr = "bc1qexample",
                    pubKey = "utxo'pub",
                    value = 50L,
                    txHash = "ab",
                    txOutputN = 0,
                    collectionId = "c1"
                )
            )
            db.utxoLabelDao().upsert(
                UtxoLabel(
                    network = "mainnet",
                    txid = "ab",
                    vout = 0,
                    label = "it's a label",
                    createdAt = 1L,
                    updatedAt = 2L
                )
            )
            db.labelEntryDao().upsert(
                LabelEntry(
                    network = "mainnet",
                    type = "addr",
                    ref = "bc1qexample",
                    label = "addr label",
                    createdAt = 1L,
                    updatedAt = 2L
                )
            )
            db.txEntropyDao().insert(
                TxEntropy(
                    txid = "ab",
                    nbCmbn = 2,
                    entropyBits = 1.5,
                    linkabilityJson = "[]",
                    tooComplex = false,
                    computedAt = 3L
                )
            )
        }
        db.close()
        room = null
    }

    private fun singleText(session: SqlSession, sql: String): String {
        var found: String? = null
        session.queryEach(sql) { row -> found = row.text(0) }
        return found ?: throw AssertionError(sql)
    }

    private fun singleLong(session: SqlSession, sql: String): Long {
        var found: Long? = null
        session.queryEach(sql) { row -> found = row.long(0) }
        return found ?: throw AssertionError(sql)
    }
}

private class JdbcDatabaseOpener : DatabaseOpener {
    init {
        Class.forName("org.sqlite.JDBC")
    }

    override fun open(path: String, rawKey: ByteArray?): SqlSession {
        val url = "jdbc:sqlite:$path"
        val connection = if (rawKey == null) {
            DriverManager.getConnection(url)
        } else {
            SQLiteMCSqlCipherConfig.getV4Defaults()
                .withKey(DatabasePassphrase.hex(rawKey))
                .build()
                .createConnection(url)
        }
        return JdbcSqlSession(connection)
    }
}

private class JdbcSqlSession(private val connection: Connection) : SqlSession {
    override fun exec(sql: String, args: List<SqlValue>) {
        if (args.isEmpty()) {
            connection.createStatement().use { statement ->
                if (statement.execute(sql)) {
                    statement.resultSet?.close()
                }
            }
        } else {
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value ->
                    when (value) {
                        SqlValue.Null -> statement.setNull(index + 1, Types.NULL)
                        is SqlValue.Integer -> statement.setLong(index + 1, value.v)
                        is SqlValue.Real -> statement.setDouble(index + 1, value.v)
                        is SqlValue.Text -> statement.setString(index + 1, value.v)
                        is SqlValue.Blob -> statement.setBytes(index + 1, value.v)
                    }
                }
                statement.executeUpdate()
            }
        }
    }

    override fun queryEach(sql: String, consumer: (SqlRow) -> Unit) {
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                val count = rs.metaData.columnCount
                while (rs.next()) {
                    val values = ArrayList<SqlValue>(count)
                    for (i in 1..count) {
                        val obj = rs.getObject(i)
                        values.add(
                            when {
                                obj == null || rs.wasNull() -> SqlValue.Null
                                obj is ByteArray -> SqlValue.Blob(obj)
                                obj is Float || obj is Double -> SqlValue.Real((obj as Number).toDouble())
                                obj is Number -> SqlValue.Integer(obj.toLong())
                                obj is Boolean -> SqlValue.Integer(if (obj) 1L else 0L)
                                else -> SqlValue.Text(obj.toString())
                            }
                        )
                    }
                    consumer(SqlRow(values))
                }
            }
        }
    }

    override fun close() {
        connection.close()
    }
}
