package com.samourai.sentinel.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #226. A flaky Dojo round parsed as an empty fee table and an empty
 * tx list, and the reconcile treated that as an empty wallet. The
 * write is allowed for a non-empty list, or for an empty list that
 * came with the healthy-round signal.
 */
class EmptyReconcileTest {

    @Test
    fun `an empty list without the healthy signal is not written`() {
        assertFalse(emptyReconcileAllowed(0, false))
    }

    @Test
    fun `an empty list with the healthy signal is a real wipe`() {
        assertTrue(emptyReconcileAllowed(0, true))
    }

    @Test
    fun `a non-empty list is written even without the signal`() {
        assertTrue(emptyReconcileAllowed(13, false))
    }

    @Test
    fun `fetchFromServer skips the tx wipe unless a fee table was seen`() {
        val src = source("app/src/main/java/com/samourai/sentinel/data/repository/TransactionsRepository.kt")
        val start = src.indexOf("suspend fun fetchFromServer(collectionId: String)")
        val end = src.indexOf("private suspend fun saveTx")
        assertTrue(start >= 0 && end > start)
        val fn = src.substring(start, end)
        assertTrue(fn.contains("if (!response.info.fees.isNullOrEmpty()) sawFeeTable = true"))
        assertTrue(fn.contains("if (emptyReconcileAllowed(newTransactions.size, sawFeeTable))"))
        assertTrue(fn.contains("empty tx round without a fee table; not wiping"))
        val saveAt = fn.indexOf("saveTx(newTransactions, collectionId)")
        val gateAt = fn.indexOf("emptyReconcileAllowed(newTransactions.size, sawFeeTable)")
        assertTrue(gateAt >= 0 && saveAt > gateAt)
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
