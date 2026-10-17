package com.samourai.sentinel.data.entropy

import com.samourai.boltzmann.beans.Txos
import com.samourai.boltzmann.linker.IntraFees
import com.samourai.boltzmann.linker.TxosLinker
import com.samourai.boltzmann.linker.TxosLinkerOptionEnum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Guard test for the vendored engine's maxDuration bailout (#107
 * prerequisite; the #102 NPE class - 45 device txids).
 *
 * Pre-guard: computeLinkMatrix's duration check (TxosAggregator
 * :320-323) returned TxosAggregatorResult(0, null); TxosLinker:168
 * hands the null matrix to findDtrmLinks, which NPEs at
 * matCmbn.size64() (TxosAggregator:677).
 *
 * Post-guard: the bailout returns the engine's existing txos-cap
 * sentinel state - nbCmbn=0 with a zero [output][input] matrix, the
 * same shape TxosLinker:103 initializes when the txos cap skips
 * compute, and the state the app already reports as TooComplex
 * (proven tolerated end-to-end by BoltzmannTxServiceTest.
 * beyondTheTxosCapReportsTooComplex).
 *
 * maxDuration=0 trips the duration check on the first loop
 * iteration, before any DFS work: a deterministic reproducer with
 * no timing dependence. RED pre-guard (NPE), GREEN post-guard.
 */
class BoltzmannEngineBailoutTest {

    @Test
    fun durationBailoutReturnsTheTxosCapSentinelStateNotANullMatrix() {
        val txos = Txos(
            linkedMapOf("in0" to 1_000_100L, "in1" to 1_000_100L),
            linkedMapOf("out0" to 1_000_000L, "out1" to 1_000_000L),
        )
        val fees = 2L * 1_000_100L - 2L * 1_000_000L
        val linker = TxosLinker(fees, 0, 12)
        val result = linker.process(
            txos,
            emptyList(),
            setOf(
                TxosLinkerOptionEnum.LINKABILITY,
                TxosLinkerOptionEnum.MERGE_INPUTS,
            ),
            IntraFees(0, 0),
        )
        assertEquals(0, result.nbCmbn)
        assertNotNull(result.matLnkCombinations)
    }
}
