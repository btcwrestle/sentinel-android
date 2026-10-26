package com.samourai.sentinel.data.db

import java.io.Closeable

internal sealed class SqlValue {
    object Null : SqlValue()
    data class Integer(val v: Long) : SqlValue()
    data class Real(val v: Double) : SqlValue()
    data class Text(val v: String) : SqlValue()
    data class Blob(val v: ByteArray) : SqlValue()

    fun toBindArg(): Any? = when (this) {
        Null -> null
        is Integer -> v
        is Real -> v
        is Text -> v
        is Blob -> v
    }
}

internal data class SqlRow(val values: List<SqlValue>) {
    fun text(index: Int): String {
        val value = values[index]
        if (value is SqlValue.Text) return value.v
        if (value is SqlValue.Null) throw IllegalStateException("null column")
        throw IllegalStateException("text column")
    }

    fun long(index: Int): Long = when (val value = values[index]) {
        is SqlValue.Integer -> value.v
        is SqlValue.Real -> value.v.toLong()
        else -> throw IllegalStateException("number column")
    }
}

/**
 * Minimal SQL surface used to copy a plaintext database into an
 * encrypted one and to delete user rows. Production and tests each
 * supply an implementation; the statements live in one place.
 */
internal interface SqlSession : Closeable {
    fun exec(sql: String, args: List<SqlValue> = emptyList())
    fun queryEach(sql: String, consumer: (SqlRow) -> Unit)
}

/** [rawKey] null opens a plaintext database. A non-null key must be 32 bytes. */
internal interface DatabaseOpener {
    fun open(path: String, rawKey: ByteArray?): SqlSession
}
