package com.samourai.sentinel.ui.tools.sweep

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #215. PreviewBottomSheet restored through the no-arg constructor and
 * then read selectedPubKey!! and the lateinit utxo list. The preview
 * now rides in arguments, and a sheet that does not have it closes.
 */
class PreviewStateTest {

    @Test
    fun `the sweep preview keeps its state in arguments and does not crash on an empty restore`() {
        val sweep = source("app/src/main/java/com/samourai/sentinel/ui/tools/sweep/SweepPrivKeyFragment.kt")
        val preview = sweep.substringAfter("class PreviewBottomSheet")
            .substringBefore("class ScanPrivKeyFragment")
        assertFalse(preview.contains("selectedPubKey!!"))
        assertFalse(preview.contains("lateinit var utxoList"))
        assertTrue(preview.contains("sweep_preview_arg_pubkey"))
        assertTrue(preview.contains("sweep_preview_arg_utxos"))
        assertTrue(preview.contains("sweep_preview_arg_wif"))
        assertTrue(preview.contains("fun hasPreviewState()"))
        assertTrue(preview.contains("if (!hasPreviewState())"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
