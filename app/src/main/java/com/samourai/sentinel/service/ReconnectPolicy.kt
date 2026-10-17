package com.samourai.sentinel.service

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with uniform jitter for websocket reconnect attempts.
 *
 * Pure JVM - injected [Random] makes the sequence deterministic under test.
 * See #41: reconnects previously fired per-failure with no cooldown, which
 * stacked into a retry storm whenever Tor state or connectivity flapped.
 */
class ReconnectPolicy(
    private val baseMs: Long = 1_000L,
    private val maxMs: Long = 60_000L,
    private val factor: Double = 2.0,
    private val jitterRatio: Double = 0.2,
    private val random: Random = Random.Default,
) {
    init {
        require(baseMs > 0L) { "baseMs must be positive" }
        require(maxMs >= baseMs) { "maxMs must be >= baseMs" }
        require(factor > 1.0) { "factor must be > 1" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be within [0, 1]" }
    }

    private val attempt = AtomicInteger(0)

    /** Clear the attempt history after a successful (re)connect. */
    fun reset() {
        attempt.set(0)
    }

    /**
     * Delay to wait before the next reconnect attempt. Advances the attempt
     * counter. Exponential growth from [baseMs], capped at [maxMs], plus or
     * minus uniform jitter of [jitterRatio] so independent callers never
     * synchronize their retries into a burst.
     */
    fun nextDelayMs(): Long {
        val exponential = baseMs * factor.pow(attempt.getAndIncrement())
        val capped = min(exponential, maxMs.toDouble())
        val jitter = 1.0 + (random.nextDouble() * 2.0 - 1.0) * jitterRatio
        return (capped * jitter).toLong().coerceAtLeast(0L)
    }
}
