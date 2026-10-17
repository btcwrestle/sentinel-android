package com.samourai.sentinel.util

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.ContinuationInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #149 structural gate, mirroring the #130 doc block's semantics in Scopes.kt.
 *
 * `dataBaseScope carries terminal containment` is the discrimination leg: it
 * must FAIL on pre-#149 master (dataBaseScope carried no
 * CoroutineExceptionHandler - launch children died uncaught to the process)
 * and PASS after. The other two legs are invariant pins - they pass on old
 * and new code alike and exist to catch the fix dropping the SupervisorJob
 * or the IO dispatcher while adding the handler, not to discriminate.
 */
class ScopesTest {

    @Test
    fun `dataBaseScope carries terminal containment`() {
        assertNotNull(
            "dataBaseScope carries no CoroutineExceptionHandler - " +
                "launch children die uncaught to the process (#149 open)",
            dataBaseScope.coroutineContext[CoroutineExceptionHandler]
        )
    }

    /**
     * A plain Job would let one failing child cancel every sibling, and
     * losing Dispatchers.IO would silently move every dataBaseScope writer
     * onto the default dispatcher.
     */
    @Test
    fun `dataBaseScope keeps its supervisor and IO invariants`() {
        val job = dataBaseScope.coroutineContext[Job]
        assertNotNull("dataBaseScope lost its Job while gaining the handler", job)
        assertTrue(
            "dataBaseScope's Job is no longer a SupervisorJob - a failing child would take siblings",
            job!!.javaClass.simpleName.contains("Supervisor")
        )
        assertEquals(
            "dataBaseScope lost Dispatchers.IO while gaining the handler",
            Dispatchers.IO,
            dataBaseScope.coroutineContext[ContinuationInterceptor]
        )
    }

    /** apiScope has carried its handler since #130/#144 - regression pin. */
    @Test
    fun `apiScope still carries its handler`() {
        assertNotNull(
            "apiScope lost its CoroutineExceptionHandler - #144 regression",
            apiScope.coroutineContext[CoroutineExceptionHandler]
        )
    }
}
