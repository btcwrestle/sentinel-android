package com.samourai.sentinel.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.TransactionsRepository
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.home.HomeActivity
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.MonetaryUtil
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okio.ByteString
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

class WebSocketHandler : WebSocketListener() {

    enum class Status {
        CONNECTED,
        DISCONNECTED,
    }

    private enum class SocketState { IDLE, CONNECTING, CONNECTED }

    private val context: Context by inject(Context::class.java)
    private val apiService: ApiService by inject(ApiService::class.java)
    private val monetaryUtil: MonetaryUtil by inject(MonetaryUtil::class.java)
    private val transactionsRepository: TransactionsRepository
            by inject(TransactionsRepository::class.java)
    private val collectionRepo: CollectionRepository
            by inject(CollectionRepository::class.java)
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private val reconnectPolicy = ReconnectPolicy()
    private val torResetFloorMs = 5_000L
    private var lastTorState: EnumTorState? = null
    private var lastTorOnResetMs = 0L
    private val jwtReauthPending = AtomicBoolean(false)

    /**
     * Serializes every socket lifecycle transition: connect attempts, terminal
     * callbacks, teardown. OkHttp delivers callbacks on its own threads, so all
     * reads/writes of [socket], [socketState] and [reconnectJob] hold this mutex.
     */
    private val socketMutex = Any()
    private var socket: WebSocket? = null
    private var socketState = SocketState.IDLE
    private var reconnectJob: Job? = null

    @Volatile private var mainJob = SupervisorJob()
    @Volatile private var webSocketScope = CoroutineScope(Dispatchers.IO) + mainJob
    @Volatile private var socketStatus = Status.DISCONNECTED

    init {
        webSocketScope.launch(Dispatchers.Main) {
            SentinelTorManager.getTorStateLiveData().observeForever {
                // QA round 1 (PR #45): the LiveData emits bursts of state
                // objects during bootstrap; acting on every ON emission
                // produced 19 connect attempts in 20s. Only act on real
                // transitions, and rate-limit teardowns of healthy sockets.
                val prev = lastTorState
                lastTorState = it.state
                if (prev == it.state) return@observeForever
                when (it.state) {
                    EnumTorState.ON -> {
                        // #135: ON is published at boot=5; acting here fired
                        // the socket at boot=14 through a proxy-less client
                        // (38 UHE, capture 1010-133246, all pre-T100). Wait
                        // for full bootstrap + SOCKS listener, then connect
                        // once under the same 5 s floor as before.
                        ensureScope().launch {
                            // #135 instrumentation: the fresh-launch capture
                            // showed this await returning ~58 s after readiness
                            // (expected <=250 ms). Log both ends so the next
                            // capture names the delay mechanism with data.
                            val ready = SentinelTorManager.awaitTorReady()
                            Timber.i("tor-on await completed: ready=$ready")
                            if (ready) {
                                val now = System.currentTimeMillis()
                                if (now - lastTorOnResetMs >= torResetFloorMs) {
                                    lastTorOnResetMs = now
                                    resetAndReconnect(reason = "tor-ready")
                                } else if (synchronized(socketMutex) { socketState }
                                        == SocketState.IDLE) {
                                    scheduleReconnect()
                                }
                            }
                        }
                    }
                    EnumTorState.OFF -> closeAndStop()
                    else -> Unit
                }
            }
        }
        registerNetworkGate()
    }

