package com.samourai.sentinel.data.whirlpool

import com.samourai.sentinel.data.whirlpool.WhirlpoolClassification.FirstMix
import com.samourai.sentinel.data.whirlpool.WhirlpoolClassification.Remix
import com.samourai.sentinel.data.whirlpool.WhirlpoolClassification.Tier
import com.samourai.sentinel.data.whirlpool.WhirlpoolClassification.Tx0
import com.samourai.sentinel.data.whirlpool.WhirlpoolClassification.Unknown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deterministic unit matrix over synthetic views. Real-tx fixtures
 * land in /whirlpool/sample.jsonl via scripts/fetch_whirlpool_fixtures.sh
 * — the final test fails fast until then (fixture-first: red now,
 * green in the same PR).
 */
class WhirlpoolDetectorTest {

    private val cfg = WhirlpoolConfig.DEFAULT

    private fun io(value: Long, account: Long? = null, script: String? = null) =
        WhirlpoolTxView.IO(value, null, account, script)

    private fun view(ins: List<WhirlpoolTxView.IO>, outs: List<WhirlpoolTxView.IO>) =
        WhirlpoolTxView("aa".repeat(32), ins, outs)

    // --- TX0 ---

    @Test
    fun tx0AccountsAloneClassifyAtL1() {
        val v = view(
            listOf(io(100_000_000, cfg.depositAccount)),
            listOf(
                io(2_500_000, cfg.premixAccount),
                io(2_500_000, cfg.premixAccount),
                io(2_499_999, cfg.premixAccount),   // uneven -> structure unconfirmed
                io(92_400_000, cfg.badbankAccount),
            )
        )
        assertEquals(Tx0(Tier.L1), WhirlpoolDetector.classify(v, cfg))
    }

    @Test
    fun tx0UniformPremixPlusOpReturnClassifiesAtL2() {
        val v = view(
            listOf(io(100_000_000, cfg.depositAccount)),
            listOf(
                io(2_500_000, cfg.premixAccount),
                io(2_500_000, cfg.premixAccount),
                io(2_500_000, cfg.premixAccount),
                io(92_400_000, cfg.badbankAccount),
                io(0, script = "6a04a1b2c3d4"),
            )
        )
        assertEquals(Tx0(Tier.L2), WhirlpoolDetector.classify(v, cfg))
    }

    @Test
    fun tx0WithoutOpReturnWhenScriptsSuppliedStaysAtL1() {
        val v = view(
            listOf(io(100_000_000, cfg.depositAccount)),
            listOf(
                io(2_500_000, cfg.premixAccount, script = "0014ab"),
                io(2_500_000, cfg.premixAccount, script = "0014cd"),
                io(92_400_000, cfg.badbankAccount, script = "0014ef"),
            )
        )
        assertEquals(Tx0(Tier.L1), WhirlpoolDetector.classify(v, cfg))
    }

    // --- Mixes ---

    @Test
    fun firstMixPremixInPostmixOutUniformClassifies() {
        val ins = (1..5).map { io(2_500_300, cfg.premixAccount) }
        val outs = (1..5).map { io(2_500_000, cfg.postmixAccount) }
        assertEquals(
            FirstMix(2_500_000L, Tier.L2),
            WhirlpoolDetector.classify(view(ins, outs), cfg)
        )
    }

    @Test
    fun remixPostmixInPostmixOutUniformClassifies() {
        val ins = (1..5).map { io(2_500_200, cfg.postmixAccount) }
        val outs = (1..5).map { io(2_500_000, cfg.postmixAccount) }
        assertEquals(
            Remix(2_500_000L, Tier.L2),
            WhirlpoolDetector.classify(view(ins, outs), cfg)
        )
    }

    // --- Guards: no label by construction ---

    @Test
    fun foreignUniformRoundIsUnknownByConstruction() {
        // Wasabi/JoinMarket shape: perfect 5/5 uniform, zero accounts.
        val ins = (1..5).map { io(2_500_300) }
        val outs = (1..5).map { io(2_500_000) }
        assertEquals(Unknown, WhirlpoolDetector.classify(view(ins, outs), cfg))
    }

    @Test
    fun arbitraryPremixSpendIsUnknown() {
        val v = view(
            listOf(io(2_500_000, cfg.premixAccount)),
            listOf(io(2_490_000)),
        )
        assertEquals(Unknown, WhirlpoolDetector.classify(v, cfg))
    }

    @Test
    fun postmixConsolidationIsUnknown() {
        val ins = (1..3).map { io(2_500_000, cfg.postmixAccount) }
        val outs = listOf(io(7_490_000, cfg.postmixAccount))
        assertEquals(Unknown, WhirlpoolDetector.classify(view(ins, outs), cfg))
    }

