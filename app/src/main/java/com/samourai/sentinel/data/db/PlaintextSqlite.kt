package com.samourai.sentinel.data.db

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile

internal object PlaintextSqlite {
    private val HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun isPlaintext(file: File): Boolean {
        if (!file.isFile || file.length() < HEADER.size) return false
        val buf = ByteArray(HEADER.size)
        FileInputStream(file).use { input ->
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) return false
                off += n
            }
        }
        return buf.contentEquals(HEADER)
    }

    /** Merge a plaintext WAL into the main file so the copy sees every row. */
    fun checkpoint(file: File) {
        if (!file.exists()) return
        // Do not use the default corruption handler: it deletes the file.
        val handler = DatabaseErrorHandler { }
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            handler
        )
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                cursor.moveToFirst()
            }
        } finally {
            db.close()
        }
        shredSidecars(file)
    }

    fun shred(file: File) {
        if (!file.exists()) return
        if (file.isFile) {
            try {
                RandomAccessFile(file, "rws").use { raf ->
                    val zeros = ByteArray(8192)
                    var left = raf.length()
                    raf.seek(0)
                    while (left > 0) {
                        val n = minOf(left, zeros.size.toLong()).toInt()
                        raf.write(zeros, 0, n)
                        left -= n
                    }
                    raf.fd.sync()
                }
            } catch (_: IOException) {
            }
        }
        file.delete()
    }

    fun shredSidecars(file: File) {
        shred(File(file.path + "-wal"))
        shred(File(file.path + "-shm"))
        shred(File(file.path + "-journal"))
    }

    /** Migration leftovers only. Never the live database or its WAL. */
    fun shredPlaintextResidue(dbFile: File) {
        val parent = dbFile.parentFile ?: return
        for (suffix in listOf(".plaintext", ".plaintext-old", ".encrypting", ".encrypting-failed")) {
            val extra = File(parent, dbFile.name + suffix)
            shred(extra)
            shredSidecars(extra)
        }
    }
}
