package com.samourai.sentinel.ui.home

import androidx.lifecycle.*
import com.samourai.sentinel.core.ConnectionIndicator
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.SyncState
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.data.repository.FeeRepository
import com.samourai.sentinel.data.repository.TransactionsRepository
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.tor.TorState
import com.samourai.sentinel.ui.utils.Preferences
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.bitcoinj.core.Coin
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import kotlin.reflect.KProperty

class HomeViewModel : ViewModel() {

    companion object {
        /**
         * How long a sync may run before we tell the user it's taking a while.
         *
         * This does NOT cancel anything - it only annotates the banner. Tor
         * bootstrapping plus onion circuit setup can legitimately exceed a minute,
         * so cancelling on a timer produced false failures.
         */
        private const val SLOW_SYNC_NOTICE_MS = 30_000L

        /** Auto rounds that fail totally (circuit not yet usable) get at
         *  most this many spaced retries before showing Failed. */
        private const val AUTO_SYNC_RETRIES = 2
        private const val AUTO_RETRY_DELAY_MS = 3_000L

        /**
         * #135: wall-clock budget for waiting out the boot climb before a
         * round fires. Covers the measured cold start (~50 s, 10-02) with
         * margin; on timeout the round defers instead of firing through a
         * proxy-less client, and observeTor() re-arms it at readiness.
         */
        private const val TOR_READY_TIMEOUT_MS = 90_000L
    }

    val repository: CollectionRepository by inject(CollectionRepository::class.java)
    private val exchangeRateRepository: ExchangeRateRepository by inject(ExchangeRateRepository::class.java)
    private val transactionsRepository: TransactionsRepository by inject(TransactionsRepository::class.java)
    private val feeRepository: FeeRepository by inject(FeeRepository::class.java)

