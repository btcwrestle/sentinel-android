package com.samourai.sentinel.data.repository

import androidx.lifecycle.LiveData
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.api.ApiService.ApiNotConfigured
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.WalletResponse
import com.samourai.sentinel.data.db.dao.TxDao
import com.samourai.sentinel.data.db.dao.TxEntropyDao
import com.samourai.sentinel.data.db.dao.UtxoDao
import com.samourai.sentinel.data.whirlpool.WhirlpoolAutoWriter
import com.samourai.sentinel.data.db.entity.TxEntropy
import com.samourai.sentinel.data.entropy.ENTROPY_FEATURE_ENABLED
import com.samourai.sentinel.data.entropy.EsploraTxAdapter
import com.samourai.sentinel.data.entropy.TxEntropyIngest
import com.samourai.sentinel.data.whirlpool.WhirlpoolBackfill
import com.samourai.sentinel.data.whirlpool.BadbankLabeler
import com.samourai.sentinel.data.whirlpool.WhirlpoolTxAdapter
import com.samourai.sentinel.data.whirlpool.WhirlpoolTxView
import com.samourai.sentinel.data.whirlpool.WhirlpoolLabelSink
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.ui.utils.logThreadInfo
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.wallet.util.XPUB
import com.samourai.sentinel.util.UtxoMetaUtil
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

/**
 * sentinel-android
 *
 */


/**
 * Repository class for handling transaction related to a specific collection
 * Each collection needs to maintain its own repo for handling tx's
 * Tx data will be saved in a separate file
 * transactions that belongs to collection will be stored in a separate file
 */
class TransactionsRepository {

    private val txDao: TxDao by inject(TxDao::class.java)
    private val utxoDao: UtxoDao by inject(UtxoDao::class.java)
    private val apiService: ApiService by inject(ApiService::class.java)
    private val collectionRepository: CollectionRepository by inject(CollectionRepository::class.java)
    private val feeRepository: FeeRepository by inject(FeeRepository::class.java)

    private val labelRepository: LabelRepository by inject(LabelRepository::class.java)
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private val whirlpoolBackfill by lazy {
        WhirlpoolBackfill(WhirlpoolAutoWriter(WhirlpoolLabelSink(labelRepository)))
    }
    private val badbankLabeler by lazy {
        BadbankLabeler(WhirlpoolLabelSink(labelRepository))
    }
    private val txEntropyDao: TxEntropyDao by inject(TxEntropyDao::class.java)
    private val txEntropyIngest by lazy {
        TxEntropyIngest(
            cache = object : TxEntropyIngest.TxEntropyCache {
                override suspend fun findByTxid(txid: String) = txEntropyDao.findByTxid(txid)
                override suspend fun insert(entry: TxEntropy) = txEntropyDao.insert(entry)
            },
            fetcher = { txid ->
                val response = apiService.getTx(txid)
                if (response.isSuccessful) {
                    response.body?.string()?.let { EsploraTxAdapter.fromJson(it) }
                } else {
                    null
                }
            },
        )
    }
    // NOTE: the old `loading: MutableLiveData<MutableList<Boolean>>` counter was
    // removed. Callers now observe HomeViewModel.syncState(), which cannot leak a
    // permanent "loading" entry. See com.samourai.sentinel.core.SyncState.

    //track currently loading collection
    var loadingCollectionId = ""

    fun getTransactionsLiveData(collectionId: String): LiveData<List<Tx>> {
        return txDao.getAllTx(collectionId)
    }

