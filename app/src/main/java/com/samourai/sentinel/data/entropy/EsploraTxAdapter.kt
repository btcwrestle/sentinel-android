package com.samourai.sentinel.data.entropy

import com.samourai.sentinel.data.Inputs
import com.samourai.sentinel.data.Out
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.prevOut
import org.json.JSONArray
import org.json.JSONObject

/**
 * Esplora-shaped /tx JSON -> app Tx, for the #72 entropy
 * fetcher only (ApiService.getTx -> this adapter ->
 * TxEntropyIngest's fetcher). The wallet payload path
 * (getWallet -> WalletResponse) is untouched, and fetchFee
 * keeps its own flat-key parse.
 *
 * Payload facts (on-device census, #72):
 *  - dojo's /tx/{txid}?fees=1 returns esplora-shaped JSON:
 *    txid, version, locktime, created, block, confirmations,
 *    fees, size, vsize, feerate, vfeerate, inputs, outputs;
 *  - inputs are {n, outpoint, seq, sig, witness} - NO
 *    address; outpoint is {txid, vout, value, scriptpubkey}
 *    - the per-input value the entropy compute needs lives
 *    there;
 *  - outputs are {n, scriptpubkey, type, value} with an
 *    OPTIONAL address key (two key variants observed).
 *
 * Entity key: scriptpubkey, both sides, in the addr slot.
 * Not a preference - the payload forces it (inputs carry no
 * address at all), and for standard script types it encodes
 * the same entity an address does. Same script = one entity,
 * preserving the service's honest same-entity merge
 * semantics through analyze(Tx) unchanged.
 *
 * Tolerance: a body that is not JSON, or lacks a txid, maps
 * to null - the ingest's fetch-failure arm then writes no
 * row and re-attempts next sync (the InsufficientData
 * convention extended). An input without an outpoint maps to
 * prev_out = null - analyze reports InsufficientData for
 * that tx, same convention.
 */
object EsploraTxAdapter {

    fun fromJson(body: String): Tx? {
        return try {
            val root = JSONObject(body)
            val txid = root.optString("txid", "")
            if (txid.isEmpty()) return null

            val inputsArr = root.optJSONArray("inputs") ?: JSONArray()
            val inputs = (0 until inputsArr.length()).map { i ->
                val el = inputsArr.getJSONObject(i)
                val outpoint = el.optJSONObject("outpoint")
                Inputs(
                    vin = el.optInt("n", i),
                    sequence = if (el.has("seq")) el.optLong("seq") else null,
                    prev_out = outpoint?.let { op ->
                        prevOut(
                            addr = op.optString("scriptpubkey", ""),
                            txid = op.optString("txid", ""),
                            value = op.optLong("value", 0L),
                            vout = op.optInt("vout", 0),
                            xpub = null,
                        )
                    },
                )
            }

            val outsArr = root.optJSONArray("outputs") ?: JSONArray()
            val out = (0 until outsArr.length()).map { i ->
                val el = outsArr.getJSONObject(i)
                Out(
                    n = el.optInt("n", i),
                    value = el.optLong("value", 0L),
                    addr = el.optString("scriptpubkey", ""),
                    xpub = null,
                )
            }

            Tx(
                hash = txid,
                time = root.optLong("created", 0L),
                version = root.optInt("version", 0),
                locktime = root.optInt("locktime", 0),
                result = null,
                inputs = inputs,
                out = out,
                block_height = if (root.has("block") && !root.isNull("block"))
                    root.optLong("block")
                else null,
                confirmations = root.optLong("confirmations", 0L),
            )
        } catch (e: Exception) {
            null
        }
    }
}
