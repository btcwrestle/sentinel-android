package com.samourai.sentinel.ui.dojo

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.invertedx.hummingbird.QRScanner
import com.samourai.sentinel.R
import com.samourai.sentinel.api.TlsPolicy
import com.samourai.sentinel.databinding.FragmentBottomsheetViewPagerBinding
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.shouldAdmitScan
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.koin.java.KoinJavaComponent
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

// ---- #233: the pairing network path, made pure ----
//
// The deleted line in setDojo() was:
//   OkHttpClient.Builder().proxy(SentinelTorManager.getProxy()).build()
// getProxy() returns null until the SOCKS listener is published, and ON
// alone is published at boot=5, before the listener exists (#135).
// OkHttp treats proxy(null) as the DEFAULT route: DIRECT - so the
// pairing POST (/auth/login, apikey in the body) left via the device's
// normal network while the UI said Tor. The three arms below are the
// sheet-side mirror of the gates that already protect
// ApiService.buildClient (:419) and WebSocketHandler.connect() (:187):
// never build a proxy-less client, never cache readiness.

// #233: bounded wait for the SOCKS listener, mirroring HomeViewModel's
// TOR_READY_TIMEOUT_MS (covers the measured ~50 s cold start with
// margin). On timeout the pairing fails closed - no proxy-less POST,
// ever.
private const val TOR_READY_TIMEOUT_MS = 90_000L

/**
 * #233: what setDojo() may do with the network, decided at call time.
 * Top-level so the negative test can pin the ON-but-not-ready window
 * without loading any Android machinery.
 */
enum class PairingTorDecision { POST_NOW, DEFER, FAIL_CLOSED }

/**
 * #233: pure decision for the pairing call path. ON-but-not-ready
// DEFERs - that is the exact window the old code POSTed direct in.
 * OFF/STOPPING is a dead daemon: retrying it is spin, so the sheet
 * fails closed (toast + dismiss, no re-enqueue; the Tor watchdog owns
 * restarting the daemon).
 */
fun pairingTorDecision(
    state: EnumTorState,
    boot: Int,
    proxy: Proxy?,
): PairingTorDecision = when {
    state == EnumTorState.ON && boot == 100 && proxy != null ->
        PairingTorDecision.POST_NOW
    state == EnumTorState.ON || state == EnumTorState.STARTING ->
        PairingTorDecision.DEFER
    else -> PairingTorDecision.FAIL_CLOSED
}

/**
 * #233: the pairing client. Takes a NON-NULL SOCKS proxy at the
 * signature - the direct-POST route is gone from the type; only a
 * POST_NOW decision can reach this. Installs the #229 host-scoped TLS
 * policy (https-onion Dojos with self-signed certs were structurally
 * unpairable through the old one-off client) and mirrors the Tor-mode
 * timeouts of ApiService's ON arm: OkHttp's default 10 s callTimeout
 * kills an onion circuit build that can legitimately take minutes.
 */
fun buildPairingClient(torProxy: Proxy): OkHttpClient {
    val builder = OkHttpClient.Builder()
    TlsPolicy.install(builder)
    builder.callTimeout(0, TimeUnit.MILLISECONDS)
    builder.readTimeout(180, TimeUnit.SECONDS)
    builder.writeTimeout(180, TimeUnit.SECONDS)
    builder.connectTimeout(180, TimeUnit.SECONDS)
    builder.proxy(torProxy)
    return builder.build()
}

// ---- #234: the pairing acknowledgment, made pure ----
//
// The success arm of setDojoPost used to commit the pairing inline
// (prefs, tokens, payload store) and auto-advance: dismissAllOrToast
// fired the listener 500 ms later and NetworkActivity started the xpub
// registration burst - no user gate anywhere between the 200 and the
// burst. Pairing is a trust decision (all wallet traffic flows to that
// node from then on), so the commit now waits for an explicit tap, and
// the gate below is the whole decision table. It is also the seam
// #250's network guard rides: a cross-network payload is another
// decision that belongs before the first write, not after it.

