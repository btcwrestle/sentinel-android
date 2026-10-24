package com.samourai.sentinel.ui.tools.sweep

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #280. Two classes were named ScanPubKeyFragment. The sweep page is
 * the private-key entry step. The pubkey scanner in ui.fragments
 * keeps the old name.
 */
class SweepKeyEntryNameTest {

    @Test
    fun `the sweep key entry is not also named ScanPubKeyFragment`() {
        val sweep = source("app/src/main/java/com/samourai/sentinel/ui/tools/sweep/SweepPrivKeyFragment.kt")
        assertTrue(sweep.contains("class SweepKeyEntryFragment"))
        assertFalse(sweep.contains("class ScanPubKeyFragment"))
        assertFalse(sweep.contains("ScanPubKeyFragment("))
        val add = source("app/src/main/java/com/samourai/sentinel/ui/fragments/AddNewPubKeyBottomSheet.kt")
        assertTrue(add.contains("class ScanPubKeyFragment"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