    @Test
    fun uniformRoundOutsideConfiguredDenominationsIsUnknown() {
        val ins = (1..5).map { io(3_000_300, cfg.premixAccount) }
        val outs = (1..5).map { io(3_000_000, cfg.postmixAccount) }
        assertEquals(Unknown, WhirlpoolDetector.classify(view(ins, outs), cfg))
    }

    // --- Path parsing ---

    @Test
    fun accountIndicesFromPaths() {
        // SamouraiAccountIndex.java: PREMIX = Int.MAX_VALUE - 2
        val premixIdx = (Int.MAX_VALUE - 2).toLong()
        assertEquals(
            premixIdx,
            WhirlpoolDetector.accountFromPath("m/84'/0'/${Int.MAX_VALUE - 2}'/0/7")
        )
        assertEquals(0L, WhirlpoolDetector.accountFromPath("m/84'/0'/0'/0/0"))
        assertNull(WhirlpoolDetector.accountFromPath("m/84'/0'"))
        assertNull(WhirlpoolDetector.accountFromPath("nonsense"))
    }

    @Test
    fun firstMixWithRemixerAndPremixInputsClassifies() {
        // Observed shape (076a1dcc, 6fd8e282): inputs mix buffered
        // premix UTXOs (two different buffers observed) with exact-
        // denomination remixer UTXOs; outputs all exact denomination.
        // Sparse anchor: only the wallet's IOs carry accounts.
        val premixIdx = (Int.MAX_VALUE - 2).toLong()
        val postmixIdx = (Int.MAX_VALUE - 1).toLong()
        val ins = listOf(
            io(2_500_907, premixIdx),
            io(2_500_000), io(2_500_000), io(2_500_000), io(2_500_605),
        )
        val outs = listOf(
            io(2_500_000, postmixIdx),
            io(2_500_000), io(2_500_000), io(2_500_000), io(2_500_000),
        )
        assertEquals(
            FirstMix(2_500_000L, Tier.L2),
            WhirlpoolDetector.classify(view(ins, outs), cfg)
        )
    }

    // --- Real-tx fixtures (fixture-first gate) ---

    @Test
    fun sampleFixtureMatrixPasses() {
        val stream = javaClass.getResourceAsStream("/whirlpool/sample.jsonl")
        if (stream == null) {
            org.junit.Assert.fail(
                "sample.jsonl missing — populate the txid manifest and run " +
                "scripts/fetch_whirlpool_fixtures.sh"
            )
        }
        stream!!.bufferedReader().readLines().filter { it.isNotBlank() }
            .forEach { raw ->
                val obj = org.json.JSONObject(raw)
                val txid = obj.getString("txid")
                val accountsMap = obj.optJSONObject("accounts") ?: org.json.JSONObject()
                val roleIndex = mapOf(
                    "deposit" to cfg.depositAccount,
                    "premix" to cfg.premixAccount,
                    "postmix" to cfg.postmixAccount,
                    "badbank" to cfg.badbankAccount,
                )
                val acct: (String?) -> Long? = { addr ->
                    if (addr != null && accountsMap.has(addr)) {
                        val role = accountsMap.getString(addr)
                        roleIndex[role]
                            ?: throw IllegalStateException("txid $txid: unknown account role '$role'")
                    } else null
                }
                val ins = (0 until obj.getJSONArray("vin").length()).map { i ->
                    val prev = obj.getJSONArray("vin").getJSONObject(i).optJSONObject("prevout")
                    val addr = prev?.optString("scriptpubkey_address", null as String?)
                    WhirlpoolTxView.IO(
                        value = prev?.optLong("value") ?: 0L,
                        addr = addr,
                        account = acct(addr),
                        script = prev?.optString("scriptpubkey"),
                    )
                }
                val outs = (0 until obj.getJSONArray("vout").length()).map { i ->
                    val o = obj.getJSONArray("vout").getJSONObject(i)
                    val addr = o.optString("scriptpubkey_address", null as String?)
                    WhirlpoolTxView.IO(
                        value = o.getLong("value"),
                        addr = addr,
                        account = acct(addr),
                        script = o.optString("scriptpubkey"),
                    )
                }
                val result = WhirlpoolDetector.classify(WhirlpoolTxView(txid, ins, outs), cfg)
                when (obj.getString("expect")) {
                    "tx0" -> assertEquals("txid $txid", Tx0(Tier.L2), result)
                    "first_mix" -> assertEquals(
                        "txid $txid", FirstMix(outs[0].value, Tier.L2), result)
                    "remix" -> assertEquals(
                        "txid $txid", Remix(outs[0].value, Tier.L2), result)
                    "none" -> assertEquals("txid $txid", Unknown, result)
                    else -> org.junit.Assert.fail("txid $txid: unknown expect tag")
                }
            }
    }
}