    /**
     *  Api call for wallet api
     *  using coroutines concurrent execution
     */
    // Wrapped in withContext(Dispatchers.IO) so Room is never touched on the main
    // thread regardless of which dispatcher the caller used.
    suspend fun fetchFromServer(collectionId: String): Unit = withContext(Dispatchers.IO) {
        try {
            logThreadInfo("fetchFromServer")
            val collection = collectionRepository.findById(collectionId)
                ?: return@withContext
            var newTransactions: ArrayList<Tx> = arrayListOf();
            val utxos: ArrayList<Utxo> = arrayListOf();
            val jobs: ArrayList<Deferred<Response>> = arrayListOf()
            collection.pubs.forEach {
                val item = apiScope.async {
                    apiService.getWallet(it.pubKey)
                }
                jobs.add(item);
            }

            jobs.forEach { job ->
                val index = jobs.indexOf(job)
                loadingCollectionId = collectionId
                try {
                    val res = jobs[index].await()
                    res.body?.let { it ->
                        val resString = it.string()
                        val pubKeyAssociated = collection.pubs[index]
                        val jsonObject = JSONObject(resString)
                        if (jsonObject.has("status")) {
                            if (jsonObject.getString("status") == "error") {
                                throw CancellationException("Invalid token")
                            }
                        }
                        val response: WalletResponse = fromJSON<WalletResponse>(resString)!!
                        Timber.i("fetchFromServer: ${response.info.fees}")
                        response.info.fees?.let { it1 -> feeRepository.parse(it1) }
                        SentinelState.blockHeight = response.info.latest_block
                        var latestBlockHeight = 1L
                        if (SentinelState.blockHeight != null)
                            latestBlockHeight = SentinelState.blockHeight?.height!!
                        val items = response.txs.map { tx ->
                            tx.hash = tx.hash + "-" + collectionId
                            tx.associatedPubKey = pubKeyAssociated.pubKey
                            val txBlockHeight = tx.block_height ?: 0
                            if (tx.block_height != null)
                                txDao.updateTxConfirmations(tx.hash, latestBlockHeight.minus(tx.block_height).plus(1))
                            tx.collectionId = collectionId
                            tx.confirmations =
                                if (latestBlockHeight > 0L && txBlockHeight > 0L)
                                        (latestBlockHeight.minus(txBlockHeight)) + 1
                                else 0
                            tx
                        }
                        response.unspent_outputs?.let {
                            val list = response.unspent_outputs.toMutableList().map {
                                it.pubKey = pubKeyAssociated.pubKey
                                it.idx = "${it.txHash}:${it.txOutputN}"
                                it.collectionId = collectionId
                                if (it.xpub != null) {
                                    it.path = it.xpub?.path!!
                                }
                                it
                            }.toList()
                            utxos.addAll(list)
                        }
                        collection.pubs[index].balance = response.wallet.final_balance
                        if (collection.pubs[index].type != AddressTypes.ADDRESS) {
                            val address =
                                response.addresses?.find { address -> address.address == collection.pubs[index].pubKey }
                            if (address != null) {
                                address.accountIndex?.let { accIndex ->
                                    collection.pubs[index].account_index = accIndex
                                }
                                address.changeIndex?.let { changeIndex ->
                                    collection.pubs[index].change_index = changeIndex
                                }
                            }
                        }
                        newTransactions.addAll(items)
                        collection.updateBalance()
                        collection.lastRefreshed = System.currentTimeMillis()
                        // #93 phase A: one hoisted snapshot serves find and
                        // indexOf - two live reads of the mutable field can
                        // disagree mid-flight (a concurrent remove -> indexOf -1).
                        val collectionsSnapshot = collectionRepository.collectionsSnapshot()
                        val item =
                            collectionsSnapshot.find { collection -> collection.id == collectionId }
                                ?: return@withContext
                        collectionRepository.update(
                            collection,
                            collectionsSnapshot.indexOf(item)
                        )
                    }
                } catch (e: Exception) {
                    throw  e
                }
            }
            withContext(Dispatchers.IO) {
                utxoDao.deleteByCollection(collectionId)
                txDao.deleteByCollectionID(collectionId)
            }

            // Whirlpool auto-labels (#6 part 2): runs BEFORE the dup-hash
            // renaming below (hashes are still uniform txid-collectionId
            // here, so suffix stripping is unambiguous) and reads the
            // in-memory list, so it cannot race saveTx. Labels must never
            // break sync: failures are contained and logged.
            if (prefsUtil.whirlpoolAutoLabels == true) {
                runCatching {
                    val accountOfXpub = buildMap<String, Long> {
                        // Account from the key itself (UI-proven XPUB
                        // formula), registered under every serialization:
                        // Dojo's echoed `m` may differ from the stored form.
                        collection.pubs.forEach { pub ->
                            runCatching {
                                val x = XPUB(pub.pubKey)
                                x.decode()
                                val account = x.child + 2_147_483_648L
                                listOf(
                                    XPUB.MAGIC_XPUB, XPUB.MAGIC_TPUB, XPUB.MAGIC_YPUB,
                                    XPUB.MAGIC_UPUB, XPUB.MAGIC_ZPUB, XPUB.MAGIC_VPUB,
                                ).forEach { v ->
                                    put(
                                        XPUB.makeXPUB(v, x.depth, x.fingerprint, x.child, x.chain, x.pubkey),
                                        account,
                                    )
                                }
                            }
                        }
                    }
                    val backfillResult =
                        whirlpoolBackfill.run(newTransactions, collectionId, accountOfXpub)
                    Timber.i("whirlpool backfill: ${backfillResult.processed} processed, ${backfillResult.written} written, ${backfillResult.handsOff} hands-off, ${backfillResult.unclassified} unclassified")

                    // Badbank deduction + phase-1 propagation (#46):
                    // same hook, same flag, same pre-mangle in-memory
                    // list. Fixpoint over NOT_DESCENDANT_SPEND retries:
                    // a same-sync chain (tx0 and descendant spends in
                    // one batch, any order) converges — each retry pass
                    // either strictly shrinks the retry set or breaks.
                    // Without this, a descendant processed before its
                    // parent's label exists is missed permanently (it
                    // never reappears in newTransactions). Per-tx
                    // containment (#48): one bad tx never starves the
                    // batch; failures are logged and skipped.
                    val badbankOutcomes =
                        HashMap<String, BadbankLabeler.Outcome>()
                    val badbankViews = WhirlpoolBackfill.mergePartialTxs(
                        newTransactions, collectionId
                    ).map { tx ->
                        WhirlpoolTxAdapter.toView(tx, collectionId, accountOfXpub)
                    }
                    badbankViews.forEach { v ->
                        runCatching { badbankLabeler.process(v) }
                            .onSuccess { badbankOutcomes[v.txid] = it }
                            .onFailure {
                                Timber.e(it, "badbank labeler failed: ${v.txid}")
                            }
                    }
                    var badbankRetry = badbankViews.filter {
                        badbankOutcomes[it.txid] ==
                            BadbankLabeler.Outcome.NOT_DESCENDANT_SPEND
                    }
                    while (badbankRetry.isNotEmpty()) {
                        var progressed = false
                        val nextRetry = mutableListOf<WhirlpoolTxView>()
                        for (v in badbankRetry) {
                            val out = runCatching { badbankLabeler.process(v) }
                                .onFailure {
                                    Timber.e(it, "badbank labeler retry: ${v.txid}")
                                }
                                .getOrNull() ?: continue   // failed twice: drop
                            badbankOutcomes[v.txid] = out
                            if (out != BadbankLabeler.Outcome.NOT_DESCENDANT_SPEND) {
                                progressed = true
                            } else {
                                nextRetry += v
                            }
                        }
                        if (!progressed) break
                        badbankRetry = nextRetry
                    }
                    val bb = { o: BadbankLabeler.Outcome ->
                        badbankOutcomes.values.count { it == o }
                    }
                    Timber.i("badbank labeler: ${badbankViews.size} processed, " +
                        "tx0-labelled=${bb(BadbankLabeler.Outcome.TX0_LABELLED)}, " +
                        "propagated=${bb(BadbankLabeler.Outcome.PROPAGATED)}, " +
                        "hands-off=${bb(BadbankLabeler.Outcome.TX0_HANDS_OFF)}, " +
                        "territory=${bb(BadbankLabeler.Outcome.WHIRLPOOL_TERRITORY)}, " +
                        "not-descendant=${bb(BadbankLabeler.Outcome.NOT_DESCENDANT_SPEND)}")
                }
                    .onFailure { Timber.e(it, "whirlpool/badbank labelling failed") }
            }
            // Boltzmann tx entropy (issue #7, #72 fix): compute + cache
            // per bare txid on the FULL tx (ApiService.getTx -> esplora
            // adapter), never on a wallet slice - the payload cannot
            // prove completeness. #102: the ingest no longer runs here -
            // it ran inside the delete->insert window (deletes above,
            // saveTx below), holding the tx table empty for the whole
            // sync. What stays here is the CAPTURE: bare txids, as
            // strings, pre-mangle (keepTransactionWithVariousPubkeys
            // below appends -N suffixes to hashes in place - strings,
            // not Tx references, so the mangle cannot reach the
            // capture). The ingest launches after saveUtxos, outside
            // the window.
            // #105: kill switch. While ENTROPY_FEATURE_ENABLED is
            // false the capture is null, the launch below never
            // runs - and the by-lazy ingest means the engine object
            // is never built while OFF.
            val entropyTxids = if (ENTROPY_FEATURE_ENABLED && prefsUtil.whirlpoolAutoLabels == true) {
                LinkedHashSet(newTransactions.map { it.hash.removeSuffix("-$collectionId") })
            } else null
            newTransactions = keepTransactionWithVariousPubkeys(newTransactions)
            saveTx(newTransactions, collectionId)
            saveUtxos(utxos, collectionId)
            // #102: the ingest runs AFTER the inserts - the tx table is
            // populated while entropy computes. Fire-and-forget on
            // apiScope (SupervisorJob): an ingest failure can never
            // cancel the scope or break sync; per-tx containment lives
            // inside the ingest. The ingest reads only tx_entropy and
            // esplora - never the tx table - so it cannot race saveTx.
            // The counter line logs from inside the job (the sync may
            // already have returned; the log is the telemetry).
            // #105: dead code while the kill switch is OFF - kept
            // intact as the re-enable point (see EntropyFeature.kt).
            entropyTxids?.let { captured ->
                apiScope.launch {
                    val entropyResult = txEntropyIngest.ingestTxids(captured)
                    Timber.i(
                        "boltzmann entropy: ${entropyResult.attempted} attempted, " +
                            "${entropyResult.analyzed} analyzed, " +
                            "${entropyResult.unresolvable} unresolvable (skipped), " +
                            "${entropyResult.fetchFailed} fetch-failed (skipped), " +
                            "${entropyResult.engineFailed} engine-failed (rowed), " +
                            "${entropyResult.failed} failed (skipped)"
                    )
                }
            }
        } catch (e: ApiNotConfigured) {
            // #103: ApiNotConfigured extends Throwable - every catch(Exception)
            // wall above (per-job and this outer one) is blind to it, so an async
            // child's throw escaped fetchFromServer and killed the process on any
            // sync round against an unpaired install. Same ordered-catch degrade
            // as fetchUTXOS below. The throw jumps from await() straight here:
            // the delete window is never reached, no partial wipe.
            Timber.e(e)
        } catch (e: Exception) {
            Timber.e(e)
            throw e
        }
    }

