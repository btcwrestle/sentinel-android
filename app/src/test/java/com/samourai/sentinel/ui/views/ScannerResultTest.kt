package com.samourai.sentinel.ui.views

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #218. The scanner sheets stored the consumer in a field. A restored
 * sheet scanned into that empty field. The decode is now a fragment
 * result, and the host re-registers the key.
 */
class ScannerResultTest {

    @Test
    fun `scanners post a fragment result and the hosts listen for it`() {
        val camera = source("app/src/main/java/com/samourai/sentinel/ui/views/codeScanner/CameraFragmentBottomSheet.java")
        assertTrue(camera.contains("setFragmentResult"))
        assertTrue(camera.contains("newInstance"))
        assertFalse(camera.contains("ListenQRScan"))
        assertFalse(camera.contains("setQrCodeScanLisenter"))
        val tx = source("app/src/main/java/com/samourai/sentinel/ui/broadcast/BroadcastTx.kt")
        assertTrue(tx.contains("setFragmentResultListener(REQUEST_SCAN_TX"))
        assertTrue(tx.contains("ScanTxFragment.newInstance"))
        assertFalse(tx.contains("setOnScanListener"))
        val sweep = source("app/src/main/java/com/samourai/sentinel/ui/tools/sweep/SweepPrivKeyFragment.kt")
        assertTrue(sweep.contains("setFragmentResultListener(REQUEST_SCAN_PRIVKEY"))
        assertTrue(sweep.contains("ScanPrivKeyFragment.newInstance"))
        assertFalse(sweep.contains("setOnScanListener"))
        val send = source("app/src/main/java/com/samourai/sentinel/ui/collectionDetails/send/SendFragment.kt")
        assertTrue(send.contains("setFragmentResultListener(REQUEST_SCAN_PAYMENT"))
        assertTrue(send.contains("CameraFragmentBottomSheet.newInstance"))
        assertFalse(send.contains("setQrCodeScanLisenter"))
        val compose = source("app/src/main/java/com/samourai/sentinel/ui/broadcast/BroadcastFromComposeTx.kt")
        assertTrue(compose.contains("setFragmentResultListener(REQUEST_SCAN_SIGNED_TX"))
        assertFalse(compose.contains("setQrCodeScanLisenter"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
