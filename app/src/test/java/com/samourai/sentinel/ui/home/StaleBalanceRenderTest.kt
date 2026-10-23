package com.samourai.sentinel.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #235. Returning to a screen re-reads street mode and the fiat line.
 * Home used to hide the USD amount and then force it visible again,
 * and the collection rows only rebound when the balance was tapped.
 */
class StaleBalanceRenderTest {

    @Test
    fun `resume rebinds values instead of leaving the last paint on screen`() {
        val home = source("app/src/main/java/com/samourai/sentinel/ui/home/HomeActivity.kt")
        val resume = home.substringAfter("override fun onResume()").substringBefore("private fun setUp()")
        assertTrue(resume.contains("applyBalanceDisplayMode()"))
        assertTrue(resume.contains("collectionsAdapter.notifyDataSetChanged()"))
        assertFalse(resume.contains("exchangeRateTxt.visibility"))

        val collection = source("app/src/main/java/com/samourai/sentinel/ui/collectionDetails/transactions/TransactionsFragment.kt")
        val render = collection.substringAfter("private fun renderBalance")
        assertTrue(render.contains("collectionBalanceFiat.visibility"))
        assertFalse(collection.contains("collectionBalanceFiat.text = it"))

        val rows = source("app/src/main/java/com/samourai/sentinel/ui/collectionDetails/transactions/TransactionsListFragment.kt")
        assertTrue(rows.contains("override fun onResume()"))
        assertTrue(rows.contains("transactionAdapter.notifyDataSetChanged()"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
