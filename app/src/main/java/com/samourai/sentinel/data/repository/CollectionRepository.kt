package com.samourai.sentinel.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.db.PayloadReadException
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.util.dataBaseScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.util.*
import kotlin.collections.ArrayList

class CollectionRepository {

    // #93-C: field sealed - census (post-#97, re-verified post-#151,
    // main+test) has zero external references; access goes through
    // collectionsSnapshot(), findById(), and the locked mutators below.
    // Sealing makes the #89 lock-jumping shape structurally impossible
    // in future code.
    private val pubKeyCollections: ArrayList<PubKeyCollection> = arrayListOf()
    val collectionsLiveData: MutableLiveData<ArrayList<PubKeyCollection>> = MutableLiveData()

    /**
     * Set when the on-disk payload exists but could not be decoded.
     *
     * While this is true the repository will REFUSE to persist, because the
     * in-memory list does not reflect reality and writing it would destroy the
     * user's collections.
     */
    private val readFailure: MutableLiveData<String?> = MutableLiveData(null)

    fun readFailure(): LiveData<String?> = readFailure

    @Volatile
    private var isPayloadUsable = false

    /**
     * Serializes every write to the single payload file.
     *
     * The previous `@Synchronized fun sync()` only guarded the *launching* of the
     * coroutine - the lock was released as soon as `launch` returned, so the
     * actual file writes still raced and could truncate each other.
     */
    private val writeMutex = Mutex()

    private val sentinelCollectionStore: SentinelCollectionStore by inject(SentinelCollectionStore::class.java)

    /**
     * #89: deep structural snapshot for callers that must iterate collections
     * without racing import/edit mutations. The outer list and every
     * collection's `pubs` list are fresh copies; the `PubKeyModel` elements
     * are shared, so field updates remain visible - this guarantees
     * structural stability, not field immutability.
     */
    fun collectionsSnapshot(): ArrayList<PubKeyCollection> =
        synchronized(pubKeyCollections) {
            pubKeyCollections.mapTo(ArrayList()) { it.copy(pubs = ArrayList(it.pubs)) }
        }

    fun addNew(pubKeyCollection: PubKeyCollection) {
        // #89: mutators now hold the same monitor the readers lock on. The
        // previous bare add/delete/update raced xpubVotes' synchronized read
        // and every bare external reader (CME on the backup-import path).
        synchronized(pubKeyCollections) {
            pubKeyCollection.id = UUID.randomUUID().toString()
            pubKeyCollections.add(pubKeyCollection)
        }
        this.sync()
    }

    fun delete(index: Int) {
        synchronized(pubKeyCollections) {
            if (index < 0 || index >= pubKeyCollections.size) return
            pubKeyCollections.removeAt(index)
        }
        this.sync()
    }

    fun update(pubKeyCollection: PubKeyCollection, index: Int) {
        synchronized(pubKeyCollections) {
            if (index < 0 || index >= pubKeyCollections.size) return
            pubKeyCollections[index] = pubKeyCollection
            pubKeyCollections[index].updateBalance()
        }
        this.sync()
    }


    fun findById(id: String): PubKeyCollection? {
        // #89: bare find could CME against a concurrent mutator. Returns the
        // LIVE object on purpose - callers (CollectionEdit) mutate it and
        // then persist via update(), which resolves by data-class equality.
        return synchronized(pubKeyCollections) {
            pubKeyCollections.find { it.id == id }
        }
    }

