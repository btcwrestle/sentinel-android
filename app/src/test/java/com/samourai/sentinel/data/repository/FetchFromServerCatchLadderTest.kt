package com.samourai.sentinel.data.repository

import com.samourai.sentinel.api.ApiService.ApiNotConfigured
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import timber.log.Timber

/**
 * #103 pin: ApiNotConfigured extends Throwable, not Exception, so every
 * catch(Exception) wall in fetchFromServer was blind to it - an async
 * child's throw rode await() out of the function and killed the process
 * (two witnesses on #103: Dojo-switch mid-reconfigure, and a plain sync
 * round on an unpaired install).
 *
 * The repository itself is Koin/Room-wired and not JVM-constructible
 * (DojoAuthTokenTest precedent: duplicate the contract, not the wiring),
 * so these ladders are shape-verbatim replicas. Limitation, on record in
 * the PR body: the replica cannot go red against the repository code -
 * the hunk is verified by the gate plus the owed device leg.
 */
class FetchFromServerCatchLadderTest {

    /** The fixed outer ladder (this PR's hunk), shape-verbatim. */
    private fun fixedLadder(child: suspend () -> Unit): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val job = scope.async { child() }
            try {
                job.await()
            } catch (e: Exception) {
                throw e
            }
        } catch (e: ApiNotConfigured) {
            Timber.e(e)
            // degrade: logged, no rethrow - the sync aborts cleanly
        } catch (e: Exception) {
            Timber.e(e)
            throw e
        }
    }

    /**
     * Master's pre-fix ladder (the witnessed shape), kept in-file on
     * purpose: the wall-proof case below asserts it leaks. If the class
     * is ever re-parented under Exception, that case goes red here -
     * the rejection's teeth.
     */
    private fun masterLadder(child: suspend () -> Unit): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val job = scope.async { child() }
            try {
                job.await()
            } catch (e: Exception) {
                throw e
            }
        } catch (e: Exception) {
            Timber.e(e)
            throw e
        }
    }

    @Test
    fun `ApiNotConfigured is not an Exception - the root blindness`() {
        assertTrue(ApiNotConfigured() !is Exception)
    }

    @Test
    fun `fixed ladder contains a child-thrown ApiNotConfigured`() {
        // no propagation out of runBlocking = pass; a leak fails the test
        // with the exception itself
        fixedLadder { throw ApiNotConfigured() }
    }

    @Test
    fun `master ladder leaks the same throw - the witnessed flaw, pinned`() {
        try {
            masterLadder { throw ApiNotConfigured() }
            fail("expected the pre-fix ladder to leak ApiNotConfigured")
        } catch (e: ApiNotConfigured) {
            // the witness: the throw escapes every Exception wall
        }
    }

    @Test
    fun `fixed ladder still rethrows Exception-class failures`() {
        try {
            fixedLadder { throw IllegalStateException("io failure") }
            fail("expected the ladder to rethrow Exception-class failures")
        } catch (e: IllegalStateException) {
            // the existing log+rethrow contract, preserved
        }
    }

    @Test
    fun `abandoned siblings on the SupervisorJob scope surface nowhere`() {
        // first await escapes to the ordered catch; remaining jobs are
        // abandoned, their exceptions held by the SupervisorJob - the
        // fetchUTXOS production shape, no uncaught-exception death
        val scope = CoroutineScope(SupervisorJob())
        runBlocking {
            try {
                val jobs = listOf(
                    scope.async { throw ApiNotConfigured() },
                    scope.async { throw ApiNotConfigured() },
                )
                jobs.forEach { it.await() }
            } catch (e: ApiNotConfigured) {
                Timber.e(e)
            }
        }
    }
}
