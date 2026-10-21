package com.samourai.sentinel.ui.dojo

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.samourai.sentinel.R
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.GenericBottomSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject
import java.net.SocketTimeoutException

/**
 * Lists the community Dojos published by Dojo Bay (fetched live, over Tor) and
 * lets the user either pair directly to one or copy its pairing JSON.
 *
 * Only ever shown after the caller has put the user through
 * [DojoConfigureBottomSheet]'s privacy warning dialog - this sheet itself assumes
 * that consent has already been given.
 *
 * #210: no primary-constructor parameters anymore - the required lambda made
 * this class non-recreatable (Fragment$InstantiationException on process death
 * or a ui-mode change while the list was showing). The one construction site
 * (DojoConfigureBottomSheet, via childFragmentManager) keeps compiling through
 * the legacy bridge constructor, and a selection now resolves the host sheet at
 * click time - the #181 event-time pattern - so a restored list still pairs.
 */
class CommunityDojoListBottomSheet() : GenericBottomSheet() {

    /** #210 bridge for the pre-#210 `CommunityDojoListBottomSheet(onDojoSelected)` shape. */
    private var legacyOnDojoSelected: ((String) -> Unit)? = null

    constructor(onDojoSelected: (String) -> Unit) : this() {
        legacyOnDojoSelected = onDojoSelected
    }

    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var retryButton: MaterialButton

    private var fetchInFlight = false

    /** #135: ticks elapsed seconds into the status line while a fetch runs. */
    private var elapsedTicker: Job? = null

    /**
     * #135: the dominant directory latency is Dojo Bay's own probing of every
     * listed node (server-side, unshortenable from here). A client-side 120 s
     * timeout therefore does NOT mean the work was wasted - the operator
     * measured attempt 2 answering in ~3 s right after a 120 s timeout, the
     * signature of a warmed probe cache. One automatic retry converts the
     * worst first-run experience into a self-healing one; a second timeout
     * surfaces the plain-language message instead of raw exception text.
     */
    private companion object {
        const val AUTO_PROBE_RETRIES = 1
        const val AUTO_PROBE_RETRY_DELAY_MS = 3_000L
    }

    private val adapter = CommunityDojoAdapter(
        onSelect = { node ->
            val json = node.pairingPayloadJson()
            if (json == null) {
                Toast.makeText(requireContext(), "Invalid pairing details for this Dojo", Toast.LENGTH_SHORT).show()
            } else {
                // Snapshot directory display metadata now: past this callback
                // only the pairing JSON travels, and the stored payload has no
                // name/flag/jurisdiction. The node object is already in memory
                // from the directory fetch (over Tor) - nothing new is requested.
                prefsUtil.dojoDisplayName = node.name?.takeIf { it.isNotBlank() }
                prefsUtil.dojoDisplayFlag = node.flagEmoji
                prefsUtil.dojoDisplayUrl = node.payload?.pairing?.url
                // #256: jurisdiction rides the same snapshot - a directory
                // datum, display-only, cleared with the pairing (see
                // DojoUtility.clearDojo).
                prefsUtil.dojoDisplayJurisdiction = node.jurisdiction?.takeIf { it.isNotBlank() }
                // #210: resolve the host sheet at click time - the old
                // constructor lambda died with the instance on every
                // recreation. The configure sheet's existing #181 entry point
                // takes it from here (validate -> payload -> connect page,
                // its own toast on invalid); the legacy lambda remains as the
                // fallback for any host that is not the configure sheet.
                val host = parentFragment as? DojoConfigureBottomSheet
                if (host != null) {
                    host.onScanPayload(json)
                } else {
                    legacyOnDojoSelected?.invoke(json)
                }
                dismiss()
            }
        },
        onCopy = { node -> copyPairingJson(node) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottomsheet_community_dojo_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.communityDojoToolbar)
        recyclerView = view.findViewById(R.id.communityDojoRecyclerView)
        progressBar = view.findViewById(R.id.communityDojoProgress)
        statusText = view.findViewById(R.id.communityDojoStatusText)
        retryButton = view.findViewById(R.id.communityDojoRetryButton)

        toolbar.setNavigationOnClickListener { dismiss() }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        retryButton.setOnClickListener { connectTorThenFetch() }

        connectTorThenFetch()
    }

