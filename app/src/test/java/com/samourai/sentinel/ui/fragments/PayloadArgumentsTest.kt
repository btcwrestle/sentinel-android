package com.samourai.sentinel.ui.fragments

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #216. A restored sheet is built with the no-arg constructor, so a
 * payload kept only as a constructor field is gone. The pager then
 * restores its own pages, which are not the field instances that were
 * wired. The payload rides in arguments, and the listeners attach to
 * the page under its tag.
 */
class PayloadArgumentsTest {

    @Test
    fun `pairing add-key and sweep keep the payload and wire the restored page`() {
        val pairing = source("app/src/main/java/com/samourai/sentinel/ui/fragments/WalletPairingFragment.kt")
        assertTrue(pairing.contains("putString(ARG_PAYLOAD, payload)"))
        assertTrue(pairing.contains("findFragmentByTag(\"f0\")"))
        assertFalse(pairing.contains("class WalletPairingFragment(private val payload"))

        val add = source("app/src/main/java/com/samourai/sentinel/ui/fragments/AddNewPubKeyBottomSheet.kt")
        assertTrue(add.contains("putString(ARG_PUBKEY, pubKey)"))
        assertTrue(add.contains("findFragmentByTag(\"f0\")"))
        assertFalse(add.contains("class AddNewPubKeyBottomSheet(private val pubKey"))

        val sweep = source("app/src/main/java/com/samourai/sentinel/ui/tools/sweep/SweepPrivKeyFragment.kt")
        assertTrue(sweep.contains("putString(ARG_PRIVKEY, privKey)"))
        assertTrue(sweep.contains("findFragmentByTag(\"f0\")"))
        assertTrue(sweep.contains("class SweepKeyEntryFragment"))
        assertFalse(sweep.contains("class SweepPrivKeyFragment(private val privKey"))
        assertFalse(sweep.contains("class SweepKeyEntryFragment(private val privKey"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
