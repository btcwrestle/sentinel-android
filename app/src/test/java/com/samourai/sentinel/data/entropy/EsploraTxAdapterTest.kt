package com.samourai.sentinel.data.entropy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests for EsploraTxAdapter on the two /tx bodies
 * banked verbatim from the on-device dojo census (#72):
 * testnet, public on-chain facts only - the esplora payload
 * carries scriptpubkeys and sig/witness hex, all public
 * blockchain data; the bodies were probed key-by-key before
 * being banked.
 *
 *  - tx_5ca0.json: the x3-slice TX0 (5ca05aa5...). Full tx
 *    1x28 where the wallet's merged view was 1x26.
 *  - tx_exhibit.json: the flaw exhibit (380709af...). Full
 *    tx 2x11 where the wallet's merged view was 2x9; output
 *    values 0, 5000, 53539, 106352 x8; fees 12432 and
 *    output sum 909355, so input values sum to 921787.
 *
 * Tolerance cases pin the conventions the ingest relies
 * on: unparseable or txid-less body -> null (the
 * fetch-failure arm); an input without outpoint ->
 * prev_out = null (the InsufficientData arm).
 */
class EsploraTxAdapterTest {

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/entropy/$name")!!
            .bufferedReader().readText()

    @Test
    fun golden5ca0AdaptsTheFullTxShape() {
        val tx = EsploraTxAdapter.fromJson(resource("tx_5ca0.json"))
        assertNotNull("golden body must adapt", tx)
        assertEquals(
            "5ca05aa563ff6fc2ceaac5c9a51b796628548dd04644da90b7cf75022450e749",
            tx!!.hash,
        )
        assertEquals(1, tx.inputs.size)
        assertEquals(28, tx.out.size)
        tx.inputs.forEach {
            assertNotNull("every esplora input carries an outpoint", it.prev_out)
        }
        tx.out.forEach {
            assertTrue("every output carries a scriptpubkey entity key", !it.addr.isNullOrEmpty())
        }
    }

    @Test
    fun goldenExhibitAdaptsTheFlawExhibitExactly() {
        val raw = resource("tx_exhibit.json")
        val tx = EsploraTxAdapter.fromJson(raw)
        assertNotNull(tx)
        val t = tx!!
        assertEquals(
            "380709af4a0834b9d48eaec993618fca0ded2f9280b9299d41e7585e85ba8a93",
            t.hash,
        )
        assertEquals(2, t.inputs.size)
        assertEquals(11, t.out.size)
        assertEquals(
            listOf(0L, 5000L, 53539L) + List(8) { 106352L },
            t.out.map { it.value },
        )
        // esplora invariant, self-derived from the golden: the
        // input values the entropy compute needs must sum to
        // the output sum plus fees.
        val fees = JSONObject(raw).getLong("fees")
        val outSum = t.out.map { it.value }.sum()
        val inSum = t.inputs.map { it.prev_out!!.value }.sum()
        assertEquals(outSum + fees, inSum)
    }

    @Test
    fun unparseableBodyMapsToNull() {
        assertNull(EsploraTxAdapter.fromJson("{not json"))
    }

    @Test
    fun txidLessBodyMapsToNull() {
        assertNull(EsploraTxAdapter.fromJson("""{"inputs":[],"outputs":[]}"""))
    }

    @Test
    fun inputWithoutOutpointMapsToNullPrevOut() {
        val body = """
            {"txid":"aa","inputs":[{"n":0}],
             "outputs":[{"n":0,"value":1,"scriptpubkey":"spk","type":"op_return"}]}
        """.trimIndent()
        val tx = EsploraTxAdapter.fromJson(body)
        assertNotNull(tx)
        assertNull(tx!!.inputs[0].prev_out)
    }
}