    private fun connectTorThenFetch() {
        showLoading(getString(R.string.community_dojo_connecting_tor))
        // State can say ON from a stale LiveData while the proxy object
        // is not (yet) available; require both to take the fast path.
        // #135: ON alone is NOT enough - it is published at boot=5, before
        // the SOCKS listener exists and before boot=100. Ask the manager for
        // full readiness so a fresh start cannot fetch through a proxy-less
        // or circuit-less client.
        if (SentinelTorManager.isTorReady()) {
            fetchDirectory()
            return
        }
        SentinelTorManager.setUp(requireContext().applicationContext as Application)
        // #170: directory-fetch start, no stomp on the user's toggle.
        SentinelTorManager.start()
        SentinelTorManager.getTorStateLiveData().observe(viewLifecycleOwner) { state ->
            when (state.state) {
                EnumTorState.ON -> {
                    // #135: this sheet has no transition dedupe, so every ON
                    // emission of the 5->100 climb used to call
                    // fetchDirectory() at boot=5-ish. Await full readiness
                    // instead (returns false on OFF/STOPPING, letting the
                    // OFF handler below own the failure); the fetchInFlight
                    // guard collapses any overlapping ready emissions.
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (SentinelTorManager.awaitTorReady()) {
                            fetchDirectory()
                        }
                    }
                }
                EnumTorState.OFF ->
                    // Tor stopped while the sheet is open; surface a
                    // retryable error instead of spinning forever.
                    showMessage(
                        getString(R.string.community_dojo_error, "Tor stopped"),
                        canRetry = true
                    )
                else -> Unit // STARTING/STOPPING: keep waiting
            }
        }
    }

    /**
     * #135: timeout-shaped failures (120 s callTimeout or socket read
     * timeout) get the plain-language probe message; everything else keeps
     * the detailed error string.
     */
    private fun isProbeTimeout(e: Exception): Boolean =
        e is SocketTimeoutException ||
            e.message?.contains("timeout", ignoreCase = true) == true ||
            e.message?.contains("timed out", ignoreCase = true) == true

    private fun fetchDirectory() {
        if (fetchInFlight) return
        fetchInFlight = true
        viewLifecycleOwner.lifecycleScope.launch {
            var attempt = 0
            while (true) {
                attempt++
                startElapsedTicker()
                try {
                    val nodes = CommunityDojoRepository.fetchDirectory()
                    // The directory fetch may take up to 120s and can complete
                    // after the user has dismissed this sheet. Touching the
                    // fragment context or its views at that point crashes with
                    // IllegalStateException (not attached to a context), so
                    // bail out whenever we are no longer attached.
                    if (!isAdded) {
                        fetchInFlight = false
                        stopElapsedTicker()
                        return@launch
                    }
                    val network = if (prefsUtil.testnet == true) "testnet" else "mainnet"
                    val filtered = nodes
                        .filter { it.network.equals(network, ignoreCase = true) }
                        .sortedWith(
                            compareByDescending<CommunityDojoNode> { it.isOnline }
                                .thenBy { it.name.orEmpty().lowercase() }
                        )
                    fetchInFlight = false
                    stopElapsedTicker()
                    if (filtered.isEmpty()) {
                        showMessage(getString(R.string.community_dojo_empty, network), canRetry = true)
                    } else {
                        showList(filtered)
                    }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    stopElapsedTicker()
                    val timeout = isProbeTimeout(e)
                    if (timeout && attempt <= AUTO_PROBE_RETRIES) {
                        // The first attempt's server-side probing is not
                        // wasted - retry once before giving up (operator
                        // datum: 120 s timeout then ~3 s success).
                        if (!isAdded) {
                            fetchInFlight = false
                            return@launch
                        }
                        showLoading(getString(R.string.community_dojo_auto_retry))
                        delay(AUTO_PROBE_RETRY_DELAY_MS)
                        if (!isAdded) {
                            fetchInFlight = false
                            return@launch
                        }
                        continue
                    }
                    fetchInFlight = false
                    if (!isAdded) {
                        return@launch
                    }
                    showMessage(
                        if (timeout) getString(R.string.community_dojo_probe_timeout)
                        else getString(R.string.community_dojo_error, e.message ?: e.toString()),
                        canRetry = true
                    )
                    return@launch
                }
            }
        }
    }

    /** #135: 1 s ticks into the status line so the wait stays legible. */
    private fun startElapsedTicker() {
        stopElapsedTicker()
        // First paint: probing copy with "(0 s elapsed)" at once,
        // instead of leaving the previous label up for the first tick.
        statusText.text =
            getString(R.string.community_dojo_fetching_elapsed, 0)
        val startedAt = SystemClock.elapsedRealtime()
        elapsedTicker = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(1_000)
                // Same attachment rule as the fetch completion path: never
                // touch views once the sheet is gone.
                if (!isAdded) return@launch
                val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
                statusText.text =
                    getString(R.string.community_dojo_fetching_elapsed, seconds)
            }
        }
    }

    private fun stopElapsedTicker() {
        elapsedTicker?.cancel()
        elapsedTicker = null
    }

    private fun showLoading(message: String) {
        progressBar.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = message
        retryButton.visibility = View.GONE
        recyclerView.visibility = View.GONE
    }

    private fun showMessage(message: String, canRetry: Boolean) {
        stopElapsedTicker()
        progressBar.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = message
        retryButton.visibility = if (canRetry) View.VISIBLE else View.GONE
        recyclerView.visibility = View.GONE
    }

    private fun showList(nodes: List<CommunityDojoNode>) {
        stopElapsedTicker()
        progressBar.visibility = View.GONE
        statusText.visibility = View.GONE
        retryButton.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        adapter.submitList(nodes)
    }

    private fun copyPairingJson(node: CommunityDojoNode) {
        val json = node.pairingPayloadJson()
        if (json == null) {
            Toast.makeText(requireContext(), "No pairing JSON available for this Dojo", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Dojo pairing payload", json))
        Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }
}