    private fun keepTransactionWithVariousPubkeys(transactions: ArrayList<Tx>): ArrayList<Tx> {
        val groupedTransactions = transactions.groupBy { it.hash }
        val hashes: HashMap<String, Int> = hashMapOf()
        groupedTransactions.forEach {
            if (it.value.size > 1) {
                hashes[it.value[0].hash] = 0
            }
        }
        transactions.forEach {
            if (hashes.contains(it.hash)) {
                val counter = hashes[it.hash]?.plus(1)
                hashes[it.hash] = counter!!.toInt()
                it.hash = it.hash + "-" + hashes[it.hash]
            }
        }
        return transactions
    }

    private fun saveTx(transactions: ArrayList<Tx>, collectionId: String) = apiScope.launch {
        withContext(Dispatchers.IO) {
            transactions.forEach {
                txDao.insert(it)
            }
        }
    }


    private fun saveUtxos(utxos: ArrayList<Utxo>, collectionId: String) = apiScope.launch {
        // #87: mid-erase (or a deleted collection) findById returns
        // null - take the same early-skip fetchUTXOS already uses. Nothing
        // to reconcile, and writing rows for a gone collection would
        // resurrect data into a freshly wiped wallet.
        val collection = collectionRepository.findById(collectionId)
            ?: return@launch
        withContext(Dispatchers.IO) {
            utxoDao.getUTXObyCollectionAsList(collectionId)
                .forEach {
                    val isExist =
                        utxos.find { utxo -> (utxo.txHash == it.txHash && it.txOutputN == utxo.txOutputN) }
                   //if utxos is not present in the new list it will be removed from the local db
                    if(isExist == null){
                        if (it.txOutputN != null && it.txHash != null) {
                            utxoDao.delete(it.txHash!!, it.txOutputN!!)
                        }
                    }
                }

            UtxoMetaUtil.getBlockedAssociatedWithPubKeyList(collection.pubs.map { it.pubKey }.toList())
                .forEach {
                    val isExist =
                        utxos.find { utxo -> (utxo.txHash == it.hash && it.txOutputN == utxo.txOutputN) }
                    //if utxos is not present in the new list it will be removed from the Blocked UTXOs List
                    if(isExist == null){
                        UtxoMetaUtil.remove(it.hash, it.txOutputN)
                    }
                }
            utxos.forEach {
                utxoDao.insert(it)
            }
        }
    }

