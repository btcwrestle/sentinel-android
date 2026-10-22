package com.samourai.sentinel.tor

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.matthewnelson.kmp.file.resolve
import io.matthewnelson.kmp.tor.resource.noexec.tor.ResourceLoaderTorNoExec
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.config.IntervalUnit
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.core.util.executeAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Proxy
import timber.log.Timber

/**
 * kmp-tor 2.x wrapper. Frozen public surface (all call sites unchanged):
 * setUp / start / stop / newIdentity / getTorState / getTorStateLiveData / getProxy.
 * #135 adds isTorReady()/awaitTorReady() - additive only, no existing call
 * site changes shape.
 * #161 adds getNewNymNoticeLiveData()/ackNewNymNotice() - additive only,
 * NEWNYM feedback routing; no existing call site changes shape.
 */
object SentinelTorManager {

    private const val TAG = "SentinelTorManager"

    // ---- #11/#172 watchdog: tiered zero-progress stall recovery ----
    private const val CHEAP_RESTART_MS = 15_000L    // tier 1: restart, data dirs KEPT
    private const val WIPE_STALL_MS = 35_000L       // tier 2: TOTAL stall budget, then wipe
    private const val MAX_RECOVERIES = 2            // counts wipes only - cheap restarts are free
    private const val STOP_DEADLINE_MS = 15_000L    // wedged daemon must die within this
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastBootstrapProgressAt = 0L
    @Volatile private var recoveries = 0
    @Volatile private var cheapRestartUsed = false  // #172: once per stall episode
    @Volatile private var stopInFlight = false      // #164: one owning stop launch at a time
    @Volatile private var watchdogJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var appContext: Application? = null
    @Volatile private var runtime: TorRuntime? = null
    @Volatile private var proxy: Proxy? = null

    private val torStateLiveDataPrivate = MutableLiveData(makeState(EnumTorState.OFF, 0))

    // #161: NEWNYM outcome stream - tor's verbatim notice (success or
    // rate-limit advisory) or the local failure literal. A listening
    // screen renders it on the themed snackbar; with no active listener
    // the emitter falls back to the framework toast. Null is the ack.
    private val newNymNoticePrivate = MutableLiveData<String?>(null)

    fun getTorStateLiveData(): LiveData<TorState> = torStateLiveDataPrivate

    /** #161: consuming screen acks so a config-change re-subscribe replays nothing. */
    fun getNewNymNoticeLiveData(): LiveData<String?> = newNymNoticePrivate

    fun ackNewNymNotice() {
        newNymNoticePrivate.value = null
    }

    @Synchronized
    fun setUp(app: Application) {
        if (runtime != null) return // idempotent: bottom sheets re-call setUp
        Timber.i("setUp() building runtime")
        appContext = app

        val env = TorRuntime.Environment.Builder(
            app.getDir("tor", Application.MODE_PRIVATE),
            app.getDir("tor_cache", Application.MODE_PRIVATE),
            ResourceLoaderTorNoExec::getOrCreate,
        )

        runtime = TorRuntime.Builder(env) {

            // kmp-tor contract: register an ERROR observer or it rethrows
            observerStatic(RuntimeEvent.ERROR) { t ->
                Timber.e(t, "kmp-tor error")
            }

            observerStatic(RuntimeEvent.STATE, OnEvent.Executor.Immediate) { s ->
                val st = when {
                    s.daemon.isOn -> EnumTorState.ON
                    s.daemon.isStarting -> EnumTorState.STARTING
                    s.daemon.isStopping -> EnumTorState.STOPPING
                    else -> EnumTorState.OFF
                }
                Timber.i("state -> $st boot=${s.daemon.bootstrap.toInt()}")
                publish(st, s.daemon.bootstrap.toInt())
            }

            observerStatic(RuntimeEvent.LISTENERS, OnEvent.Executor.Immediate) { l ->
                proxy = l.socks.firstOrNull()?.let { s ->
                    // #135: timestamp t(socks-ready) so captures can prove
                    // readiness instead of inferring it.
                    Timber.i("socks ready ${s.address.value}:${s.port.value}")
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress(s.address.value, s.port.value),
                    )
                }
            }

            config { environment ->
                TorOption.__SocksPort.configure { auto() }
                TorOption.ConnectionPadding.configure { disable() }
                TorOption.ReducedConnectionPadding.configure(true)
                TorOption.DormantClientTimeout.configure(10, IntervalUnit.MINUTES)
                TorOption.DormantCanceledByStartup.configure(true)
                TorOption.ClientOnionAuthDir.configure(
                    directory = environment.workDirectory
                        .resolve("auth_private_files"),
                )
            }
        }