/**
 * #234: what the sheet may do around the pairing acknowledgment.
 * Top-level so the negative tests can pin the double-tap and
 * tap-after-decline windows without loading any Android machinery.
 */
enum class PairingAckDecision { SHOW_CONFIRMATION, COMMIT_AND_DISMISS, DISCARD_AND_DISMISS, NOOP }

/**
 * #234: pure transition table for the pairing acknowledgment.
 * Success arms SHOW_CONFIRMATION exactly once - a re-fire landing
 * while the dialog is up is a NOOP, so there is one dialog and one
 * decision per pairing. CONFIRM commits and dismisses exactly once;
 * DECLINE discards and dismisses exactly once. Either terminal tap
 * spends the gate: the dialog dismisses on the first tap, so any
 * second tap - and any stray success that lands after the user has
 * decided - resolves to NOOP. The user's decision, once made, stands.
 * Synchronized because the arm arrives on the OkHttp dispatcher
 * thread while the taps arrive on main.
 */
class PairingAckGate {
    private var awaitingConfirmation = false
    private var spent = false

    @Synchronized
    fun onPairingSuccess(): PairingAckDecision = when {
        spent || awaitingConfirmation -> PairingAckDecision.NOOP
        else -> {
            awaitingConfirmation = true
            PairingAckDecision.SHOW_CONFIRMATION
        }
    }

    @Synchronized
    fun onUserConfirmed(): PairingAckDecision = when {
        awaitingConfirmation && !spent -> {
            spent = true
            PairingAckDecision.COMMIT_AND_DISMISS
        }
        else -> PairingAckDecision.NOOP
    }

    @Synchronized
    fun onUserDeclined(): PairingAckDecision = when {
        awaitingConfirmation && !spent -> {
            spent = true
            PairingAckDecision.DISCARD_AND_DISMISS
        }
        else -> PairingAckDecision.NOOP
    }
}

/**
 * #234: the confirmation dialog's summary lines, derived from the
 * payload. The apikey is deliberately absent - it grants access to
 * the node (see DojoUtility.getPairing's FLAG_SECURE note). The
 * pairing type is invariant post-validate ("dojo.api" or the payload
 * never got this far), so the lines show what actually varies: the
 * URL, the declared version, and the sibling explorer when present.
 */
fun pairingConfirmationLines(pairing: DojoPairing): List<String> {
    val lines = mutableListOf<String>()
    pairing.pairing?.url?.takeIf { it.isNotBlank() }?.let { lines.add(it) }
    pairing.pairing?.version?.takeIf { it.isNotBlank() }?.let { lines.add("Dojo $it") }
    pairing.explorer?.url?.takeIf { it.isNotBlank() }?.let { url ->
        val type = pairing.explorer?.type?.takeIf { it.isNotBlank() } ?: "explorer"
        lines.add("Explorer: $type - $url")
    }
    return lines
}

class DojoConfigureBottomSheet : GenericBottomSheet() {
    private var payloadPassed: String? = null;
    private var dojoConfigurationListener: DojoConfigurationListener? = null
    private val prefsUtil: PrefsUtil by KoinJavaComponent.inject(PrefsUtil::class.java);
    private var numRetries = 0

    // #233: single-flight for the bounded Tor wait. setDojo() re-enters
    // from the Tor observer and the #176 retry timer while Tor is still
    // bootstrapping; without this latch every not-ready entry would
    // stack its own awaitTorReady and the POST would fire once per
    // waiter at readiness.
    private var torDeferJob: Job? = null

    // #159: the dismissal seam. The listener fires at most once per
    // sheet lifetime, only from the success arm - the contract is
    // "pairing live, endpoints set". Every dismissal routes through
    // the main looper: no bare Handler() off a lucky call path, no
    // sleeps on OkHttp dispatcher threads.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listenerFired = false

    private var payload: String = ""

