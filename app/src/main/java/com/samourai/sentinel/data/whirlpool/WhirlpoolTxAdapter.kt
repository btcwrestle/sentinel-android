package com.samourai.sentinel.data.whirlpool

import com.samourai.sentinel.data.Inputs
import com.samourai.sentinel.data.Out
import com.samourai.sentinel.data.Tx

/**
 * Tx (Dojo wallet-response shape) -> WhirlpoolTxView (issue #6, part 2).
 * Pure JVM.
 *
 * Storage facts this adapter encodes:
 *  - Room primary key is "txid-collectionId" (fetchFromServer appends
 *    the suffix); label refs must use the bare txid. Stripped via
 *    removeSuffix — collectionIds may themselves contain '-', so
 *    split() is unsafe here.
 *  - Dojo tags wallet IOs with xpub.path; account resolution is
 *    WhirlpoolDetector.accountFromPath. IOs without xpub carry a
 *    null account and are inert for Layer-1 membership.
 *  - The app Tx model carries no scriptpubkeys (part 1 tri-state
 *    note): script = null everywhere, OP_RETURN check skipped.
 */
object WhirlpoolTxAdapter {

    fun toView(
        tx: Tx,
        collectionId: String,
        accountOfXpub: Map<String, Long> = emptyMap(),
    ): WhirlpoolTxView =
        WhirlpoolTxView(
            txid = tx.hash.removeSuffix("-$collectionId"),
            inputs = tx.inputs.map { it.toIO(accountOfXpub) },
            outputs = tx.out.map { it.toIO(accountOfXpub) },
        )

    private fun Inputs.toIO(accountOfXpub: Map<String, Long>) = WhirlpoolTxView.IO(
        value = prev_out?.value ?: 0L,
        addr = prev_out?.addr,
        account = prev_out?.xpub?.m?.let(accountOfXpub::get)
            ?: prev_out?.xpub?.path?.let(WhirlpoolDetector::accountFromPath),
        script = null,
        spentTxid = prev_out?.txid,
        spentVout = prev_out?.vout,
    )

    private fun Out.toIO(accountOfXpub: Map<String, Long>) = WhirlpoolTxView.IO(
        value = value,
        addr = addr,
        account = xpub?.m?.let(accountOfXpub::get)
            ?: xpub?.path?.let(WhirlpoolDetector::accountFromPath),
        script = null,
        vout = n,
    )
}