        // Route tor's NEWNYM reply to the #161 single feedback surface.
        // Rate-limit notices arrive verbatim (tor's own text); the 1.x
        // library's kmp_tor_newnym_* res strings no longer exist, so
        // success uses a local literal. emitNewNymNotice renders it on
        // the themed snackbar when a screen is listening, else the
        // framework toast - the notice is never lost.
        with(RuntimeEvent.EXECUTE.CMD) {
            runtime?.observeSignalNewNym(TAG, OnEvent.Executor.Main) { notice ->
                emitNewNymNotice(notice ?: "New Tor identity")
            }
        }
    }

    /** Public entry: user-directed start. Resets the retry budget + episode state. */
    fun start() {
        Timber.i("start() (manual)")
        recoveries = 0
        cheapRestartUsed = false
        startInternal()
    }

    /**
     * Recovery-safe start: never touches the retry budget.
     * [resetStallClock]=false is the #172 cheap-restart path: the episode
     * clock keeps running so WIPE_STALL_MS stays a TOTAL budget (15s tier 1
     * -> 35s tier 2 = 20s between), not 15s + 35s.
     */
    private fun startInternal(resetStallClock: Boolean = true) {
        val r = runtime
        if (r == null) {
            Timber.w("start() called but runtime is null (setUp never ran)")
            return
        }
        Timber.i("start() called")
        if (resetStallClock) {
            lastBootstrapProgressAt = SystemClock.elapsedRealtime()
        }
        publish(EnumTorState.STARTING, 0)
        armWatchdog()
        scope.launch {
            try {
                r.startDaemonAsync()
                Timber.i("startDaemonAsync returned")
            } catch (t: Throwable) {
                Timber.e(t, "startDaemonAsync threw")
                publish(EnumTorState.OFF, 0)
            }
        }
    }

    /**
     * #164: a stop() that lands posthumously (daemon already dead - the
     * erase flow's double stop) or on a hung shutdown used to wedge the
     * LiveData at STOPPING for process lifetime: STOPPING was published
     * unconditionally and nothing guaranteed a correcting OFF (the STATE
     * observer emits nothing once the daemon is dead; repro 3 hung 258s).
     * Now a posthumous/duplicate call no-ops, and the owning launch
     * publishes OFF once stopDaemonAsync resolves or the deadline passes.
     */
    @Synchronized
    fun stop() {
        Timber.i("stop() called")
        val r = runtime ?: return
        val st = getTorState().state
        if (st == EnumTorState.OFF || st == EnumTorState.STOPPING || stopInFlight) {
            Timber.i("stop() no-op: state=$st inFlight=$stopInFlight (#164 guard)")
            return
        }
        stopInFlight = true
        publish(EnumTorState.STOPPING, 0)
        watchdogJob?.cancel()
        scope.launch {
            try {
                try {
                    withTimeoutOrNull(STOP_DEADLINE_MS) { r.stopDaemonAsync() }
                } catch (t: Throwable) {
                    Timber.w(t, "stopDaemonAsync threw (tolerated)")
                }
                if (getTorState().state == EnumTorState.STOPPING) {
                    Timber.w("stop(): no OFF emission landed - correcting to OFF (#164)")
                    publish(EnumTorState.OFF, 0)
                }
            } finally {
                stopInFlight = false
            }
        }
    }

    fun newIdentity() {
        val r = runtime
        if (r == null) {
            // #161: silence was the last dishonesty on this surface -
            // the failure reports through the same single channel.
            Timber.w("newIdentity() called but runtime is null (setUp never ran)")
            emitNewNymNotice("Tor identity change failed")
            return
        }
        scope.launch {
            try {
                r.executeAsync(TorCmd.Signal.NewNym)
            } catch (t: Throwable) {
                Timber.e(t, "newIdentity failed")
                emitNewNymNotice("Tor identity change failed")
            }
        }
    }

    fun getTorState(): TorState =
        torStateLiveDataPrivate.value ?: makeState(EnumTorState.OFF, 0)

    fun getProxy(): Proxy? = proxy

    /**
     * #135: full readiness for reaching the dojo's onion service through
     * SOCKS. boot=100 alone certifies only the 3-hop public circuit; the
     * SOCKS listener must also be published so buildClient() attaches a
     * live proxy instead of leaving OkHttp to resolve .onion via system
     * DNS (38 UHE in capture 1010-133246, all before T100 at line 659).
     */
    fun isTorReady(): Boolean {
        val st = getTorState()
        return st.state == EnumTorState.ON &&
            st.progressIndicator == 100 &&
            proxy != null
    }

    /**
     * Suspends until [isTorReady], the daemon leaves the STARTING/ON
     * lifecycle (OFF/STOPPING), or [timeoutMs] elapses. [timeoutMs] null
     * waits unboundedly (still bounded by OFF/STOPPING and caller-scope
     * cancellation); callers with a wall-clock budget pass a finite value.
     * Returns true only when all three readiness conditions hold.
     */
    suspend fun awaitTorReady(timeoutMs: Long? = null): Boolean {
        val deadline = timeoutMs?.let { SystemClock.elapsedRealtime() + it }
            ?: Long.MAX_VALUE
        while (SystemClock.elapsedRealtime() < deadline) {
            val st = getTorState()
            if (st.state == EnumTorState.ON &&
                st.progressIndicator == 100 &&
                proxy != null
            ) return true
            if (st.state == EnumTorState.OFF || st.state == EnumTorState.STOPPING) {
                return false
            }
            delay(250L)
        }
        return isTorReady()
    }

    private fun makeState(state: EnumTorState, progress: Int): TorState =
        TorState().apply {
            this.state = state
            this.progressIndicator = progress
        }

    private fun publish(state: EnumTorState, progress: Int) {
        if (progress > 0 || state == EnumTorState.ON) {
            lastBootstrapProgressAt = SystemClock.elapsedRealtime()
        }
        torStateLiveDataPrivate.postValue(makeState(state, progress))
    }

    private fun armWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                // #172: 5s tick so the 15s tier lands at 15-20s, not ~20-25s
                delay(5_000L)
                val st = getTorState()
                if (st.state == EnumTorState.ON || st.state == EnumTorState.OFF) return@launch
                val stalledMs = SystemClock.elapsedRealtime() - lastBootstrapProgressAt
                if (st.state == EnumTorState.STARTING && st.progressIndicator == 0) {
                    if (stalledMs > WIPE_STALL_MS) {
                        attemptRecovery() // tier 2: total budget spent - wipe + rebuild
                        return@launch
                    }
                    if (stalledMs > CHEAP_RESTART_MS && !cheapRestartUsed) {
                        cheapRestart() // tier 1: at most once per stall episode
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * #172 tier 1: stop + start on the SAME runtime, both data dirs KEPT
     * (consensus/descriptors/guard state survive - a false positive costs
     * ~nothing). Stop-first so startDaemonAsync's dedupe cannot no-op it
     * (same pattern as attemptRecovery). Episode clock keeps running:
     * WIPE_STALL_MS is a total budget, not 15s + 35s.
     */
    private fun cheapRestart() {
        cheapRestartUsed = true
        Timber.w(
            "watchdog: boot=0 stall > ${CHEAP_RESTART_MS / 1000}s — " +
                "cheap restart (data dirs kept)",
        )
        toast("Tor bootstrap stalled — restarting (data kept)")
        scope.launch {
            val wedged = runtime ?: return@launch
            try {
                withTimeoutOrNull(STOP_DEADLINE_MS) { wedged.stopDaemonAsync() }
            } catch (t: Throwable) {
                Timber.w(t, "stopDaemonAsync during cheap restart threw (tolerated)")
            }
            startInternal(resetStallClock = false) // re-arms the watchdog
        }
    }

    private fun attemptRecovery() {
        val app = appContext
        if (app == null || recoveries >= MAX_RECOVERIES) {
            Timber.e(
                "watchdog: still wedged after $recoveries recovery attempt(s) — publishing OFF",
            )
            toast("Tor failed to bootstrap — retry Tor from settings")
            publish(EnumTorState.OFF, 0) // UI offers retryable error, not eternal "initializing"
            return
        }
        recoveries++
        Timber.w(
            "watchdog: boot=0 stall > ${WIPE_STALL_MS / 1000}s — " +
                "wiping tor data, recovery $recoveries/$MAX_RECOVERIES",
        )
        toast("Tor bootstrap stalled — auto-recovering ($recoveries/$MAX_RECOVERIES)")
        scope.launch {
            val wedged = runtime
            try {
                // stop FIRST: startDaemonAsync is deduped while the wedged daemon
                // is still "starting" — observed as a no-op in the #11 captures
                withTimeoutOrNull(STOP_DEADLINE_MS) { wedged?.stopDaemonAsync() }
            } catch (t: Throwable) {
                Timber.w(t, "stopDaemonAsync during recovery threw (tolerated)")
            }
            runtime = null // release the wedged runtime
            app.getDir("tor", Application.MODE_PRIVATE).deleteRecursively()
            app.getDir("tor_cache", Application.MODE_PRIVATE).deleteRecursively()
            setUp(app)      // rebuild against fresh dirs (idempotent guard passes on null)
            cheapRestartUsed = false // fresh episode: tier 1 available again
            startInternal() // re-arms the watchdog; budget preserved
        }
    }

    /**
     * #161: the single NEWNYM feedback surface. A listening screen gets
     * the notice on the stream (NetworkActivity renders the themed
     * snackbar with the verbatim text); with no active listener the
     * framework toast carries it - failure and null-runtime arms
     * included, which used to be silent.
     */
    private fun emitNewNymNotice(msg: String) {
        if (newNymNoticePrivate.hasActiveObservers()) {
            newNymNoticePrivate.postValue(msg)
        } else {
            toast(msg)
        }
    }

    private fun toast(msg: String) {
        mainHandler.post {
            appContext?.let { Toast.makeText(it, msg, Toast.LENGTH_LONG).show() }
        }
    }
}
