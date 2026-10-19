package com.samourai.sentinel.ui.collectionDetails.transactions

import com.samourai.sentinel.data.Out
import com.samourai.sentinel.data.Tx
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #55: the chip resolver's decision boundary, pinned JVM-side (pattern:
 * PubsFingerprintTest - plain JUnit, data classes only, no Robolectric).
 * The render sites (TransactionAdapter row chips, details-sheet chip
 * row) are thin views over chipsFor(); their correctness is the device
 * leg per house rules.
 *
 * Key-format contract under test: "txid:vout" exactly as
 * LabelRepository.observeLabelMap builds it, txid lowercased, the
 * "-index" association suffix stripped - the same normalization the
 * tx-label lookup applies one screen over.
 */
class TxUtxoLabelsTest {

    private companion object {
        private const val TXID =
            "5c15acf562667768704f8cbfd56b217c9feafd8608230e556cde6b621645b40b"
    }

    private fun out(n: Int): Out = Out(n = n, value = 1000L, addr = null, xpub = null)

    private fun tx(vararg outs: Out, hash: String = TXID): Tx = Tx(
        hash = hash,
        time = 1L,
        version = 1,
        locktime = 0,
        result = 0L,
        inputs = emptyList(),
        out = outs.toList(),
        block_height = 1L
    )

    @Test
    fun labeledOutputYieldsItsChip() {
        val labels = mapOf("$TXID:22" to "Bad Bank")
        assertEquals(
            listOf("Bad Bank"),
            TxUtxoLabels.chipsFor(tx(out(0), out(22)), labels)
        )
    }

    @Test
    fun foreignTxidLabelsNeverMatch() {
        val labels = mapOf(
            "0000000000000000000000000000000000000000000000000000000000000000:22" to "Bad Bank",
            "$TXID:9" to "someone else's vout"
        )
        assertEquals(
            emptyList<String>(),
            TxUtxoLabels.chipsFor(tx(out(0), out(22)), labels)
        )
    }

    @Test
    fun sameLabelOnTwoOutputsCollapsesToOneChip() {
        val labels = mapOf("$TXID:0" to "Bad Bank", "$TXID:22" to "Bad Bank")
        assertEquals(
            listOf("Bad Bank"),
            TxUtxoLabels.chipsFor(tx(out(0), out(22)), labels)
        )
    }

    @Test
    fun distinctLabelsKeepOutputOrder() {
        val labels = mapOf("$TXID:3" to "Postmix", "$TXID:1" to "Bad Bank")
        assertEquals(
            listOf("Postmix", "Bad Bank"),
            TxUtxoLabels.chipsFor(tx(out(3), out(1)), labels)
        )
    }

    @Test
    fun hashSuffixAndCaseNormalize() {
        val labels = mapOf("$TXID:22" to "Bad Bank")
        assertEquals(
            listOf("Bad Bank"),
            TxUtxoLabels.chipsFor(tx(out(22), hash = TXID.uppercase() + "-3"), labels)
        )
    }

    @Test
    fun blankLabelsAreDropped() {
        val labels = mapOf("$TXID:0" to "   ")
        assertEquals(emptyList<String>(), TxUtxoLabels.chipsFor(tx(out(0)), labels))
    }

    @Test
    fun emptyMapShortCircuits() {
        assertEquals(
            emptyList<String>(),
            TxUtxoLabels.chipsFor(tx(out(0), out(22)), emptyMap())
        )
    }

    @Test
    fun outputlessTxYieldsNothing() {
        val labels = mapOf("$TXID:0" to "Bad Bank")
        assertEquals(emptyList<String>(), TxUtxoLabels.chipsFor(tx(), labels))
    }
}