    // #234: the acknowledgment gate and the success arm's commit
    // inputs. Nothing commits until onUserConfirmed() resolves to
    // COMMIT_AND_DISMISS; decline never entered the commit, so there
    // is nothing to unwind. The mainHandler.post on the success arm
    // publishes these writes to the main thread before any read.
    private val pairingAckGate = PairingAckGate()
    private var pendingPairing: DojoPairing? = null
    private var pendingAuthBody: JSONObject? = null

    private val dojoUtil: DojoUtility by KoinJavaComponent.inject(DojoUtility::class.java);

    private var _binding: FragmentBottomsheetViewPagerBinding? = null
    private val binding get() = _binding!!

    // #231: the connect start latch. maybeStartConnect() is reachable
    // from two fires - the pager's page-selected callback and the
    // connect page's view-creation entry point - and this latch keeps
    // the pairing attempt once per sheet lifetime whichever lands.
    private var connectStarted = false

    // #231: registered in setUpViewPager, unregistered in onDestroyView.
    private var pagerCallback: ViewPager2.OnPageChangeCallback? = null

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentBottomsheetViewPagerBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpViewPager()
        // #181: no sheet-side page wiring anymore. Every page event
        // travels fragment -> sheet at event time: each page resolves
        // (parentFragment as? DojoConfigureBottomSheet) inside its own
        // click/scan/latch wiring (see the page classes below) and
        // calls the entry points grouped under
        // "#181 page-event entry points". The old design registered
        // listeners on pre-built field instances here; a FragmentStateAdapter
        // restore re-creates pages by tag from the childFragmentManager,
        // so after any recreation those fields held objects the pager
        // never showed - dead wiring, and no crash to report it.
        // #231: the connect trigger now rides pager page SELECTION
        // (setUpViewPager registers the callback); the connect page's
        // view-creation call is kept only as the restore path's race
        // cover - maybeStartConnect() gates both fires on selection
        // and payload.
        //validate payload that is passed
        payloadPassed?.let {
            if (dojoUtil.validate(it)) {
                payload = it
                binding.pager.setCurrentItem(2, true)
            } else {
                // #181: the old scanFragment.resetCamera() here no-op'd
                // (the scan page has no view at this moment) - dropped
                // with the field instances; the toast is the feedback.
                Toast.makeText(requireContext(), getString(R.string.invalid_payload), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        // #231: the page-selected callback dies with the pager's view.
        pagerCallback?.let { binding.pager.unregisterOnPageChangeCallback(it) }
        pagerCallback = null
        super.onDestroyView()
    }

    // ---- #181 page-event entry points ----
    // Called by the page fragments at event time. No listener fields
    // exist anywhere, so there is nothing to strand across a restore.

    /** Instructions page: CONNECT tapped - advance to the scan page. */
    fun onInstructionsConnectClicked() {
        binding.pager.setCurrentItem(1, true)
    }

    /** Instructions page: BROWSE tapped - the consent dialog gates the list. */
    fun onBrowseCommunityDojoClicked() {
        showCommunityDojoPrivacyWarning()
    }

    /** Scan page: PASTE tapped - clipboard pairing payload. */
    fun onManualPasteClicked() {
        val jsonDojo = AndroidUtil.getClipBoardString(requireContext())

        if (jsonDojo != null && dojoUtil.validate(jsonDojo!!)) {
            payload = jsonDojo
            binding.pager.setCurrentItem(2, true)
        } else
            Toast.makeText(requireContext(), getString(R.string.invalid_pairing_payload), Toast.LENGTH_SHORT).show()
    }

    /**
     * Scan page decoded a QR. True = payload accepted and the sheet
     * advanced to the connect page; false = invalid payload (the toast
     * lands here, where getString lives) and the caller resets its own
     * camera.
     */
    fun onScanPayload(scanned: String): Boolean {
        if (dojoUtil.validate(scanned)) {
            payload = scanned
            binding.pager.setCurrentItem(2, true)
            return true
        }
        Toast.makeText(requireContext(), getString(R.string.invalid_pairing_payload), Toast.LENGTH_SHORT).show()
        return false
    }

    /**
     * #231: the connect page's view exists. The gate below decides
     * whether that means anything - an offscreen bind during the pager
     * height walk has a view too, and must trigger nothing.
     */
    fun onConnectViewReady() {
        maybeStartConnect()
    }

    /**
     * #231: the connect gate - both fires (the pager's page-selected
     * callback and the connect page's view-creation entry point) land
     * here. Fires at most once per sheet lifetime, and only when page
     * 2 is actually SELECTED and a validated payload is in hand. A
     * blank payload routes back to the scan page: fail closed - no
     * banner, no network call. This is the piece that breaks the
     * re-pairing lockout: with Tor left ON from a failed attempt,
     * opening the sheet bound page 2 offscreen, and the old
     * view-creation trigger fired an empty-payload connect on the
     * spot.
     */
    private fun maybeStartConnect() {
        if (connectStarted) return
        if (binding.pager.currentItem != 2) return
        if (payload.isBlank()) {
            // #231: a restore that lands on page 2 has lost the
            // in-memory payload - the scan page, not an empty POST.
            binding.pager.setCurrentItem(1, true)
            return
        }
        connectStarted = true
        startTorAndConnect()
    }

    /**
     * #181: the live connect page, whenever it exists - built fresh by
     * createFragment, restored by tag by the adapter, never a sheet
     * field. All sheet -> page progress writes go through this lookup.
     */
    private fun connectPage(): DojoConnectFragment? =
        childFragmentManager.fragments.filterIsInstance<DojoConnectFragment>().firstOrNull()

    private fun startTorAndConnect() {
        if (SentinelTorManager.getTorState().state == EnumTorState.ON) {
            connectPage()?.showTorProgressSuccess()
            setDojo()
        } else {
            setUpTor()
            // #170: pairing start, no stomp on the user's toggle.
            SentinelTorManager.start()
            connectPage()?.showTorProgress()
            SentinelTorManager.getTorStateLiveData().observe(this) {
                if (it.state == EnumTorState.ON) {
                    if (connectPage()?.showTorProgressSuccess() == true) {
                        setDojo()
                    }
                }
            }
        }
    }

    /**
     * #233: the pairing gate. Every entry - connect latch, Tor
     * observer, #176 retry timer - re-decides at CALL TIME, because the
     * readiness inputs move under the sheet (ON@boot=5 -> listener ->
     * boot=100). The gate is the whole fix: the client is built only on
     * the POST_NOW arm, so it is never CONSTRUCTED in the
     * ON-but-not-ready state, let alone fired.
     */
    private fun setDojo() {
        when (pairingTorDecision(
            SentinelTorManager.getTorState().state,
            SentinelTorManager.getTorState().progressIndicator,
            SentinelTorManager.getProxy(),
        )) {
            PairingTorDecision.POST_NOW -> setDojoPost()
            PairingTorDecision.DEFER -> setDojoDeferUntilTorReady()
            PairingTorDecision.FAIL_CLOSED -> setDojoFailClosed()
        }
    }

    /**
     * #233: bounded wait for the SOCKS listener. Single-flight: a
     * second not-ready entry must not stack a second await.
     * lifecycleScope: dismissing the sheet cancels the wait, so no
     * orphan POST can land after teardown. On timeout or a dead
     * daemon: the final fail arm - the Tor watchdog owns restarting
     * the daemon, the sheet does not spin.
     */
    private fun setDojoDeferUntilTorReady() {
        if (torDeferJob?.isActive == true) return
        torDeferJob = lifecycleScope.launch {
            val ready = SentinelTorManager.awaitTorReady(TOR_READY_TIMEOUT_MS)
            if (ready) setDojoPost()
            else dismissAllOrToast(false)
        }
    }

    /**
     * #233: OFF/STOPPING (or the daemon died mid-flight). Not
     * retryable from the sheet - cancel any pending wait and take the
     * #159-style fail arm.
     */
    private fun setDojoFailClosed() {
        torDeferJob?.cancel()
        torDeferJob = null
        dismissAllOrToast(false)
    }

    private fun setDojoPost() {
        connectPage()?.showDojoProgress()
        try {
            val pairing = fromJSON<DojoPairing>(payload)
                ?: throw  Exception("Invalid payload")
            // #233: re-read at call time. The gate said POST_NOW, so
            // the proxy was live a moment ago; if the world moved
            // anyway, fail closed - never proxy(null), which is
            // OkHttp's DEFAULT route, i.e. DIRECT.
            val torProxy = SentinelTorManager.getProxy()
                ?: throw IllegalStateException("pairing path reached with no live SOCKS proxy (#233)")
            val client = buildPairingClient(torProxy)

            val requestBody = FormBody.Builder()
                .add("apikey", pairing.pairing?.apikey.toString())
                .build()

            val request = Request.Builder()
                .url(pairing.pairing!!.url!!.toString().plus("/auth/login"))
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: "{}"
                    if (response.code == 401 || (response.isSuccessful && !body.contains("authorizations"))) {
                        Log.d("DojoConfiguration", "Unauthorized: wrong API key")
                        dismissAllOrToast(false)
                    }
                    else if (response.isSuccessful) {
                        // #234: success arms the acknowledgment gate -
                        // nothing commits until the user taps. The gate
                        // takes SHOW_CONFIRMATION once; any re-fire that
                        // lands here while the dialog is already up
                        // resolves to NOOP. The commit body itself moved,
                        // verbatim, into onPairingConfirmTapped().
                        if (pairingAckGate.onPairingSuccess() == PairingAckDecision.SHOW_CONFIRMATION) {
                            pendingPairing = pairing
                            pendingAuthBody = JSONObject(body)
                            mainHandler.post { showPairingConfirmation() }
                        }
                    }  else {
                        dismissAllOrToast(false)
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    // e.message can be null; `!!` here crashed inside the failure handler.
                    // #176: this arm decides nothing on the OkHttp dispatcher
                    // thread anymore. Both failure kinds take the #159 capped
                    // retry arm - resolve-host included, which used to sleep
                    // 5s right here and re-enter setDojo() off-main, uncapped,
                    // for as long as the proxy lived. The numRetries cap in
                    // dismissAllOrToast bounds it now: three timer-driven
                    // retries on the main looper, then toast-and-dismiss.
                    // setDojo() is entered from main only (connect latch, Tor
                    // observer, retry timer).
                    mainHandler.post {
                        dismissAllOrToast(false, true)
                    }
                }
            })
        } catch (e: Exception) {
            // #159: a failed pairing must not fire the listener - the
            // contract is "pairing live, endpoints set"; firing on a
            // failure bursts stale state into the holders. Toast + dismiss
            // only, on the main looper from any caller thread
            // (setDojo is main-only post-#176; this post stays as
            // defense).
            mainHandler.post {
                dismiss()
                if (context != null)
                    Toast.makeText(context, getString(R.string.dojo_connect_error, e.toString()), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * #234: the acknowledgment dialog - what was paired, derived from
     * the payload via pairingConfirmationLines (URL, declared version,
     * sibling explorer), never the apikey (see DojoUtility.getPairing's
     * FLAG_SECURE note). Non-cancelable by design: an outside tap
     * silently discarding a pairing the user waited a full Tor
     * bootstrap for would be the auto-advance bug inverted; both
     * buttons resolve it. The connect page gets its success check
     * behind the dialog so the state is visible while the user
     * decides.
     */
    private fun showPairingConfirmation() {
        val pairing = pendingPairing ?: return
        if (context == null) return
        connectPage()?.showDojoProgressSuccess()
        val lines = pairingConfirmationLines(pairing)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dojo_pairing_confirm_title))
            .setMessage(
                getString(R.string.dojo_pairing_confirm_message) +
                        "\n\n" + lines.joinToString("\n")
            )
            .setCancelable(false)
            .setPositiveButton(getString(R.string.dojo_pairing_confirm_accept)) { _, _ ->
                onPairingConfirmTapped()
            }
            .setNegativeButton(getString(R.string.dojo_pairing_confirm_decline)) { _, _ ->
                onPairingDeclineTapped()
            }
            .show()
    }

    /**
     * #234: Confirm tapped - the commit the old success arm made
     * inline, now behind the explicit tap: payload, endpoints, tokens,
     * payload store, then the unchanged #159 dismissal seam
     * (showDojoProgressSuccess, 500 ms, listener at most once). The
     * gate spends itself on the first tap, so a double-tap that lands
     * before the dialog window closes resolves to NOOP and cannot run
     * this body twice.
     */
    private fun onPairingConfirmTapped() {
        if (pairingAckGate.onUserConfirmed() != PairingAckDecision.COMMIT_AND_DISMISS) return
        val pairing = pendingPairing ?: return
        val authBody = pendingAuthBody ?: return
        pendingPairing = null
        pendingAuthBody = null
        val node = pairing.pairing ?: return
        dojoUtil.setDojoPayload(payload)
        prefsUtil.apiEndPointTor = node.url
        prefsUtil.apiEndPoint = node.url
        prefsUtil.dojoExplorerUrl = pairing.explorer?.url
        prefsUtil.dojoExplorerType = pairing.explorer?.type
        dojoUtil.setAuthToken(authBody.toString())
        apiScope.launch {
            dojoUtil.writePayload(pairing);
            withContext(Dispatchers.Main) {
                dismissAllOrToast(true)
            }
        }
    }

    /**
     * #234: Decline tapped - discard and dismiss. The gate sits before
     * the first write, so nothing was ever committed: endpoints unset,
     * tokens unwritten, payload store untouched - there is nothing to
     * unwind. The listener never fires: the holders see a declined
     * pairing exactly the way they see a failed one (#159), and the
     * xpub registration burst never starts.
     */
    private fun onPairingDeclineTapped() {
        if (pairingAckGate.onUserDeclined() != PairingAckDecision.DISCARD_AND_DISMISS) return
        pendingPairing = null
        pendingAuthBody = null
        mainHandler.post {
            dismiss()
            if (context != null)
                Toast.makeText(context, getString(R.string.dojo_pairing_declined), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * #159: the dismissal seam, rebuilt. Success is the only
     * listener-firing arm, and it fires at most once per sheet lifetime
     * - the latch holds against any residual multi-fire. Failure toasts
     * and dismisses without firing: a failed pairing must not present
     * itself as a live one to the holders. Retries re-enqueue off a
     * main-looper timer instead of sleeping on an OkHttp thread.
     */
    fun dismissAllOrToast(wasResponseSuccessful: Boolean, retry: Boolean = false) {
        if (wasResponseSuccessful) {
            connectPage()?.showDojoProgressSuccess()
            mainHandler.postDelayed({
                if (!listenerFired) {
                    listenerFired = true
                    dojoConfigurationListener?.onDismiss()
                }
                dismiss()
            }, 500)
        }
        else if (retry && numRetries <= 2) {
            numRetries += 1
            mainHandler.postDelayed({ setDojo() }, 5000)
        }
        else {
            mainHandler.post {
                dismiss()
                if (context != null)
                    Toast.makeText(context, getString(R.string.dojo_connect_failed), Toast.LENGTH_LONG).show()
            }
        }
    }



    private fun showCommunityDojoPrivacyWarning() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.community_dojo_warning_title))
            .setMessage(getString(R.string.community_dojo_warning_message))
            .setPositiveButton(getString(R.string.community_dojo_continue)) { _, _ ->
                showCommunityDojoList()
            }
            .show()
    }

    private fun showCommunityDojoList() {
        CommunityDojoListBottomSheet { selectedPayload ->
            if (dojoUtil.validate(selectedPayload)) {
                payload = selectedPayload
                binding.pager.setCurrentItem(2, true)
            } else {
                Toast.makeText(requireContext(), getString(R.string.invalid_pairing_payload), Toast.LENGTH_SHORT).show()
            }
        }.show(childFragmentManager, "community_dojo_list")
    }

    private fun setUpTor() {
        SentinelTorManager.setUp(context?.applicationContext as Application)
        if (prefsUtil.enableTor == true) {
            SentinelTorManager.start()
        }
    }

    fun setDojoConfigurationListener(dojoConfigurationListener: DojoConfigurationListener?) {
        this.dojoConfigurationListener = dojoConfigurationListener
    }

    private fun setUpViewPager() {
        // #181: the FragmentStateAdapter contract owns construction -
        // createFragment must return a NEW instance per position. The
        // old code returned sheet fields here, desyncing the adapter's
        // restoration bookkeeping (pages restored by tag) from the
        // sheet's wiring (set on the fields) after any recreation.
        binding.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int {
                return 3
            }

            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> DojoNodeInstructions()
                    1 -> ScanFragment()
                    else -> DojoConnectFragment()
                }
            }

        }
        binding.pager.isUserInputEnabled = false