    suspend fun removeTxsRelatedToPubKey(pubKeyModel: PubKeyModel, collectionId: String) {
        val collection = collectionRepository.findById(collectionId)
        if (collection != null) {
            txDao.deleteByCollectionAndPubkey(collection.id, pubKeyModel.pubKey)
            utxoDao.deleteByCollection(collection.id)
        }
    }

    suspend fun fetchFromServer(collection: PubKeyCollection) {
        return this.fetchFromServer(collection.id)
    }

    // Also forced onto Dispatchers.IO for Room safety.
    suspend fun fetchUTXOS(collectionId: String): Unit = withContext(Dispatchers.IO) {
        try {
            val collection = collectionRepository.findById(collectionId)
                ?: return@withContext
            val utxos: ArrayList<Utxo> = arrayListOf();
            val jobs: ArrayList<Deferred<Response>> = arrayListOf()
            collection.pubs.forEach {
                val item = apiScope.async {
                    apiService.getWallet(it.pubKey)
                }
                jobs.add(item);
            }

            jobs.forEach { job ->
                val index = jobs.indexOf(job)
                loadingCollectionId = collectionId
                try {
                    val res = jobs[index].await()
                    res.body?.let { it ->
                        val resString = it.string()
                        val pubKeyAssociated = collection.pubs[index]
                        val response: WalletResponse = fromJSON<WalletResponse>(resString)!!

                        response.unspent_outputs?.let {
                            val list = response.unspent_outputs.toMutableList().map {
                                it.pubKey = pubKeyAssociated.pubKey
                                it.idx = "${it.txHash}:${it.txOutputN}"
                                it.collectionId = collectionId
                                if (it.xpub != null) {
                                    it.path = it.xpub?.path!!
                                }
                                it
                            }.toList()
                            utxos.addAll(list)
                        }
                    }
                } catch (e: Exception) { Timber.e(e) }
            }

            withContext(Dispatchers.IO) {
                utxoDao.deleteByCollection(collectionId)
            }

            saveUtxos(utxos, collectionId)
        } catch (e: ApiNotConfigured) {
            Timber.e(e)
        } catch (e: Exception) {
            Timber.e(e)
        }
    }
    }
