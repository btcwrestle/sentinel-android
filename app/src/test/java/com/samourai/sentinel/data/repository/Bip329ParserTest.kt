package com.samourai.sentinel.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite over the synthetic fixture in
 * src/test/resources/bip329/sample.jsonl. Fixture line order is
 * load-bearing: tests index into it. 8 lines parse, 6 are rejected.
 */
class Bip329ParserTest {

    private val network = "mainnet"

    private fun lines(): List<String> =
        javaClass.getResourceAsStream("/bip329/sample.jsonl")!!
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }

    @Test
    fun fixtureParsesEightRecordsAndRejectsSix() {
        val parsed = lines().mapNotNull { Bip329Parser.parse(it, network, 0L) }
        assertEquals(8, parsed.size)
        assertEquals(6, lines().size - parsed.size)
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
    fun malformedRecordsAreRejected() {
        assertNull(Bip329Parser.parse(lines()[9], network, 0L))   // unknown type
        assertNull(Bip329Parser.parse(lines()[10], network, 0L)) // txid too short
        assertNull(Bip329Parser.parse(lines()[11], network, 0L)) // non-numeric vout
        assertNull(Bip329Parser.parse(lines()[12], network, 0L))  // missing label field
    }

    @Test
    fun blankLabelParsesSoImporterCanDelete() {
        val p = Bip329Parser.parse(lines()[13], network, 0L)
        assertNotNull(p)
        assertEquals("", (p as Bip329Parser.Parsed.Other).entry.label)
    }
}
