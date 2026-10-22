package com.samourai.sentinel.ui.fragments

import com.samourai.sentinel.data.AddressTypes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    // #254: the multi-key UR decode helpers.

    @Test
    fun `purpose is read from origin path`() {
        assertEquals("84", urPurposeFromPath("84'/0'/0'"))
        assertEquals("44", urPurposeFromPath("44'/0'/0'"))
        assertEquals("49", urPurposeFromPath("m/49'/0'/0'"))
        assertEquals("84", urPurposeFromPath("84"))
    }

    @Test
    fun `unparsable paths carry no purpose`() {
        assertNull(urPurposeFromPath(""))
        assertNull(urPurposeFromPath("x"))
        assertNull(urPurposeFromPath("abc'/0'"))
        assertNull(urPurposeFromPath(null))
    }

    @Test
    fun `supported purposes map to address types`() {
        assertEquals(AddressTypes.BIP44, urAccountType("44"))
        assertEquals(AddressTypes.BIP49, urAccountType("49"))
        assertEquals(AddressTypes.BIP84, urAccountType("84"))
    }

    @Test
    fun `unsupported purposes are null not bip84`() {
        assertNull(urAccountType("86"))
        assertNull(urAccountType("0"))
        assertNull(urAccountType(null))
    }

    @Test
    fun `bytes payload yields every leg`() {
        val json = JSONObject(
            "{\"xfp\":\"deadbeef\",\"bip44\":{\"_pub\":\"xpubA\"}," +
                "\"bip49\":{\"_pub\":\"ypubA\"},\"bip84\":{\"_pub\":\"zpubA\"}}"
        )
        val decode = urBytesKeys(json)
        assertEquals(3, decode.keys.size)
        assertEquals("xpubA", decode.keys[0].xpub)
        assertEquals(AddressTypes.BIP44, decode.keys[0].type)
        assertEquals("Deposit BIP44", decode.keys[0].label)
        assertEquals("Deposit BIP49", decode.keys[1].label)
        assertEquals("Deposit BIP84", decode.keys[2].label)
        assertTrue(decode.skipped.isEmpty())
    }

    @Test
    fun `bytes payload without bip84 is not empty`() {
        val json = JSONObject("{\"xfp\":\"deadbeef\",\"bip49\":{\"_pub\":\"ypubA\"}}")
        val decode = urBytesKeys(json)
        assertEquals(1, decode.keys.size)
        assertEquals("ypubA", decode.keys.first().xpub)
    }

    @Test
    fun `unreadable and unsupported legs are named skips`() {
        val json = JSONObject(
            "{\"xfp\":\"deadbeef\",\"bip84\":{},\"bip86\":{\"_pub\":\"xpub86\"}}"
        )
        val decode = urBytesKeys(json)
        assertTrue(decode.keys.isEmpty())
        assertEquals(2, decode.skipped.size)
        assertTrue(decode.skipped.any { it.startsWith("bip84") })
        assertTrue(decode.skipped.any { it.contains("bip86") })
    }

    @Test
    fun `duplicate descriptors collapse loudly`() {
        val decode = dedupeAccountKeys(
            listOf(
                URAccountKey("zpubA", "Deposit BIP84", AddressTypes.BIP84),
                URAccountKey("zpubA", "Deposit BIP84", AddressTypes.BIP84)
            )
        )
        assertEquals(1, decode.keys.size)
        assertEquals(1, decode.skipped.size)
        assertTrue(decode.skipped.first().contains("duplicate"))
    }

}
