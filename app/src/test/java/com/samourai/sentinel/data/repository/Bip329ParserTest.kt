package com.samourai.sentinel.data.repository

import com.samourai.sentinel.data.db.entity.LabelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite over the synthetic fixture in
 * src/test/resources/bip329/sample.jsonl. Fixture line order is
 * load-bearing: tests index into it. 8 lines carry usable labels,
 * 5 are rejected, 1 has no label field (ignored per BIP-329).
 */
class Bip329ParserTest {

    private val network = "mainnet"

    private fun lines(): List<String> =
        javaClass.getResourceAsStream("/bip329/sample.jsonl")!!
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }

    @Test
    fun fixtureCountsAreEightUsableFiveRejectedOneIgnored() {
        val results = lines().map { Bip329Parser.parse(it, network, 0L) }
        assertEquals(8, results.count { it != null && it !is Bip329Parser.Parsed.Ignored })
        assertEquals(5, results.count { it == null })
        assertEquals(1, results.count { it is Bip329Parser.Parsed.Ignored })
    }

    @Test
    fun outputRecordKeepsOriginVoutAndTxid() {
        val p = Bip329Parser.parse(lines()[0], network, 0L)
        assertTrue(p is Bip329Parser.Parsed.Output)
        val o = (p as Bip329Parser.Parsed.Output).label
        assertEquals("postmix", o.label)
        assertEquals("[f79a0d3d/84'/0'/0']", o.origin)
        assertEquals(0, o.vout)
        assertEquals(
            "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
            o.txid
        )
    }

    @Test
    fun uppercaseRefsAreNormalizedToLowercase() {
        val out = Bip329Parser.parse(lines()[1], network, 0L) as Bip329Parser.Parsed.Output
        assertEquals(
            "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d7",
            out.label.txid
        )
        assertEquals(1, out.label.vout)
        val tx = Bip329Parser.parse(lines()[2], network, 0L) as Bip329Parser.Parsed.Other
        assertEquals(
            "c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0",
            tx.entry.ref
        )
        val pub = Bip329Parser.parse(lines()[7], network, 0L) as Bip329Parser.Parsed.Other
        assertEquals(
            "02a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
            pub.entry.ref
        )
    }

    @Test
    fun crossNetworkRecordsAreRejectedOnMainnet() {
        assertNull(Bip329Parser.parse(lines()[4], network, 0L))   // tb1 address
        assertNull(Bip329Parser.parse(lines()[6], network, 0L))   // tpub
    }

    @Test
    fun crossNetworkRecordsImportWhenNetworkMatches() {
        val p = Bip329Parser.parse(lines()[4], "testnet", 0L)   // same tb1 line
        assertNotNull(p)
        assertEquals("testnet", (p as Bip329Parser.Parsed.Other).entry.network)
    }

    @Test
    fun absentLabelIsIgnoredPerBip329() {
        // BIP-329: an omitted label means "do not alter" — a valid
        // metadata-only line, not an error.
        val p = Bip329Parser.parse(lines()[12], network, 0L)
        assertTrue(p is Bip329Parser.Parsed.Ignored)
    }

    @Test
    fun malformedRecordsAreRejected() {
        assertNull(Bip329Parser.parse(lines()[9], network, 0L))   // unknown type
        assertNull(Bip329Parser.parse(lines()[10], network, 0L))  // txid too short
        assertNull(Bip329Parser.parse(lines()[11], network, 0L))  // non-numeric vout
    }

    @Test
    fun blankLabelParsesSoImporterCanDelete() {
        val p = Bip329Parser.parse(lines()[13], network, 0L)
        assertNotNull(p)
        assertEquals("", (p as Bip329Parser.Parsed.Other).entry.label)
    }

    @Test
    fun xpubWireRoundTripsThroughLabelType() {
        // The wire token is "xpub" for BOTH key serializations - the
        // fixture's testnet line is type:"xpub" with a tpub ref: the
        // JSON type field, not the ref prefix, is what fromWire parses.
        // Pin the token + roundtrip so the #64 delegation keeps
        // classifying refs while the type stays stable.
        assertEquals(LabelType.XPUB, LabelType.fromWire("xpub"))
        assertEquals("xpub", LabelType.fromWire("xpub")!!.wire)
        assertNull(LabelType.fromWire("banana"))
    }

    @Test
    fun mixedCaseXpubRefClassifiesViaNetworkClassifier() {
        // #64: classification delegates to the case-insensitive
        // NetworkClassifier contract - a mixed-case xpub ref resolves to
        // mainnet by its own verdict, where the old local table fell
        // through to the target-network fallback by accident.
        val line = "{\"type\":\"xpub\",\"ref\":\"XPUB661MyMwAeaAR\",\"label\":\"case probe\"}"
        val p = Bip329Parser.parse(line, network, 0L)
        assertNotNull(p)
        val entry = (p as Bip329Parser.Parsed.Other).entry
        assertEquals("mainnet", entry.network)
        assertEquals("XPUB661MyMwAeaAR", entry.ref)
    }

    @Test
    fun mixedCaseXpubRefOnTestnetTargetIsRejectedHonest() {
        // #64 discrimination leg: the old case-sensitive table returned
        // null for an "XPUB..." ref and the fallback-to-target-network
        // passed it through on a testnet import; the delegation
        // classifies the ref MAINNET and rejects it cross-network.
        // Fails on master (fallback passes), passes on the delegation.
        val line = "{\"type\":\"xpub\",\"ref\":\"XPUB661MyMwAeaAR\",\"label\":\"cross probe\"}"
        assertNull(Bip329Parser.parse(line, "testnet", 0L))
    }
}
