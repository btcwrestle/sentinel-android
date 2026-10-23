package com.samourai.sentinel.data.db.dao

import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.db.SentinelRoomDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * #201: TxDao.upsertCollectionTx must land a whole sync round as ONE
 * transaction - one commit, one LiveData emission, never an empty
 * intermediate state (the tx-side port of the saveUtxos reconcile).
 *
 * Determinism: query AND transaction executors are direct (run inline
 * on the calling thread), so every Room hop runs synchronously; only
 * the final LiveData setValue posts to the main looper, drained by
 * shadowOf(mainLooper).idle(). The quiescence loop covers any hop
 * Room keeps on a real thread. Caveat, named: LiveData.postValue can
 * coalesce back-to-back deliveries, so the count leg is a one-way
 * gate (passes only for a single commit; the authoritative
 * emission evidence is the on-device logcat leg of #201). Tests that
 * COUNT emissions therefore drain between writes - this suite's own
 * first red (2 deliveries where 3 were posted) is the why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TxDaoReconcileTest {

    private lateinit var db: SentinelRoomDb
    private lateinit var dao: TxDao
    private lateinit var liveData: LiveData<List<Tx>>

    private val collection = "c1"
    private val otherCollection = "c2"
    private val pubKey = "pub"

    private val emissions = mutableListOf<List<Tx>>()
    private val observer = Observer<List<Tx>> { emissions.add(it) }

    private val direct = Executor { it.run() }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SentinelRoomDb::class.java
        )
            .allowMainThreadQueries()
            .setQueryExecutor(direct)
            .setTransactionExecutor(direct)
            .build()
        dao = db.txDao()
        liveData = dao.getAllTx(collection)
        liveData.observeForever(observer)
        drainMain()
        emissions.clear()   // drop the initial empty emission
    }

    @After
    fun tearDown() {
        liveData.removeObserver(observer)
        db.close()
    }

    private fun drainMain() {
        val shadow = shadowOf(Looper.getMainLooper())
        var lastCount = -1
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadow.idle()
            shadow.idle()
            if (emissions.size == lastCount) {
                Thread.sleep(50)
                shadow.idle()
                if (emissions.size == lastCount) return
            }
            lastCount = emissions.size
        }
    }

    private fun tx(
        hash: String,
        confirmations: Long = 6,
        time: Long = 1000L,
        collectionId: String = collection,
        associatedPubKey: String = pubKey
    ): Tx = Tx(
        hash = hash,
        time = time,
        version = 1,
        locktime = 0,
        result = 100L,
        inputs = emptyList(),
        out = emptyList(),
        block_height = 700000L,
        confirmations = confirmations,
        associatedPubKey = associatedPubKey,
        collectionId = collectionId
    )

    @Test
    fun reconcileRefreshesReplacesVanishesInOneEmission() = runBlocking {
        dao.upsertCollectionTx(collection, listOf(tx("txa"), tx("txb"), tx("txc")))
        drainMain()
        assertEquals(1, emissions.size)
        assertEquals(3, emissions[0].size)

        val changed = tx("txa", confirmations = 99, time = 2000L)
        val unchanged = tx("txb")
        val fresh = tx("txd", confirmations = 1)
        dao.upsertCollectionTx(collection, listOf(changed, unchanged, fresh))
        drainMain()

        // ONE emission for the whole round: a wipe would emit an empty
        // list first; a non-transactional insert+delete pair would emit
        // the intermediate post-insert state (4 rows, txc still present).
        assertEquals(2, emissions.size)
        val final = emissions[1]
        assertEquals(3, final.size)
        assertTrue(final.none { it.hash == "txc" })
        assertEquals(99L, final.first { it.hash == "txa" }.confirmations)
        assertTrue(final.any { it.hash == "txd" })
        emissions.forEach { assertTrue(it.isNotEmpty()) }
    }

    @Test
    fun reconcileNeverEmitsEmptyForNonEmptyWallet() = runBlocking {
        dao.upsertCollectionTx(collection, listOf(tx("txa")))
        drainMain()
        dao.upsertCollectionTx(collection, listOf(tx("txa"), tx("txb")))
        drainMain()
        assertEquals(2, emissions.size)
        assertEquals(2, emissions[1].size)
        emissions.forEach { assertTrue(it.isNotEmpty()) }
    }

    @Test
    fun pruneIsScopedToTheSyncingCollection() = runBlocking {
        dao.upsertCollectionTx(collection, listOf(tx("txa"), tx("txb")))
        dao.upsertCollectionTx(otherCollection, listOf(tx("txo", collectionId = otherCollection)))
        drainMain()

        dao.upsertCollectionTx(collection, listOf(tx("txa")))
        drainMain()

        assertEquals(1, dao.getAssociated(collection, pubKey).size)
        assertEquals(1, dao.getAssociated(otherCollection, pubKey).size)
    }

    @Test
    fun emptyFetchWipesOnlyItsCollection() = runBlocking {
        dao.upsertCollectionTx(collection, listOf(tx("txa")))
        drainMain()
        dao.upsertCollectionTx(otherCollection, listOf(tx("txo", collectionId = otherCollection)))
        drainMain()

        dao.upsertCollectionTx(collection, emptyList())
        drainMain()

        assertTrue(dao.getAssociated(collection, pubKey).isEmpty())
        assertEquals(1, dao.getAssociated(otherCollection, pubKey).size)
        // seed c1 -> 1 emission; seed c2 -> 1 (any write to the
        // transactions table re-emits, even other collections' rows);
        // wipe c1 -> 1 (the empty one). Draining BETWEEN writes keeps
        // postValue from coalescing deliveries - the trap that red-ran
        // this leg first time out (2 emissions, second [txa] swallowed).
        assertEquals(3, emissions.size)
        assertEquals(0, emissions.last().size)
    }

    @Test
    fun replaceRefreshesConfirmationsInPlace() = runBlocking {
        dao.upsertCollectionTx(collection, listOf(tx("txa", confirmations = 3)))
        dao.upsertCollectionTx(collection, listOf(tx("txa", confirmations = 42)))
        val stored = dao.getAssociated(collection, pubKey)
        assertEquals(1, stored.size)
        assertEquals(42L, stored[0].confirmations)
    }

    /**
     * #284: the keychain list reads these LiveDatas (newest first), not
     * a Paging DataSource. A pubkey tab must not leak a sibling key.
     */
    @Test
    fun keychainListQueriesSeeReconcileNewestFirst() = runBlocking {
        val all = dao.observeCollectionTx(collection)
        val allGot = mutableListOf<List<Tx>>()
        val allObserver = Observer<List<Tx>> { allGot.add(it) }
        val one = dao.observeTxForPubKey(collection, pubKey)
        val oneGot = mutableListOf<List<Tx>>()
        val oneObserver = Observer<List<Tx>> { oneGot.add(it) }
        val many = dao.observeTxForPubKeys(collection, listOf(pubKey, "other"))
        val manyGot = mutableListOf<List<Tx>>()
        val manyObserver = Observer<List<Tx>> { manyGot.add(it) }
        all.observeForever(allObserver)
        one.observeForever(oneObserver)
        many.observeForever(manyObserver)
        try {
            drainMain()
            allGot.clear()
            oneGot.clear()
            manyGot.clear()
            dao.upsertCollectionTx(
                collection,
                listOf(
                    tx("old", time = 1L),
                    tx("new", time = 9L),
                    tx("sib", time = 8L, associatedPubKey = "other")
                )
            )
            drainMain()
            assertEquals(listOf("new", "sib", "old"), allGot.last().map { it.hash })
            assertEquals(listOf("new", "old"), oneGot.last().map { it.hash })
            assertEquals(listOf("new", "sib", "old"), manyGot.last().map { it.hash })
        } finally {
            all.removeObserver(allObserver)
            one.removeObserver(oneObserver)
            many.removeObserver(manyObserver)
        }
    }
}
