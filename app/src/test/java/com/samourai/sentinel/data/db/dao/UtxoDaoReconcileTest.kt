package com.samourai.sentinel.data.db.dao

import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.data.Utxo
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
 * #202: UtxoDao.upsertCollectionUtxos must land a whole sync round as
 * ONE transaction - one commit, one LiveData emission, never an empty
 * intermediate state (the utxo-side twin of TxDaoReconcileTest/#201).
 *
 * Determinism: query AND transaction executors are direct (run inline
 * on the calling thread), so every Room hop runs synchronously; only
 * the final LiveData setValue posts to the main looper, drained by
 * shadowOf(mainLooper).idle(). The quiescence loop covers any hop
 * Room keeps on a real thread. Caveat, named: LiveData.postValue can
 * coalesce back-to-back deliveries, so the count leg is a one-way
 * gate (passes only for a single commit; the authoritative emission
 * evidence is the on-device logcat leg of #202). Tests that COUNT
 * emissions therefore drain between writes - the discipline ported
 * from TxDaoReconcileTest, whose first red was exactly this trap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UtxoDaoReconcileTest {

    private lateinit var db: SentinelRoomDb
    private lateinit var dao: UtxoDao
    private lateinit var liveData: LiveData<List<Utxo>>

    private val collection = "c1"
    private val otherCollection = "c2"
    private val pubKey = "pub"

    private val emissions = mutableListOf<List<Utxo>>()
    private val observer = Observer<List<Utxo>> { emissions.add(it) }

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
        dao = db.utxoDao()
        liveData = dao.getUTXObyCollection(collection)
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

    // idx is the PK: production builds it as "txHash:outputN"
    // (fetchFromServer/fetchUTXOS) - the factory mirrors that.
    private fun utxo(
        hash: String,
        outputN: Int = 0,
        confirmations: Int = 6,
        value: Long = 100L,
        collectionId: String = collection
    ): Utxo = Utxo(
        idx = "$hash:$outputN",
        txHash = hash,
        txOutputN = outputN,
        confirmations = confirmations,
        value = value,
        pubKey = pubKey,
        collectionId = collectionId
    )

    @Test
    fun reconcileRefreshesReplacesVanishesInOneEmission() = runBlocking {
        dao.upsertCollectionUtxos(collection, listOf(utxo("ua"), utxo("ub"), utxo("uc")))
        drainMain()
        assertEquals(1, emissions.size)
        assertEquals(3, emissions[0].size)

        val changed = utxo("ua", confirmations = 99, value = 200L)
        val unchanged = utxo("ub")
        val fresh = utxo("ud", confirmations = 1)
        dao.upsertCollectionUtxos(collection, listOf(changed, unchanged, fresh))
        drainMain()

        // ONE emission for the whole round: a wipe would emit an empty
        // list first; a non-transactional insert+delete pair would emit
        // the intermediate post-insert state (4 rows, uc still present).
        assertEquals(2, emissions.size)
        val final = emissions[1]
        assertEquals(3, final.size)
        assertTrue(final.none { it.txHash == "uc" })
        assertEquals(99, final.first { it.txHash == "ua" }.confirmations)
        assertTrue(final.any { it.txHash == "ud" })
        emissions.forEach { assertTrue(it.isNotEmpty()) }
    }

    @Test
    fun reconcileNeverEmitsEmptyForNonEmptyWallet() = runBlocking {
        dao.upsertCollectionUtxos(collection, listOf(utxo("ua")))
        drainMain()
        dao.upsertCollectionUtxos(collection, listOf(utxo("ua"), utxo("ub")))
        drainMain()
        assertEquals(2, emissions.size)
        assertEquals(2, emissions[1].size)
        emissions.forEach { assertTrue(it.isNotEmpty()) }
    }

    @Test
    fun pruneIsScopedToTheSyncingCollection() = runBlocking {
        dao.upsertCollectionUtxos(collection, listOf(utxo("ua"), utxo("ub")))
        dao.upsertCollectionUtxos(otherCollection, listOf(utxo("uo", collectionId = otherCollection)))
        drainMain()

        dao.upsertCollectionUtxos(collection, listOf(utxo("ua")))
        drainMain()

        assertEquals(1, dao.getUTXObyCollectionAsList(collection).size)
        assertEquals(1, dao.getUTXObyCollectionAsList(otherCollection).size)
    }

    @Test
    fun emptyFetchWipesOnlyItsCollection() = runBlocking {
        dao.upsertCollectionUtxos(collection, listOf(utxo("ua")))
        drainMain()
        dao.upsertCollectionUtxos(otherCollection, listOf(utxo("uo", collectionId = otherCollection)))
        drainMain()

        dao.upsertCollectionUtxos(collection, emptyList())
        drainMain()

        assertTrue(dao.getUTXObyCollectionAsList(collection).isEmpty())
        assertEquals(1, dao.getUTXObyCollectionAsList(otherCollection).size)
        // seed c1 -> 1 emission; seed c2 -> 1 (any write to the
        // utxos table re-emits, even other collections' rows);
        // wipe c1 -> 1 (the empty one). Draining BETWEEN writes keeps
        // postValue from coalescing deliveries - the trap that
        // red-ran the TxDaoReconcileTest leg first time out.
        assertEquals(3, emissions.size)
        assertEquals(0, emissions.last().size)
    }

    @Test
    fun replaceRefreshesConfirmationsInPlace() = runBlocking {
        dao.upsertCollectionUtxos(collection, listOf(utxo("ua", confirmations = 3)))
        dao.upsertCollectionUtxos(collection, listOf(utxo("ua", confirmations = 42)))
        val stored = dao.getUTXObyCollectionAsList(collection)
        assertEquals(1, stored.size)
        assertEquals(42, stored[0].confirmations)
    }
}