    private val errorMessage: MutableLiveData<String> = MutableLiveData()
    private val balance: MutableLiveData<Long> = MutableLiveData()
    private var netWorkJobs: ArrayList<Job?> = arrayListOf()
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)

    /** Single source of truth for the sync lifecycle. */
    private val syncState: MutableLiveData<SyncState> = MutableLiveData(SyncState.Idle)

    fun syncState(): LiveData<SyncState> = syncState

    // #184: endpoint + Tor-toggle writes are indicator inputs. Pref
    // delegates notify listeners BEFORE the value lands (GenericPrefDelegate
    // notifies first, writes after), so pokes travel via postValue - by
    // main-loop dispatch time the write has landed and computeIndicator()
    // reads fresh prefs.
    private val endpointPoke = MutableLiveData<Unit>()

    /**
     * Coalescing guard (#47): at most ONE sync round in flight at a time -
     * whether a user-initiated fetchBalance round or an auto-armed
     * syncCollections round - plus a single trailing re-run if the collection
     * list changed mid-round. Overlapping rounds (each with its own
     * completed-counter, each posting its own terminal state) were the banner
     * flap, the backwards "n of m" counter, and the dojo call storm.
     */
    private var syncRound: Job? = null

    @Volatile
    private var syncRerunPending = false

    /**
     * Content signature (collection ids + pubkeys) of the last round's list.
     * ALL auto re-arms - MediatorLiveData emissions, trailing re-runs,
     * stragglers delivered just after round end - are skipped when the
     * signature is unchanged. Ids alone are too coarse: adding a pubkey to
     * an existing collection keeps the id but must re-sync (#47).
     */
    @Volatile
    private var lastRoundSignature: String? = null

    private fun collectionSignature(list: List<PubKeyCollection>): String =
        list.joinToString("|") { c ->
            c.id + ":" + c.pubs.joinToString(",") { it.pubKey }
        }

    /** Bounded auto-retry budget; reset on success or user refresh. */
    private var autoRetryBudget = 0

    @Volatile
    private var autoRetryPending = false

    private fun launchSyncRound(collections: ArrayList<PubKeyCollection>) {
        // #175: choke point for every auto arm (observeTor ON re-arm,
        // trailing re-run, getCollections). No collections and no endpoint
        // means no work exists that needs Tor: Idle, never a WaitingForTor
        // banner and never an empty round fabricating Success (#174). The
        // signature stamp is left untouched so a later real round is not
        // signature-skipped.
        if (collections.isEmpty() && !prefsUtil.isAPIEndpointEnabled()) {
            if (syncState.value !is SyncState.Failed) {
                syncState.postValue(SyncState.Idle)
            }
            return
        }
        if (syncRound?.isActive == true) {
            // Drop the re-arm; the in-flight round is already fetching this
            // data. Re-run at most once when it ends so genuine collection
            // changes mid-round are still picked up.
            syncRerunPending = true
            Timber.i("sync round suppressed: round in flight, trailing re-run armed")
            return
        }
        val signature = collectionSignature(collections)
        if (lastRoundSignature != null && signature == lastRoundSignature) {
            Timber.i("sync round skipped: signature unchanged since last round")
            // #184: a skipped re-arm must not leave the climb's
            // WaitingForTor standing - no other emission is coming, and a
            // stale WaitingForTor is what pulsed the dot amber forever
            // after bootstrap on an unconfigured rig.
            if (syncState.value is SyncState.WaitingForTor) {
                syncState.postValue(SyncState.Idle)
            }
            return
        }
        lastRoundSignature = signature
        Timber.i("sync round begin: syncCollections collections=${collections.size}")
        syncRound = viewModelScope.launch(Dispatchers.IO) {
            val self = coroutineContext[Job]
            try {
                // #135: no fetch before full readiness (boot=100 AND the
                // SOCKS listener published). buildClient attaches the proxy
                // only from the LISTENERS event; firing earlier resolves the
                // .onion host via system DNS (38 UHE, capture 1010-133246,
                // all pre-T100). On timeout: un-stamp the signature so the
                // observeTor() ready re-arm is not signature-skipped.
                if (SentinelState.isTorRequired() &&
                    !SentinelTorManager.awaitTorReady(TOR_READY_TIMEOUT_MS)
                ) {
                    lastRoundSignature = null
                    syncState.postValue(
                        SyncState.WaitingForTor(
                            SentinelTorManager.getTorState().progressIndicator
                        )
                    )
                    return@launch
                }
                syncCollections(collections, self)
            } finally {
                // Identity check: a cancelled/replaced round must never clear
                // the guard state of its successor.
                if (syncRound === self) {
                    syncRound = null
                    if (syncRerunPending) {
                        syncRerunPending = false
                        maybeTrailingRerun()
                    }
                } else {
                    Timber.i("sync round end: superseded, cleanup skipped")
                }
            }
        }
    }

    /**
     * Surfaces a corrupt-payload condition so the UI can warn the user instead of
     * silently showing an empty wallet list.
     */
    fun payloadReadFailure(): LiveData<String?> = repository.readFailure()

    // #184: only these pref writes are dot-relevant.
    private val indicatorPrefs = setOf("apiEndPoint", "apiEndPointTor", "enableTor")

    // Qualified via Preferences, the declaring class - Kotlin resolves
    // nested classifiers only through their declaring class (SentinelState
    // precedent). Declared ABOVE init: Kotlin initializes members in
    // textual order and forward references are compile errors.
    private val prefsListener = object : Preferences.SharedPrefsListener {
        override fun onSharedPrefChanged(property: KProperty<*>) {
            if (property.name !in indicatorPrefs) return
            endpointPoke.postValue(Unit)
        }
    }

    /**
     * #186: true when a poke retired a standing Success on unpair; the
     * next arrive-direction poke (re-pair) spends it on a quiet round so
     * the dot climbs back to GREEN with no manual refresh. #188: round
     * tails on IO threads set it too when they suppress a terminal
     * Success (endpoint vanished mid-round) - hence @Volatile.
     */
    @Volatile
    private var staleSuccessRetired = false

    /**
     * #184: a standing non-retryable Failed is the "No server configured."
     * red; the moment an endpoint exists it is stale. A quiet auto round
     * retires it (empty wallet -> immediate Success -> GREEN; real wallet
     * -> honest terminal state, #47 coalescing guards stacking). Runs on
     * endpointPoke dispatch so the pref write has landed before reads.
     *
     * #186: the vanish direction. computeIndicator() is stateless and its
     * Success arm is evaluated BEFORE the no-endpoint RED arm, so a
     * standing Success from the paired era keeps the dot GREEN after
     * unpairing until some other input changes. Retire it to Idle here:
     * the Idle emission itself re-arms the mediator (syncState is a
     * source) and the honest no-endpoint RED arm wins at recompute.
     */
    private val endpointKick = Observer<Unit> {
        val sync = syncState.value
        // #188: the kick was silent through two device legs - name the
        // dispatch next time. Booleans and the state class only; never
        // endpoint material (#96).
        Timber.i(
            "endpointKick: endpointSet=%s torEndpointSet=%s sync=%s",
            prefsUtil.apiEndPoint != null,
            prefsUtil.apiEndPointTor != null,
            sync?.javaClass?.simpleName
        )
        when {
            prefsUtil.isAPIEndpointEnabled() && sync is SyncState.Failed && !sync.retryable -> {
                fetchBalance(userInitiated = false)
            }
            !prefsUtil.isAPIEndpointEnabled() && sync is SyncState.Success -> {
                staleSuccessRetired = true
                syncState.postValue(SyncState.Idle)
            }
            prefsUtil.isAPIEndpointEnabled() && sync is SyncState.Idle && staleSuccessRetired -> {
                staleSuccessRetired = false
                fetchBalance(userInitiated = false)
            }
        }
    }

    init {
        load()
        observeTor()
        // #184: pairing/unpairing and the Tor toggle re-render the dot.
        // observeForever needs the main thread; ViewModel creation is main.
        prefsUtil.addListener(prefsListener)
        endpointPoke.observeForever(endpointKick)
    }

    override fun onCleared() {
        endpointPoke.removeObserver(endpointKick)
        prefsUtil.removeListener(prefsListener)
        super.onCleared()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                repository.read()
            } catch (e: Exception) {
                Timber.e(e)
                // Do NOT report this as a generic network error; it means the
                // on-disk payload could not be decoded.
                syncState.postValue(
                    SyncState.Failed(
                        reason = "Could not load your collections.",
                        retryable = true
                    )
                )
            }
        }
    }

    /**
     * Reflects Tor bootstrap progress into the sync state so the indicator can
     * show real progress rather than an indefinite spinner.
     */
    private fun observeTor() {
        if (!SentinelState.isTorRequired()) return
        SentinelTorManager.getTorStateLiveData().observeForever { torState ->
            when (torState.state) {
                EnumTorState.STARTING -> {
                    // #175: bootstrap progress is only banner-worthy when
                    // something awaits it. An empty wallet with no endpoint
                    // stays quiet (device-witnessed: 3x WaitingForTor in
                    // exactly that regime, 2026-10-16 relaunch leg).
                    val workPending = repository.collectionsSnapshot().isNotEmpty() ||
                        prefsUtil.isAPIEndpointEnabled()
                    if (syncState.value !is SyncState.Syncing && workPending) {
                        syncState.postValue(SyncState.WaitingForTor(torState.progressIndicator))
                    }
                }
                EnumTorState.OFF -> {
                    if (syncState.value is SyncState.WaitingForTor) {
                        syncState.postValue(
                            SyncState.Failed("Tor is not connected.", retryable = true)
                        )
                    }
                }
                EnumTorState.ON -> {
                    // #135: re-arm rounds deferred during the climb. The
                    // readiness check must be the AWAIT poll, not a
                    // synchronous isTorReady(): the boot=100 STATE emission
                    // can reach the main thread before LISTENERS assigns the
                    // proxy (measured 1 ms apart, capture 1011 fresh-launch),
                    // and after boot=100 no further ON emissions ever come -
                    // a lost race would defer sync forever (observed: no
                    // syncCollections round began; sync only ran via the
                    // 18:08:57 resume trigger). awaitTorReady polls the
                    // volatile proxy field directly, so ordering cannot lose.
                    // Concurrent ON emissions collapse in launchSyncRound's
                    // coalescing + signature gates.
                    // viewModelScope: a bare launch here resolved to the
                    // deprecated scope-less CoroutineContext launch and
                    // failed the compile gate (HomeViewModel.kt:218).
                    viewModelScope.launch {
                        if (SentinelTorManager.awaitTorReady()) {
                            launchSyncRound(ArrayList(repository.collectionsSnapshot()))
                        }
                    }
                }
                EnumTorState.STOPPING -> Unit
            }
        }
    }

    fun getCollections(): LiveData<ArrayList<PubKeyCollection>> {
        val collectionsLiveData = repository.collectionsLiveData
        val resultLiveData = MediatorLiveData<ArrayList<PubKeyCollection>>()

        resultLiveData.addSource(collectionsLiveData) { collections ->
            // Publish cached collections IMMEDIATELY so the user sees their
            // wallets while any network work happens in the background.
            resultLiveData.value = collections

            // #175/#174: an empty wallet with no endpoint configured has
            // no work that needs Tor - no WaitingForTor banner, no round,
            // and no empty-round Success painting the indicator green.
            // A Failed the user earned (pull-to-refresh with nothing
            // configured) stands.
            if (collections.isEmpty() && !prefsUtil.isAPIEndpointEnabled()) {
                if (syncState.value !is SyncState.Failed) {
                    syncState.postValue(SyncState.Idle)
                }
                SentinelState.hasAppJustStarted = false
                return@addSource
            }

            // #135: full readiness, not the boot=5 ON emission. When not yet
            // ready the round is deferred and observeTor() re-arms it the
            // moment boot=100 AND the SOCKS listener are both published.
            val torReady = !SentinelState.isTorRequired() ||
                SentinelTorManager.isTorReady()

            if (!torReady) {
                // Previously the loading counter was incremented here even though
                // no fetch was started, which is what left the spinner running
                // forever on first launch after PIN entry.
                syncState.postValue(
                    SyncState.WaitingForTor(SentinelTorManager.getTorState().progressIndicator)
                )
                SentinelState.hasAppJustStarted = false
                return@addSource
            }

            // MUST be Dispatchers.IO: fetchUTXOS/fetchFromServer touch Room, and
            // viewModelScope defaults to Dispatchers.Main, which makes Room throw
            // "Cannot access database on the main thread".
            launchSyncRound(collections)
        }
        return resultLiveData
    }

    /**
     * Fetches UTXOs for each collection, reporting determinate progress.
     *
     * There is deliberately NO timeout on the network work. Tor circuits can take
     * minutes to build on a poor connection, and cancelling a healthy-but-slow
     * sync produced a false "timed out" error. Instead a watchdog flips the state
     * to `Syncing(slow = true)` so the UI can explain the delay while the request
     * keeps running. The `finally` block still guarantees a terminal state.
     */
    private suspend fun syncCollections(
        collections: ArrayList<PubKeyCollection>,
        round: Job?
    ) = coroutineScope {
        if (collections.isEmpty()) {
            // #174: "nothing to sync" is a success only when a server
            // exists to have synced against. Unconfigured endpoint -> Idle;
            // Success here painted the indicator GREEN on a fresh install
            // with nothing configured (device-witnessed 2026-10-16).
            // Execute-time truth for the admit/execute race (endpoint
            // removed mid-round, #85 ground).
            if (prefsUtil.isAPIEndpointEnabled()) {
                syncState.postValue(SyncState.Success(System.currentTimeMillis()))
            } else if (syncState.value !is SyncState.Failed) {
                syncState.postValue(SyncState.Idle)
            }
            updateBalance()
            SentinelState.hasAppJustStarted = false
            return@coroutineScope
        }

        var completed = 0
        var failed = 0
        // Tracked locally because postValue() is asynchronous - reading
        // syncState.value in the finally block would still see "Syncing" and
        // clobber the real terminal state.
        var resolved = false
        syncState.postValue(SyncState.Syncing(done = 0, total = collections.size))

        // Informational only: annotates the banner, never cancels the sync.
        val slowWatchdog = launch {
            delay(SLOW_SYNC_NOTICE_MS)
            syncState.postValue(
                SyncState.Syncing(done = completed, total = collections.size, slow = true)
            )
        }

        try {
            collections.forEach { collection ->
                try {
                    transactionsRepository.fetchUTXOS(collection.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    Timber.e(e, "Failed to sync collection ${collection.id}")
                }
                completed++
                syncState.postValue(
                    SyncState.Syncing(
                        done = completed,
                        total = collections.size,
                        slow = !slowWatchdog.isActive
                    )
                )
                updateBalance()
            }

            updateBalance()

            syncState.postValue(
                when {
                    failed == collections.size -> SyncState.Failed(
                        "Could not reach the server.",
                        retryable = true
                    )
                    failed > 0 -> SyncState.Failed(
                        "$failed of ${collections.size} collections failed to sync.",
                        retryable = true
                    )
                    // #188: execute-time endpoint truth. A Success posted
                    // after the pairing vanished mid-round is the witnessed
                    // remainder (issue #188) - suppress to Idle and arm the
                    // re-pair flag. A user-earned Failed stands (#174).
                    !prefsUtil.isAPIEndpointEnabled() -> {
                        staleSuccessRetired = syncState.value !is SyncState.Failed
                        SyncState.Idle
                    }
                    else -> SyncState.Success(System.currentTimeMillis())
                }
            )
            resolved = true
        } finally {
            Timber.i("sync round end: syncCollections")
            slowWatchdog.cancel()
            // Guaranteed to run, so the UI can never be left mid-sync.
            SentinelState.hasAppJustStarted = false
            // #132: only the live round posts the terminal fallback - a
            // superseded round's Failed flashed over its successor's
            // Syncing ("Sync interrupted." on routine refresh).
            // round == null: caller had no identity - today's behavior.
            if (!resolved && (round == null || syncRound === round)) {
                syncState.postValue(SyncState.Failed("Sync interrupted.", retryable = true))
            }
        }
    }

    /**
     * Trailing re-run with id-diff discipline: the finished round already
     * synced exactly these collections if the id sets match, so its own
     * emissions are discarded. Only a genuinely changed list re-arms (#47).
     */
    private fun maybeTrailingRerun() {
        // launchSyncRound applies the signature gate; an unchanged list is
        // skipped there (#47).
        repository.collectionsLiveData.value?.let { launchSyncRound(it) }
    }

    fun getBalance(): LiveData<Long> {
        return balance
    }

    /**
     * #184: ONE shared mediator for the process. Recreated per call it could
     * never gain a source after the fact - a mid-session Tor toggle left
     * the dot blind to Tor for the rest of the process (the source set was
     * frozen at first menu preparation, and only if Tor was already
     * required then).
     */
    private val connectionIndicatorMediator: MediatorLiveData<ConnectionIndicator> by lazy {
        MediatorLiveData<ConnectionIndicator>().also { mediator ->
            val recompute: (TorState?) -> Unit = { tor ->
                mediator.value = computeIndicator(tor)
            }
            mediator.addSource(syncState) { recompute(null) }
            // Attached UNCONDITIONALLY: computeIndicator gates every Tor arm
            // on a fresh isTorRequired() read, so a mid-session toggle is
            // tracked without any re-wiring.
            mediator.addSource(SentinelTorManager.getTorStateLiveData()) { tor ->
                recompute(tor)
            }
            // #184: pairing/unpairing and the Tor toggle arrive as pokes;
            // recompute reads the prefs fresh at dispatch time.
            mediator.addSource(endpointPoke) { recompute(null) }
        }
    }

    fun connectionIndicator(): LiveData<ConnectionIndicator> = connectionIndicatorMediator

    /**
     * Derives the toolbar traffic-light from Tor state + sync state +
     * endpoint configuration. Pure; the controller's dedupe is untouched.
     *
     * GREEN only when Tor is connected AND the last sync succeeded.
     */
    private fun computeIndicator(torEmission: TorState?): ConnectionIndicator {
        val torRequired = SentinelState.isTorRequired()
        // #99: the climb emission carries the authoritative snapshot (it
        // includes progressIndicator); the syncState and poke sources fall
        // back to a fresh manager read at recompute time.
        val tor = torEmission ?: SentinelTorManager.getTorState()
        val sync = syncState.value ?: SyncState.Idle

        return when {
            sync is SyncState.Failed -> ConnectionIndicator.RED
            torRequired && tor.state == EnumTorState.OFF -> ConnectionIndicator.RED
            torRequired && tor.state == EnumTorState.STARTING -> ConnectionIndicator.YELLOW
            // #99: ON lands at boot=5 - NOT fully bootstrapped. GREEN means
            // the 5->100 climb finished; mid-climb (circuit possibly not
            // SOCKS-ready, 10-02 D3) stays YELLOW even if a round succeeded
            // (operator-approved cost). The climb is monotonic, so the gate
            // cannot flap (#47 family): GREEN lands exactly at boot=100.
            torRequired && tor.state == EnumTorState.ON &&
                tor.progressIndicator < 100 -> ConnectionIndicator.YELLOW
            sync is SyncState.WaitingForTor -> ConnectionIndicator.YELLOW
            sync is SyncState.Syncing -> ConnectionIndicator.YELLOW
            // Tor up (or not needed) and the last sync completed cleanly.
            sync is SyncState.Success -> ConnectionIndicator.GREEN
            // #184: Tor is demanded and up, but nothing is configured to
            // sync against - an honest RED. This was NEUTRAL (hidden),
            // which is how a bootstrapped no-endpoint rig sat dark or
            // stale until a manual refresh manufactured a state change.
            torRequired && !prefsUtil.isAPIEndpointEnabled() -> ConnectionIndicator.RED
            else -> ConnectionIndicator.NEUTRAL
        }
    }

    fun fetchBalance(userInitiated: Boolean = false) {
        if (prefsUtil.apiEndPoint == null) {
            if (userInitiated) {
                // A human pull-to-refresh keeps the loud, honest red -
                // the one correction path on an unconfigured wallet (#174
                // device leg, 2026-10-16).
                syncState.postValue(
                    SyncState.Failed("No server configured.", retryable = false)
                )
            } else if (syncState.value !is SyncState.Failed) {
                // #175: auto triggers (startup one-shot, auto-retry
                // relaunch) never shout about a state the user did not
                // ask about.
                syncState.postValue(SyncState.Idle)
            }
            return
        }

        if (userInitiated) {
            if (netWorkJobs.isNotEmpty()) {
                netWorkJobs.forEach { it?.cancel() }
                netWorkJobs.clear()
            }

            // A human refresh REPLACES any in-flight round (#47).
            syncRound?.cancel()
            syncRound = null
            syncRerunPending = false
            autoRetryBudget = 0
        } else if (syncRound?.isActive == true) {
            // Auto triggers never stack or replace: the in-flight round is
            // already fetching this data. Robust to any number of auto
            // trigger sources (#47).
            Timber.i("sync round skipped: auto fetchBalance while round in flight")
            return
        }

        val collections = ArrayList(repository.collectionsSnapshot())

        // MUST be Dispatchers.IO: fetchFromServer writes to Room.
        syncRound = viewModelScope.launch(Dispatchers.IO) {
            val self = coroutineContext[Job]
            Timber.i("sync round begin: fetchBalance userInitiated=$userInitiated collections=${collections.size}")
            // #135: no fetch (rates included) before full readiness - see
            // launchSyncRound. Deferred rounds are re-armed by observeTor().
            if (SentinelState.isTorRequired() &&
                !SentinelTorManager.awaitTorReady(TOR_READY_TIMEOUT_MS)
            ) {
                lastRoundSignature = null
                syncState.postValue(
                    SyncState.WaitingForTor(SentinelTorManager.getTorState().progressIndicator)
                )
                return@launch
            }
            try {
                exchangeRateRepository.fetch()
            } catch (e: Exception) {
                Timber.e(e)
            }

            if (collections.isEmpty()) {
                // #188: the head guard checked the endpoint at admit;
                // re-check at post time - it can vanish mid-round.
                if (prefsUtil.isAPIEndpointEnabled()) {
                    syncState.postValue(SyncState.Success(System.currentTimeMillis()))
                } else if (syncState.value !is SyncState.Failed) {
                    staleSuccessRetired = true
                    syncState.postValue(SyncState.Idle)
                }
                return@launch
            }

            lastRoundSignature = collectionSignature(collections)
            var completed = 0
            var failed = 0
            var resolved = false
            syncState.postValue(SyncState.Syncing(done = 0, total = collections.size))

            // Informational only - no timeout, since Tor can legitimately be slow.
            val slowWatchdog = launch {
                delay(SLOW_SYNC_NOTICE_MS)
                syncState.postValue(
                    SyncState.Syncing(done = completed, total = collections.size, slow = true)
                )
            }

            try {
                collections.forEach { collection ->
                    try {
                        transactionsRepository.fetchFromServer(collection)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        Timber.e(e)
                    }
                    completed++
                    syncState.postValue(
                        SyncState.Syncing(
                            done = completed,
                            total = collections.size,
                            slow = !slowWatchdog.isActive
                        )
                    )
                }

                try {
                    feeRepository.getDynamicFees()
                } catch (e: Exception) {
                    Timber.e(e)
                }

                updateBalance()

                if (failed == collections.size) {
                    if (!userInitiated && autoRetryBudget < AUTO_SYNC_RETRIES) {
                        // Tor state ON does not mean the SOCKS circuit is
                        // usable yet. Stay in Syncing (no Failed flash) and
                        // retry shortly instead of churning rounds (#47).
                        autoRetryBudget++
                        autoRetryPending = true
                        Timber.i(
                            "sync auto-retry armed: $autoRetryBudget/$AUTO_SYNC_RETRIES"
                        )
                    } else {
                        syncState.postValue(
                            SyncState.Failed("Could not reach the server.", retryable = true)
                        )
                    }
                } else if (!prefsUtil.isAPIEndpointEnabled()) {
                    // #188: fetches succeeded against a pairing that was
                    // removed mid-round - the late Success here is the
                    // witnessed remainder. No Success, no lastSynced
                    // write; Idle + the re-pair flag instead. A
                    // user-earned Failed stands (#174 discipline).
                    autoRetryBudget = 0
                    staleSuccessRetired = syncState.value !is SyncState.Failed
                    if (staleSuccessRetired) {
                        syncState.postValue(SyncState.Idle)
                    }
                } else {
                    autoRetryBudget = 0
                    prefsUtil.lastSynced = System.currentTimeMillis()
                    syncState.postValue(SyncState.Success(System.currentTimeMillis()))
                }
                resolved = true
            } finally {
                val roundIsSelf = syncRound === self
                if (roundIsSelf) {
                    syncRound = null
                    if (autoRetryPending) {
                        autoRetryPending = false
                        syncRerunPending = false
                        // Give the circuit a moment; the retry re-fetches
                        // everything, so a pending re-run is subsumed.
                        delay(AUTO_RETRY_DELAY_MS)
                        Timber.i("sync round end: fetchBalance, auto-retry relaunching")
                        fetchBalance(userInitiated = false)
                    } else if (syncRerunPending) {
                        syncRerunPending = false
                        maybeTrailingRerun()
                    } else {
                        Timber.i("sync round end: fetchBalance")
                    }
                } else {
                    Timber.i("sync round end: fetchBalance superseded, cleanup skipped")
                }
                slowWatchdog.cancel()
                // postValue() is async, so syncState.value cannot be trusted here.
                // #132: only the live round posts the terminal fallback - a
                // superseded round's Failed flashed over its successor's
                // Syncing ("Sync interrupted." on routine refresh).
                if (!resolved && roundIsSelf) {
                    syncState.postValue(SyncState.Failed("Sync interrupted.", retryable = true))
                }
            }
        }
    }

    fun getErrorMessage(): LiveData<String> {
        return errorMessage
    }

    fun getFiatBalance(): LiveData<String> {

        val mediatorLiveData = MediatorLiveData<String>()

        mediatorLiveData.addSource(exchangeRateRepository.getRateLive()) {
            mediatorLiveData.value = getFiatBalance(balance.value, it)
        }
        mediatorLiveData.addSource(balance) {
            mediatorLiveData.value = getFiatBalance(it, exchangeRateRepository.getRateLive().value)
        }
        return mediatorLiveData
    }

    private fun getFiatBalance(balance: Long?, rate: ExchangeRateRepository.Rate?): String {
        if (rate == null) return "00.00"
        balance ?: return "00.00"
        return try {
            val fiatRate = MonetaryUtil.getInstance().getFiatFormat(prefsUtil.selectedCurrency)
                .format((balance / 1e8) * rate.rate)
            "$fiatRate ${rate.currency}"
        } catch (e: Exception) {
            "00.00 ${rate.currency}"
        }
    }


    fun getBTCDisplayAmount(value: Long): String? {
        return Coin.valueOf(value).toPlainString()
    }


    private fun updateBalance() {
        val collections = repository.collectionsLiveData.value ?: return

        try {
            val pubkeysList: MutableList<String> = mutableListOf()
            collections.forEach { collection ->
                collection.pubs.forEach { pubkey ->
                    pubkeysList.add(pubkey.pubKey)
                }
            }

            val blockedUtxos = UtxoMetaUtil.getBlockedAssociatedWithPubKeyList(pubkeysList)

            // sumOf is empty-safe; reduce() threw UnsupportedOperationException
            // on an empty collection list.
            val totalAmount = collections.sumOf { it.balance }
            val totalBlocked = blockedUtxos.sumOf { it.amount }

            balance.postValue(totalAmount - totalBlocked)
        } catch (e: Exception) {
            Timber.e(e)
        }
    }
}
