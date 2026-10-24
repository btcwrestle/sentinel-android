package com.samourai.sentinel.ui.home

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cached collection rows publish before the network round. The header total
 * has to post in that same callback, or it stays blank until fetchUTXOS.
 */
class CachedTotalPaintTest {

    @Test
    fun `cached collections post the header total before any sync return`() {
        val src = source("app/src/main/java/com/samourai/sentinel/ui/home/HomeViewModel.kt")
        val fn = src.substringAfter("fun getCollections()")
            .substringBefore("private suspend fun syncCollections")
        val afterPublish = fn.substringAfter("resultLiveData.value = collections")
        val beforeRound = afterPublish.substringBefore("launchSyncRound(collections)")
        assertTrue(beforeRound.contains("updateBalance()"))
        val beforeFirstReturn = beforeRound.substringBefore("return@addSource")
        assertTrue(beforeFirstReturn.contains("updateBalance()"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
