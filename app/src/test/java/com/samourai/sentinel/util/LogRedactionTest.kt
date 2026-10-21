package com.samourai.sentinel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber
import java.net.UnknownHostException

/**
 * #264/#96 pins for the logging-boundary redaction. The flaw itself was
 * proven on device (the #265 leg measured 7 onion renders in one sync
 * window); these pin the fix's contract: redact() is exact about what it
 * touches, and the log() seam covers the stack that Timber 4.7.1's
 * private prepareLog appends into every message.
 *
 * JVM-testability note: MaskingDebugTree itself calls through to
 * android.util.Log (DebugTree.log), so it cannot run here without
 * Robolectric - the production override is one line, eye-reviewed,
 * and gated on device. What CAN be pinned here is redact() and the
 * seam mechanism: a Tree applying redact() at log() sees no onion
 * host anywhere in what the forest delivers - stack included.
 */
class LogRedactionTest {

    /** 32-char base32 host - the shape UnknownHostException renders. */
    private val host32 = "abcdefghijklmnopqrstuvwxyz234567"

    @Test
    fun onionHostInExceptionShapeIsRedacted() {
        val line = "java.net.UnknownHostException: Unable to resolve host \"$host32.onion\""
        val out = LogRedaction.redact(line)
        assertFalse("host must not survive", out.contains("$host32.onion"))
        assertTrue("marker must render", out.contains("ONION_REDACTED"))
        assertTrue("diagnostic type must survive", out.contains("UnknownHostException"))
    }

    @Test
    fun fullLengthV3HostIsRedacted() {
        val host56 = (host32 + host32).take(56)
        val out = LogRedaction.redact("failed to connect to $host56.onion:443")
        assertFalse(out.contains("$host56.onion"))
        assertTrue(out.contains("ONION_REDACTED"))
    }

    @Test
    fun hostBelowTheFloorIsLeftAlone() {
        val host15 = "abcdefghijklmno"
        val out15 = LogRedaction.redact("lookup failed for $host15.onion")
        assertEquals(
            "15-char host must pass through unchanged",
            "lookup failed for $host15.onion",
            out15,
        )
        val host16 = "${host15}p"
        val out16 = LogRedaction.redact("lookup failed for $host16.onion")
        assertFalse("16-char host must be redacted", out16.contains("$host16.onion"))
    }

    @Test
    fun multipleHostsInOneStackTraceAreAllRedacted() {
        val stack =
            "java.net.UnknownHostException: Unable to resolve host \"$host32.onion\"\n" +
                "\tat okhttp3.internal.connection.RealConnection.connectSocket(Dns.kt:1)\n" +
                "\tCaused by: java.net.UnknownHostException: \"$host32.onion\"\n"
        val out = LogRedaction.redact(stack)
        assertFalse(out.contains("$host32.onion"))
        assertEquals(2, Regex("ONION_REDACTED").findAll(out).count())
    }

    @Test
    fun hex64IsRedacted() {
        val txid = "ab".repeat(32)
        val out = LogRedaction.redact("insert failed for $txid")
        assertFalse(out.contains(txid))
        assertTrue(out.contains("HEX_REDACTED"))
    }

    @Test
    fun hexBelow64IsLeftAlone() {
        val short = "deadbeef"
        assertEquals(short, LogRedaction.redact(short))
        val hex63 = "ab".repeat(31) + "a"
        assertEquals("63-char hex must pass through", hex63, LogRedaction.redact(hex63))
    }

    @Test
    fun redactAtTheLogSeamCoversTimbersAppendedStack() {
        val lines = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                lines += LogRedaction.redact(message)
            }
        }
        Timber.plant(tree)
        try {
            Timber.e(
                UnknownHostException("Unable to resolve host \"$host32.onion\""),
                "dojo import auth attempt 1/3 failed",
            )
        } finally {
            Timber.uproot(tree)
        }
        assertTrue("must log - never silence", lines.isNotEmpty())
        assertTrue(
            "the marker must survive",
            lines.any { it.contains("dojo import auth attempt 1/3 failed") },
        )
        lines.forEach { line ->
            assertFalse("no onion host may survive the seam: $line", line.contains("$host32.onion"))
        }
        assertTrue(
            "the exception type must stay diagnostic",
            lines.any { it.contains("UnknownHostException") },
        )
    }
}
