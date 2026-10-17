package com.samourai.sentinel.ui.fragments

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddNewPubKeyBottomSheetTest {

    @Test
    fun `lnbc lightning invoice is not a pairing payload and does not throw`() {
        val invoice = "lnbcrt1p3xzqaxd35gq2dywd5k8p5k7n3lx0wlu4xy9f9h2zyq3qarzz"
        assertFalse(AddNewPubKeyBottomSheet.isDojoPairingPayload(invoice))
    }

    @Test
    fun `bip21 uri is not a pairing payload`() {
        assertFalse(
            AddNewPubKeyBottomSheet.isDojoPairingPayload(
                "bitcoin:bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080?amount=0.1"
            )
        )
    }

    @Test
    fun `plain text and empty payload are not pairing payloads`() {
        assertFalse(AddNewPubKeyBottomSheet.isDojoPairingPayload("hello world"))
        assertFalse(AddNewPubKeyBottomSheet.isDojoPairingPayload(""))
    }

    @Test
    fun `valid json without pairing keys is not a pairing payload`() {
        assertFalse(AddNewPubKeyBottomSheet.isDojoPairingPayload("""{"foo":"bar"}"""))
    }

    @Test
    fun `dojo pairing json is detected`() {
        assertTrue(
            AddNewPubKeyBottomSheet.isDojoPairingPayload(
                """{"external":true,"payload":"pairing-content"}"""
            )
        )
    }

    @Test
    fun `payload with surrounding whitespace still parses`() {
        assertTrue(
            AddNewPubKeyBottomSheet.isDojoPairingPayload(
                """  {"external":true,"payload":"pairing-content"}  """
            )
        )
    }
}
