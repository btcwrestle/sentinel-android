package com.samourai.sentinel.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

class ReconnectPolicyTest {

    private fun noJitter() = ReconnectPolicy(
        baseMs = 1_000L, maxMs = 60_000L, factor = 2.0,
        jitterRatio = 0.0, random = Random(42))

    @Test
    fun `delays grow exponentially from the base`() {
        val p = noJitter()
        assertEquals(1_000L, p.nextDelayMs())
        assertEquals(2_000L, p.nextDelayMs())
        assertEquals(4_000L, p.nextDelayMs())
        assertEquals(8_000L, p.nextDelayMs())
    }

    @Test
    fun `delays cap at max`() {
        val p = noJitter()
        // attempts 0..9 reach 512s; everything from attempt 10 on is capped.
        repeat(10) { p.nextDelayMs() }
        assertEquals(60_000L, p.nextDelayMs())
        assertEquals(60_000L, p.nextDelayMs())
        assertEquals(60_000L, p.nextDelayMs())
    }

    @Test
    fun `reset returns to base`() {
        val p = noJitter()
        repeat(6) { p.nextDelayMs() }
        p.reset()
        assertEquals(1_000L, p.nextDelayMs())
    }

    @Test
    fun `jitter stays within ratio bounds`() {
        val p = ReconnectPolicy(
            baseMs = 1_000L, maxMs = 60_000L, factor = 2.0,
            jitterRatio = 0.2, random = Random(7))
        repeat(200) { attempt ->
            val capped = min(1_000.0 * 2.0.pow(attempt), 60_000.0)
            val d = p.nextDelayMs()
            // +-1ms slack for toLong() truncation.
            assertTrue(
                "delay $d escaped bounds for attempt $attempt (capped $capped)",
                d + 1 >= (capped * 0.8).toLong() && d <= (capped * 1.2).toLong() + 1)
        }
    }

    @Test
    fun `jittered delays are never negative and never exceed max plus jitter`() {
        val p = ReconnectPolicy(
            baseMs = 1_000L, maxMs = 60_000L, factor = 2.0,
            jitterRatio = 0.2, random = Random(1))
        repeat(100) {
            val d = p.nextDelayMs()
            assertTrue(d >= 0L)
            assertTrue(d <= 72_000L)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero base rejected`() {
        ReconnectPolicy(baseMs = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `factor of 1 rejected`() {
        ReconnectPolicy(factor = 1.0)
    }
}