        // #231: the connect trigger rides user-visible page SELECTION.
        // The height walk below (item 0 -> 1 -> 0) binds page 2
        // offscreen as item 1's neighbor, and the #165 view-creation
        // trigger fired from that bind while the user was still on the
        // instructions page - with Tor already ON from a failed
        // attempt, that was the re-pairing lockout.
        val callback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == 2) {
                    maybeStartConnect()
                }
            }
        }
        pagerCallback = callback
        binding.pager.registerOnPageChangeCallback(callback)

        //Fix for making BottomSheet same height across all the fragments
        binding.pager.visibility = View.GONE
        binding.pager.currentItem = 1
        binding.pager.currentItem = 0
        binding.pager.visibility = View.VISIBLE
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        // #159: a cancel is not a pairing outcome - it fires nothing.
        // Success owns the only listener fire; every holder body
        // re-gates on endpoint/dojo state internally (read-verified:
        // NetworkActivity, HomeActivity, MigrationActivity), so nothing
        // strands on cancel silence.
    }

    fun setPayload(dojoPayload: String?) {
        this.payloadPassed = dojoPayload
    }

    interface DojoConfigurationListener {
        fun onDismiss()
    }
}

class DojoNodeInstructions : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bottomsheet_dojo_configure_instruction, container, false);
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // #181: resolve the sheet at click time - a stored listener
        // would be registered on whatever instance the sheet held,
        // which post-restore is not the fragment being shown.
        view.findViewById<MaterialButton>(R.id.connect_dojo).setOnClickListener {
            (parentFragment as? DojoConfigureBottomSheet)?.onInstructionsConnectClicked()
        }
        view.findViewById<MaterialButton>(R.id.browse_community_dojos).setOnClickListener {
            (parentFragment as? DojoConfigureBottomSheet)?.onBrowseCommunityDojoClicked()
        }
    }


}

