package com.samourai.sentinel.ui.collectionDetails.transactions

import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * #204: the pager-rebuild guard. TransactionsFragment now rebuilds its tab
 * pager ONLY when pubsFingerprint(collection) changes; every other
 * collectionsLiveData delivery (one per pubkey per sync round, plus
 * websocket background refetches) just swaps the live collection pointer.
 * These legs pin the fingerprint's decision boundary - the exact property
 * the screen-half of #204 depends on. The rebuild path itself (detach ->
 * adapter swap -> currentItem restore -> attach) is verified on-device;
 * per house rules the closing evidence for #204 is the panther capture.
 *
 * Plain JVM, no Robolectric: the legs construct data classes and call a
 * pure string function. PubKeyModel is @Parcelize - loading its generated
 * CREATOR only needs the Parcelable interface on the classpath, nothing
 * here invokes android code.
 *
 * Fingerprint contract (the exclusions ARE the fix):
 * - IN: pubKey, label, order, isImportFromWallet - they shape the tab
 *   strip (count, order, titles, and the import-from-wallet tab set).
 * - OUT: balance, lastRefreshed, account_index, change_index - they
 *   mutate on EVERY sync emission (that per-emission mutation is the
 *   #204 trigger) and none of them alter the tab strip.
 * - Records are separated by \u0000, fields by a space. Labels are free
 *   user text and MAY contain spaces; the record separator keeps labels
 *   from colliding across records. A false-equal would additionally
 *   require a space inside a pubKey, which xpub/address validation
 *   makes impossible on import.
 */
class PubsFingerprintTest {

    private fun pub(pubKey: String, label: String): PubKeyModel =
        PubKeyModel(pubKey = pubKey, label = label, type = AddressTypes.BIP84)

    private fun collection(
        pubs: List<PubKeyModel>,
        importFromWallet: Boolean = false
    ): PubKeyCollection = PubKeyCollection(
        collectionLabel = "c",
        id = "id",
        pubs = ArrayList(pubs),
        isImportFromWallet = importFromWallet
    )

    @Test
    fun balanceOnlyRefreshKeepsFingerprint() {
        val before = collection(listOf(pub("xpubA", "Deposit"), pub("xpubB", "Postmix")))
        val after = before.copy(
            balance = 987654321L,
            lastRefreshed = 20261018L,
            pubs = ArrayList(before.pubs.mapIndexed { i, p ->
                p.copy(balance = 1000L * (i + 1))
            })
        )
        assertEquals(
            TransactionsFragment.pubsFingerprint(before),
            TransactionsFragment.pubsFingerprint(after)
        )
    }

    @Test
    fun perPubIndexBumpsKeepFingerprint() {
        val before = collection(listOf(pub("xpubA", "Deposit"), pub("xpubB", "Postmix")))
        val after = before.copy(
            pubs = ArrayList(before.pubs.map { p ->
                p.copy(account_index = p.account_index + 1, change_index = p.change_index + 2)
            })
        )
        assertEquals(
            TransactionsFragment.pubsFingerprint(before),
            TransactionsFragment.pubsFingerprint(after)
        )
    }

    @Test
    fun addedPubKeyChangesFingerprint() {
        val before = collection(listOf(pub("xpubA", "Deposit")))
        val after = collection(listOf(pub("xpubA", "Deposit"), pub("xpubB", "Postmix")))
        assertNotEquals(
            TransactionsFragment.pubsFingerprint(before),
            TransactionsFragment.pubsFingerprint(after)
        )
    }

    @Test
    fun removedPubKeyChangesFingerprint() {
        val before = collection(listOf(pub("xpubA", "Deposit"), pub("xpubB", "Postmix")))
        val after = collection(listOf(pub("xpubA", "Deposit")))
        assertNotEquals(
            TransactionsFragment.pubsFingerprint(before),
            TransactionsFragment.pubsFingerprint(after)
        )
    }

    @Test
    fun relabeledPubKeyChangesFingerprint() {
        val before = collection(listOf(pub("xpubA", "Deposit")))
        val after = collection(listOf(pub("xpubA", "Renamed deposit")))
        assertNotEquals(
            TransactionsFragment.pubsFingerprint(before),
            TransactionsFragment.pubsFingerprint(after)
        )
    }

    @Test
    fun reorderedPubsChangeFingerprint() {
        val a = collection(listOf(pub("xpubA", "Deposit"), pub("xpubB", "Postmix")))
        val b = collection(listOf(pub("xpubB", "Postmix"), pub("xpubA", "Deposit")))
        assertNotEquals(
            TransactionsFragment.pubsFingerprint(a),
            TransactionsFragment.pubsFingerprint(b)
        )
    }

    @Test
    fun importFlagFlipChangesFingerprint() {
        val a = collection(listOf(pub("xpubA", "Deposit")), importFromWallet = false)
        val b = collection(listOf(pub("xpubA", "Deposit")), importFromWallet = true)
        assertNotEquals(
            TransactionsFragment.pubsFingerprint(a),
            TransactionsFragment.pubsFingerprint(b)
        )
    }

    @Test
    fun labelsWithSpacesStayDistinctAcrossRecords() {
        val a = collection(listOf(pub("xpubA", "alpha beta"), pub("xpubB", "gamma")))
        val b = collection(listOf(pub("xpubA", "alpha"), pub("xpubB", "beta gamma")))
        assertNotEquals(
            TransactionsFragment.pubsFingerprint(a),
            TransactionsFragment.pubsFingerprint(b)
        )
    }
}
