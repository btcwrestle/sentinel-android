package com.samourai.sentinel.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * #305 / #306. `feeMed == feeMed` was always true, so any non-identical
 * tier triple overwrote Normal with the Low/High average and then
 * stored High as that Normal. Normal is the average only when two
 * adjacent tiers collapsed, and the stored per-KB value is that
 * average, not High.
 */
class FeeTierNormalizeTest {

    @Test
    fun `three distinct tiers keep the server normal`() {
        assertEquals(5L, collapsedNormalPerVb(low = 2L, med = 5L, high = 10L))
    }

    @Test
    fun `low collapsed into med becomes the low-high average`() {
        assertEquals(7L, collapsedNormalPerVb(low = 4L, med = 4L, high = 10L))
    }

    @Test
    fun `med collapsed into high becomes the low-high average`() {
        assertEquals(5L, collapsedNormalPerVb(low = 2L, med = 8L, high = 8L))
    }

    @Test
    fun `all equal stays put — the 0_85 spread is the other branch`() {
        assertEquals(6L, collapsedNormalPerVb(low = 6L, med = 6L, high = 6L))
    }

    @Test
    fun `neither call site still has the always-true check or stores high as normal`() {
        val send = source(
            "src/main/java/com/samourai/sentinel/ui/collectionDetails/send/SendFragment.kt",
            "app/src/main/java/com/samourai/sentinel/ui/collectionDetails/send/SendFragment.kt",
        )
        val sweep = source(
            "src/main/java/com/samourai/sentinel/ui/tools/sweep/SweepPrivKeyFragment.kt",
            "app/src/main/java/com/samourai/sentinel/ui/tools/sweep/SweepPrivKeyFragment.kt",
        )
        for (src in listOf(send, sweep)) {
            assertFalse(src.contains("feeMed == feeMed"))
            assertFalse(src.contains("BigInteger.valueOf(feeHigh * 1000L)\n            feeRepository.setNormalFee"))
            assert(src.contains("feeLow == feeMed || feeMed == feeHigh"))
            assert(src.contains("BigInteger.valueOf(feeMed * 1000L)\n            feeRepository.setNormalFee"))
        }
    }

    private fun source(vararg relative: String): String {
        val file = relative.map { File(it) }.firstOrNull { it.isFile }
            ?: error("source not found from ${File(".").absolutePath}: ${relative.toList()}")
        return file.readText()
    }
}