class DojoConnectFragment : Fragment() {


    private lateinit var checkImageTor: ImageView
    private lateinit var checkImageDojo: ImageView
    private lateinit var progressTor: ProgressBar
    private lateinit var progressDojo: ProgressBar

    // #231: the page still reports its view's existence so a restore
    // that recreates page 2 as the selected page can connect even if
    // the pager's page-selected dispatch has already passed it by.
    // The sheet's maybeStartConnect() gates this fire on selection
    // and payload, so the offscreen bind at sheet-open - the #231
    // lockout path - is a silent no-op here.

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.bottomsheet_dojo_connection, container, false)
        progressTor = view.findViewById(R.id.progressTor)
        checkImageTor = view.findViewById(R.id.checkImageTor)

        checkImageDojo = view.findViewById(R.id.checkImageDojo)
        progressDojo = view.findViewById(R.id.progressDojo)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (parentFragment as? DojoConfigureBottomSheet)?.onConnectViewReady()
    }

    fun showTorProgress() {
        // #231: a selection-driven start can land while a restored
        // page's view is not created yet - the lateinit fields are
        // only safe behind this gate.
        if (view == null) return
        progressTor.visibility = View.VISIBLE
        checkImageTor.visibility = View.INVISIBLE
        progressTor.animate()
                .alpha(1f)
                .setDuration(600)
                .start()
    }

    @Synchronized
    fun showTorProgressSuccess() : Boolean {
        // #231: same view gate as showTorProgress().
        if (view == null) return false
        if (checkImageTor.visibility != View.VISIBLE) {
            progressTor.visibility = View.INVISIBLE
            checkImageTor.visibility = View.VISIBLE
            return true
        }
        return false
    }

    fun showDojoProgress() {
        // #231: same view gate as showTorProgress().
        if (view == null) return
        progressDojo.visibility = View.VISIBLE
        checkImageDojo.visibility = View.INVISIBLE
    }

    fun showDojoProgressSuccess() {
        // #231: same view gate as showTorProgress().
        if (view == null) return
        progressDojo.visibility = View.INVISIBLE
        checkImageDojo.visibility = View.VISIBLE
    }
}