    /**
     * AWAITED persistence (#48): sync() launches its disk write on
     * dataBaseScope fire-and-forget, so N addNew() calls queue N writes
     * (list snapshots of 1, 2, ... N collections). A restart-triggered
     * read() can take writeMutex before those coroutines start and see a
     * partial list - the "imported 1 of 3, second attempt got all 3" bug.
     * This variant waits for the queued writes, then writes the complete
     * list inline, and rethrows write failures so callers report honestly.
     */
    suspend fun syncNow() {
        if (!isPayloadUsable) {
            Timber.w("syncNow() suppressed: payload is not in a known-good state")
            emit()
            return
        }
        val snapshot: ArrayList<PubKeyCollection>
        synchronized(pubKeyCollections) {
            val dupRemoved = pubKeyCollections.distinctBy { it.id }
            pubKeyCollections.clear()
            pubKeyCollections.addAll(dupRemoved)
            // #89: deep copy. Data-class copy() is shallow - the previous
            // snapshot only owned the outer list, so the IO writer could
            // still CME serializing a collection's pubs while import/edit
            // mutated it.
            snapshot = ArrayList(pubKeyCollections.map { it.copy(pubs = ArrayList(it.pubs)) })
        }
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                sentinelCollectionStore.getCollectionStore().write(snapshot)
            }
        }
        this.emit()
    }

    /**
     * write changes to the db
     * sync needs to be called after changing collection (edit,delete,add)
     */
    fun sync() {
        // Never persist on top of a payload we failed to read.
        if (!isPayloadUsable) {
            Timber.w("sync() suppressed: payload is not in a known-good state")
            emit()
            return
        }

        val snapshot: ArrayList<PubKeyCollection>
        synchronized(pubKeyCollections) {
            val dupRemoved = pubKeyCollections.distinctBy { it.id }
            pubKeyCollections.clear()
            pubKeyCollections.addAll(dupRemoved)
            // Hand the writer its own copy so it cannot observe concurrent
            // mutation from fetchFromServer while serializing.
            // #89: deep copy - `pubs` must be a fresh list too, or the claim
            // above only held for the outer list (shallow data-class copy).
            snapshot = ArrayList(pubKeyCollections.map { it.copy(pubs = ArrayList(it.pubs)) })
        }

        dataBaseScope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    sentinelCollectionStore.getCollectionStore().write(snapshot)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to persist collections")
                }
            }
        }
        this.emit()
    }

    /**
     * Loads collections from disk.
     *
     * Critically, this is NON-DESTRUCTIVE: the in-memory list is only replaced
     * once the payload has been successfully decoded. Previously the list was
     * cleared *before* the read was attempted, so any failure left an empty list
     * that the next sync() would happily write to disk.
     */
    suspend fun read() = withContext(Dispatchers.IO) {
        try {
            val readValue: ArrayList<PubKeyCollection>? = writeMutex.withLock {
                sentinelCollectionStore.getCollectionStore().readOrThrow<ArrayList<PubKeyCollection>>()
            }

            // Only mutate shared state after a successful decode.
            synchronized(pubKeyCollections) {
                pubKeyCollections.clear()
                if (readValue != null) {
                    pubKeyCollections.addAll(readValue.distinctBy { it.id })
                }
            }
            isPayloadUsable = true
            readFailure.postValue(null)
        } catch (e: PayloadReadException) {
            // Leave pubKeyCollections untouched and block writes so we do not
            // overwrite recoverable data.
            Timber.e(e, "Collections payload unreadable; writes are now blocked")
            isPayloadUsable = false
            readFailure.postValue(e.message)
            throw e
        }
        emit()
    }


    private fun emit() {
        // #89: deep copy. Observers receive structurally stable snapshots, so
        // inner pubs mutation during import can no longer CME a consumer
        // iterating what it was handed.
        val array = synchronized(pubKeyCollections) {
            pubKeyCollections.distinctBy { it.id }
                .mapTo(ArrayList()) { it.copy(pubs = ArrayList(it.pubs)) }
        }
        collectionsLiveData.postValue(array)
    }

    fun update(pubKeyCollection: PubKeyCollection) {
        // #89: contains/indexOf raced mutators between the two scans; resolve
        // the index under one lock acquisition instead.
        val index = synchronized(pubKeyCollections) {
            val idx = this.pubKeyCollections.indexOf(pubKeyCollection)
            if (idx == -1) null else idx
        }
        if (index != null) this.update(pubKeyCollection, index)
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        // #89: find/remove raced readers; mutation now under the monitor.
        synchronized(pubKeyCollections) {
            val item = pubKeyCollections.find { it.id == id }
            pubKeyCollections.remove(item)
        }
        sync()
    }

    /**
     * Deliberately clears all collections. Only for the explicit
     * "clear wallet" / "replace on import" flows.
     */
    fun reset() {
        synchronized(pubKeyCollections) {
            this.pubKeyCollections.clear()
        }
        // An intentional reset must be allowed to persist even if a prior read failed.
        isPayloadUsable = true
        this.sync()
    }

}
