package com.samourai.sentinel.ui.views

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #221. The #210 secondary constructors kept old call sites compiling,
 * and those call sites built the sheet with a lambda that died on
 * restore. The factories put the same values in arguments.
 */
class NewInstanceBridgeTest {

    @Test
    fun `legacy sheet constructors are gone and call sites use the factories`() {
        val dialogs = source("app/src/main/java/com/samourai/sentinel/ui/views/BottomSheetDialogs.kt")
        assertFalse(dialogs.contains("constructor(label: String, onViewReady"))
        assertFalse(dialogs.contains("constructor(label: String, txId: String"))
        assertFalse(dialogs.contains("legacyViewReady"))
        assertTrue(dialogs.contains("super.onViewCreated(view, savedInstanceState)"))
        val tx = source("app/src/main/java/com/samourai/sentinel/ui/fragments/TransactionsDetailsBottomSheet.kt")
        assertFalse(tx.contains("constructor(tx: Tx"))
        assertTrue(tx.contains("fun newInstance(tx: Tx, secure: Boolean = false)"))
        val qr = source("app/src/main/java/com/samourai/sentinel/ui/fragments/QRBottomSheetDialog.kt")
        assertFalse(qr.contains("constructor("))
        assertTrue(qr.contains("fun newInstance("))
        val list = source("app/src/main/java/com/samourai/sentinel/ui/dojo/CommunityDojoListBottomSheet.kt")
        assertFalse(list.contains("legacyOnDojoSelected"))
        assertFalse(list.contains("constructor(onDojoSelected"))
        val host = source("app/src/main/java/com/samourai/sentinel/ui/dojo/DojoConfigureBottomSheet.kt")
        assertTrue(host.contains("CommunityDojoListBottomSheet().show("))
        assertFalse(host.contains("CommunityDojoListBottomSheet {"))
        val broadcast = source("app/src/main/java/com/samourai/sentinel/ui/broadcast/BroadcastTx.kt")
        assertTrue(broadcast.contains("SuccessfulBottomSheet.newInstance("))
        assertFalse(broadcast.contains("onViewReady"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
