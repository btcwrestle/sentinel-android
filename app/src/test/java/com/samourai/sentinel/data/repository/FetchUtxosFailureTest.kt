package com.samourai.sentinel.data.repository

import com.samourai.sentinel.api.ApiService.ApiNotConfigured
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import timber.log.Timber
import java.io.File

/**
 * #287: a failed automatic utxo fetch must not reconcile.
 *
 * The repository is Koin/Room-wired and not JVM-constructible, same
 * limit as FetchFromServerCatchLadderTest. Two legs:
 *
 * 1. A shape-verbatim ladder. A pubkey job that throws must skip save
 *    and propagate (so syncCollections can count the collection
 *    failed). ApiNotConfigured still degrades without a save (#103).
 *    Every job returning does save, including when the returned set
 *    is empty — that empty-success wipe stays #226.
 * 2. A source pin. The ladder alone cannot go red if the production
 *    function is reverted. The pin reads fetchUTXOS and syncCollections
 *    and fails on the pre-#287 shape: the one-line
 *    `catch (e: Exception) { Timber.e(e) }` swallow, and a failed
 *    round that leaves lastRoundSignature stamped.
 */
class FetchUtxosFailureTest {

    private fun fetchUtxosLadder(
        jobs: List<suspend () -> Unit>,
        save: () -> Unit,
    ): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val deferred = jobs.map { body -> scope.async { body() } }
            deferred.forEach { job ->
                try {
                    job.await()
                } catch (e: Exception) {
                    throw e
                }
            }
            save()
        } catch (e: ApiNotConfigured) {
            Timber.e(e)
        } catch (e: Exception) {
            Timber.e(e)
            throw e
        }
    }

    @Test
    fun `a failed pubkey job skips save and propagates`() {
        var saved = false
        try {
            fetchUtxosLadder(
                jobs = listOf(
                    { },
                    { throw IllegalStateException("connection refused") },
                ),
                save = { saved = true },
            )
            fail("expected the failure to propagate")
        } catch (e: IllegalStateException) {
            assertFalse(saved)
        }
    }

    @Test
    fun `ApiNotConfigured skips save and does not propagate`() {
        var saved = false
        fetchUtxosLadder(
            jobs = listOf { throw ApiNotConfigured() },
            save = { saved = true },
        )
        assertFalse(saved)
    }

    @Test
    fun `every job returning does save, even when the set is empty`() {
        var saved = false
        fetchUtxosLadder(
            jobs = listOf({ }, { }),
            save = { saved = true },
        )
        assertTrue(saved)
    }

    @Test
    fun `production fetchUTXOS rethrows before saveUtxos`() {
        val src = source(
            "src/main/java/com/samourai/sentinel/data/repository/TransactionsRepository.kt",
            "app/src/main/java/com/samourai/sentinel/data/repository/TransactionsRepository.kt",
        )
        val start = src.indexOf("suspend fun fetchUTXOS")
        assertTrue("fetchUTXOS missing", start >= 0)
        val fn = src.substring(start)
        assertFalse(
            "the one-line swallow is the #287 bug",
            fn.contains("catch (e: Exception) { Timber.e(e) }"),
        )
        val saveAt = fn.indexOf("saveUtxos(utxos, collectionId)")
        val throwAt = fn.indexOf("throw e")
        assertTrue(throwAt >= 0 && saveAt > throwAt)
        val outer = fn.indexOf("// #287: rethrow so syncCollections")
        assertTrue(outer > saveAt)
        assertTrue(fn.indexOf("throw e", outer) > outer)
    }

    @Test
    fun `a failed syncCollections round clears the pre-stamped signature`() {
        val src = source(
            "src/main/java/com/samourai/sentinel/ui/home/HomeViewModel.kt",
            "app/src/main/java/com/samourai/sentinel/ui/home/HomeViewModel.kt",
        )
        val start = src.indexOf("private suspend fun syncCollections")
        val end = src.indexOf("private fun maybeTrailingRerun")
        assertTrue(start >= 0 && end > start)
        val fn = src.substring(start, end)
        assertTrue(
            fn.contains("if (failed > 0) {\n                lastRoundSignature = null\n            }"),
        )
        assertTrue(
            fn.contains("lastRoundSignature = null\n                syncState.postValue(SyncState.Failed(\"Sync interrupted.\""),
        )
    }

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error(
                "source not found from ${File(".").absolutePath}: ${relative.toList()}",
            )
        return file.readText()
    }
}