class ScanFragment : Fragment() {

    private var mCodeScanner: QRScanner? = null
    private val appContext: Context by KoinJavaComponent.inject(Context::class.java);
    // #232: the shared invalid-payload policy's state - the payload and
    // clock stamp of the last ADMITTED decode (valid or not): the same QR
    // held in frame and the library's same-frame double-fire stay under
    // one window.
    private var lastDecodePayload: String? = null
    private var lastDecodeAtMs = 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.content_scan_layout, container, false);
    }

    // #232: resetCamera() is gone with the loop it generated - the old
    // decode path stopped the scanner first, then resetCamera()'s instant
    // restart made every re-fire of an invalid QR a stop/decode/reset
    // cycle (#232's tight toast loop). The decode listener below stops
    // the camera only for a payload the sheet accepted.


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mCodeScanner = view.findViewById(R.id.scannerViewXpub);
        mCodeScanner?.setLifeCycleOwner(this)
        view.findViewById<TextView>(R.id.scanInstructions).text = getString(R.string.dojo_scan_instruction)
        view.findViewById<TextView>(R.id.scanInstructions).textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        // #181: decode results and the paste button resolve the sheet
        // at event time; the old listener fields died with the
        // pre-built instances they were registered on.
        mCodeScanner?.setQRDecodeListener { decoded ->
            // #232: the shared invalid-payload policy, applied at the
            // scanner: a repeat of the last admitted payload inside the
            // window - the same QR still in frame, or the library's
            // same-frame double-fire - is dropped before it reaches the
            // sheet, so the invalid toast lands once per window instead
            // of in a loop. The camera only stops for a payload the
            // sheet ACCEPTED; an invalid one keeps it running.
            val now = SystemClock.elapsedRealtime()
            if (!shouldAdmitScan(lastDecodePayload, lastDecodeAtMs, decoded, now)) {
                return@setQRDecodeListener
            }
            lastDecodePayload = decoded
            lastDecodeAtMs = now
            if ((parentFragment as? DojoConfigureBottomSheet)?.onScanPayload(decoded) == true) {
                mCodeScanner?.stopScanner()
            }
        }

        view.findViewById<Button>(R.id.pastePubKey).setOnClickListener {
            (parentFragment as? DojoConfigureBottomSheet)?.onManualPasteClicked()
        }


    }


    override fun onResume() {
        super.onResume()
        if (AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, appContext)) {
            mCodeScanner?.startScanner()
        }
    }


}