    /**
     * One immediate attempt when the network comes back, no retry churn while
     * it's gone. The backoff timer alone would otherwise take up to its full
     * delay to notice a restored connection.
     */
    private fun registerNetworkGate() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    reconnectPolicy.reset()
                    // If Tor died while offline (long airplane), nothing in
                    // the background restarts it - HomeActivity only starts
                    // Tor in the foreground. Without this, every reconnect
                    // attempt hits the tor gate forever (#41: recovery must
                    // work without app restart / foregrounding).
                    val apiEndPoint = try {
                        apiService.getAPIUrl()?.toHttpUrl()
                    } catch (er: ApiService.ApiNotConfigured) {
                        null
                    }
                    val torGateRequired = SentinelState.isTorRequired() ||
                            (apiEndPoint?.host?.endsWith(".onion") == true)
                    if (torGateRequired &&
                        SentinelTorManager.getTorState().state == EnumTorState.OFF
                    ) {
                        Timber.i("Network restored but Tor is OFF; starting Tor")
                        SentinelTorManager.start()
                    }
                    connect(reason = "net-available")
                }

                override fun onLost(network: Network) {
                    // Only stop the retry timer; a live socket dies on its own
                    // if its path is really gone (a wifi->cell switch must not
                    // kill a healthy socket).
                    synchronized(socketMutex) {
                        reconnectJob?.cancel()
                        reconnectJob = null
                    }
                }
            })
        } catch (e: Exception) {
            Timber.w(e, "Network gate unavailable; backoff still applies")
        }
    }

    fun connect(reason: String = "external"): Job? {
        val apiEndPoint = try {
            apiService.getAPIUrl()?.toHttpUrl()
        } catch (er: ApiService.ApiNotConfigured) {
            return null
        } ?: return null

        // An .onion endpoint is only reachable through the Tor proxy. Without
        // this gate, attempts made while Tor is OFF resolved the onion host
        // via system DNS (instant UnknownHostException) and fed the retry
        // loop (#41). #135: ON alone is NOT enough - it is published at
        // boot=5, before the SOCKS listener exists, and the proxy-less
        // client resolves .onion via system DNS again. Defer until
        // boot=100 AND proxy != null.
        val torGateRequired = SentinelState.isTorRequired() ||
                apiEndPoint.host.endsWith(".onion")
        val torState = SentinelTorManager.getTorState()
        if (torGateRequired && !SentinelTorManager.isTorReady()) {
            // QA rounds 2-3 (PR #45): this block was silent, which made
            // "Tor never restarted in background" indistinguishable from
            // "nothing attempted". Log every deferral.
            Timber.i("Connect deferred by tor gate (reason=$reason, " +
                    "state=${torState.state}, boot=${torState.progressIndicator})")
            return null
        }

        synchronized(socketMutex) {
            if (socketState != SocketState.IDLE) {
                Timber.d("connect() ignored, socketState=$socketState")
                return null
            }
            // A fresh attempt supersedes any pending timer.
            reconnectJob?.cancel()
            reconnectJob = null
            socketState = SocketState.CONNECTING
        }

        Timber.i("OnConnect(reason=$reason)")

        val scheme = if (apiEndPoint.isHttps) "wss://" else "ws://"
        val webSocketEndPoint = (scheme + apiEndPoint.host + "/" +
                apiEndPoint.pathSegments.joinToString("/") + "/inv").toUri()

        val client = try {
            ApiService.buildClient(
                apiService = null,
                url = apiService.getAPIUrl(),
                excludeApiKey = true,
                excludeAuthenticator = true,
                authToken = prefsUtil.authorization)
                .newBuilder()
                // Without pings, an airplane-mode kill leaves a CONNECTED
                // zombie that never fails and never reconnects (QA round 1,
                // legs 2-3). 30s ping bounds detection at ~60-90s.
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        } catch (e: ApiService.ApiNotConfigured) {
            // #125: this file already arms the class correctly at :126 and :158, but the
            // second getAPIUrl (:198, as buildClient's arg) sat behind an Exception-only
            // wall. Verified callers of connect(): the launch at :286 on webSocketScope
            // (Dispatchers.IO + bare SupervisorJob, :235-245, no CEH) and the direct call
            // at :137 behind the Exception wall at :150. Process death on a reconnect
            // tick. Mirror :158's degrade exactly.
            Timber.e(e, "Websocket connect aborted: no API endpoint configured")
            synchronized(socketMutex) { socketState = SocketState.IDLE }
            return null
        } catch (e: Exception) {
            Timber.e(e)
            synchronized(socketMutex) { socketState = SocketState.IDLE }
            return null
        }

        return ensureScope().launch {
            try {
                val request = Request.Builder()
                    .url(webSocketEndPoint.toString())
                    .build()
                synchronized(socketMutex) {
                    socket = client.newWebSocket(request, this@WebSocketHandler)
                }
            } catch (e: Exception) {
                Timber.e(e)
                synchronized(socketMutex) { socketState = SocketState.IDLE }
                scheduleReconnect()
            }
        }
    }

    /**
     * dispose() cancels the scope, but Koin keeps this singleton alive and
     * JobScheduler (WebSocketService.onStopJob returns true) restarts it later.
     * Revive the scope instead of silently dropping reconnects forever.
     */
    private fun ensureScope(): CoroutineScope {
        if (!mainJob.isActive) {
            synchronized(socketMutex) {
                if (!mainJob.isActive) {
                    mainJob = SupervisorJob()
                    webSocketScope = CoroutineScope(Dispatchers.IO) + mainJob
                }
            }
        }
        return webSocketScope
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        super.onMessage(webSocket, bytes)
        Timber.i("GOT MESSAGE ")
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Timber.i("onClosed")
        onTerminal(webSocket)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Timber.i("onFailure ${t.message}")
        Timber.e(t)
        onTerminal(webSocket)
    }

    /**
     * Single funnel for both terminal callbacks. A reconnect is scheduled
     * only for the current socket: previously every parallel socket's
     * onClosed called connect() directly, which is how duplicates stacked
     * into the 4-socket subscription pattern (#41).
     */
    private fun onTerminal(webSocket: WebSocket) {
        val isCurrent = synchronized(socketMutex) {
            if (webSocket !== socket) return
            socketState = SocketState.IDLE
            true
        }
        if (!isCurrent) return
        socketStatus = Status.DISCONNECTED
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val delayMs = reconnectPolicy.nextDelayMs()
        Timber.i("Scheduling websocket reconnect in ${delayMs}ms")
        val scope = ensureScope()
        synchronized(socketMutex) {
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs)
                connect(reason = "backoff")
            }
        }
    }

    /**
     * Tear down the current socket and reconnect immediately, bypassing
     * backoff. For state transitions that guarantee a fresh attempt is
     * warranted (Tor circuit up, re-auth completed).
     */
    private fun resetAndReconnect(reason: String) {
        synchronized(socketMutex) {
            reconnectJob?.cancel()
            reconnectJob = null
            socket?.cancel()
            socket = null
            socketState = SocketState.IDLE
        }
        socketStatus = Status.DISCONNECTED
        reconnectPolicy.reset()
        connect(reason)
    }

    /**
     * Tear down the current socket and stop retrying entirely until an
     * external trigger (Tor back up, network back, UI connect) re-arms us.
     */
    private fun closeAndStop() {
        synchronized(socketMutex) {
            reconnectJob?.cancel()
            reconnectJob = null
            socket?.cancel()
            socket = null
            socketState = SocketState.IDLE
        }
        socketStatus = Status.DISCONNECTED
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val payload = runCatching { JSONObject(text) }.getOrElse {
            Timber.e(it, "Non-JSON websocket message, dropping")
            return
        }
        if (text.contains("Invalid JSON Web Token")) {
            // QA round 2 (PR #45): Dojo replies with one error frame PER
            // subscription, and every frame spawned its own auth+reset —
            // 15 reconnects in one second. Coalesce to one re-auth cycle,
            // and only reset if the offending socket is still the live one.
            if (!jwtReauthPending.compareAndSet(false, true)) return
            apiService.authenticateDojo().invokeOnCompletion { throwable ->
                jwtReauthPending.set(false)
                if (throwable != null) {
                    // Auth itself failed (offline / bad key): take the bounded
                    // backoff path instead of a hard-reset loop.
                    scheduleReconnect()
                    return@invokeOnCompletion
                }
                val stillLive = synchronized(socketMutex) { webSocket === socket }
                if (stillLive) {
                    // Stale credentials, not a network problem: reset the
                    // backoff and reconnect once fresh auth lands.
                    resetAndReconnect(reason = "jwt-refresh")
                }
            }
            return
        }
        if (payload.has("op") && payload.getString("op") == "utx") {
            showTxNotification(payload)
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        val isCurrent = synchronized(socketMutex) {
            if (webSocket !== socket) {
                false
            } else {
                socketState = SocketState.CONNECTED
                true
            }
        }
        if (!isCurrent) {
            Timber.w("onOpen for superseded socket, cancelling it")
            webSocket.cancel()
            return
        }
        reconnectPolicy.reset()
        socketStatus = Status.CONNECTED
        subscribeBlocks(webSocket)
        subscribeNewTx(webSocket)
    }

    private fun subscribeNewTx(webSocket: WebSocket) {
        collectionRepo.collectionsSnapshot().forEach { pubKeyCollection ->
            pubKeyCollection.pubs.forEach {
                val payload = JSONObject().apply {
                    put("op", "addr_sub")
                    put("addr", it.pubKey)
                    addToken()
                }.toString()
                val item = webSocket.send(payload)
                Timber.d("SubscribeTx status:$item, payload:$payload")
            }
        }
    }

    private fun subscribeBlocks(webSocket: WebSocket) {
        try {
            val payload = JSONObject().apply {
                put("op", "blocks_sub")
                addToken()
            }.toString()
            val item = webSocket.send(payload)
            Timber.d("SubscribeBlocks status:$item, payload:$payload")
        } catch (er: Exception) {
            Timber.e(er)
        }
    }

    fun refreshSubscription() {
        val currentSocket = synchronized(socketMutex) { socket }
        if (currentSocket != null) {
            subscribeNewTx(currentSocket)
        } else {
            if (socketState == SocketState.IDLE) {
                connect()
            }
        }
    }

    private fun showTxNotification(payload: JSONObject) {
        try {
            val tx = fromJSON<Tx>(payload.getString("x")) ?: return

            var amount = 0L

            tx.inputs.forEach {
                if (it.prev_out != null)
                    it.prev_out.value.let { value ->
                        amount -= value
                    }
            }
            tx.out.forEach {
                amount += it.value
            }

            //Don't send notification when it's an outgoing tx
            if (amount <= 0)
                return

            val notificationManager = NotificationManagerCompat.from(context)
            val mBuilder = NotificationCompat.Builder(context, "PAYMENTS_CHANNEL")
                .setSmallIcon(R.drawable.ic_sentinel)
                .setContentTitle("Payment received")
                .setContentText("Amount ${monetaryUtil.formatToBtc(amount)} BTC")
                .setTicker("Payment received")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
            val notifyIntent: Intent = Intent(context, HomeActivity::class.java)
            val intent = PendingIntent.getActivity(
                context, 0, notifyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            mBuilder.setContentIntent(intent)
            notificationManager.notify(tx.locktime, mBuilder.build())

            //Get all associated keys with current tx
            //Collection that associated with any of these keys will refreshed
            val keys = tx.inputs.map { it.prev_out?.xpub?.m }.toMutableList()
            keys.addAll(tx.out.map { it.xpub?.m })
            keys.addAll(tx.inputs.map { it.prev_out?.addr })
            keys.addAll(tx.out.map { it.addr })

            collectionRepo.collectionsSnapshot().forEach {
                it.pubs.forEach { pubKeyModel ->
                    if (keys.contains(pubKeyModel.pubKey)) {
                        ensureScope().launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    transactionsRepository.fetchFromServer(it.id)
                                }
                            } catch (e: Exception) {
                                Timber.e(e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    fun dispose() {
        synchronized(socketMutex) {
            reconnectJob?.cancel()
            reconnectJob = null
            socket?.cancel()
            socket = null
            socketState = SocketState.IDLE
        }
        socketStatus = Status.DISCONNECTED
        if (mainJob.isActive) {
            mainJob.cancel("Dispose")
        }
    }

    private fun JSONObject.addToken() {
        if (SentinelState.isDojoEnabled()) {
            put("at", prefsUtil.authorization)
        }
    }
}
