package com.samourai.sentinel.ui.dojo

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.invertedx.hummingbird.QRScanner
import com.samourai.sentinel.R
import com.samourai.sentinel.api.TlsPolicy
import com.samourai.sentinel.core.DerivedNetwork
import com.samourai.sentinel.core.NetworkClassifier
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.databinding.FragmentBottomsheetViewPagerBinding
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.shouldAdmitScan
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.util.OkHttpLogRedaction
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
import timber.log.Timber
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
    // #304: this client does not go through ApiService.buildClient.
    OkHttpLogRedaction.install()
    val builder = OkHttpClient.Builder()
    TlsPolicy.install(builder)
    builder.callTimeout(0, TimeUnit.MILLISECONDS)
    builder.readTimeout(180, TimeUnit.SECONDS)
    builder.writeTimeout(180, TimeUnit.SECONDS)
    builder.connectTimeout(180, TimeUnit.SECONDS)
    builder.proxy(torProxy)
    return builder.build()
}

// ---- #234/#261: the pairing decision, made pure ----
//
// (#234) The success arm of setDojoPost used to commit the pairing
// inline (prefs, tokens, payload store) and auto-advance:
// dismissAllOrToast fired the listener 500 ms later and NetworkActivity
// started the xpub registration burst - no user gate anywhere between
// the 200 and the burst. Pairing is a trust decision (all wallet
// traffic flows to that node from then on), so the commit waits for an
// explicit tap, and the gate below is the whole decision table. It is
// also the seam #250's network guard rides: a cross-network payload is
// another decision that belongs before the first write, not after it.
//
// (#261) The tap still came too late: the /auth/login POST - apikey in
// the body - left the device BEFORE the dialog, so the Dojo logged the
// attempt and held the access credential before the user decided
// anything. The table below inverts the order: SELECTION arms the
// dialog (validate passed - there is nothing to wait for), decline is
// zero contact, and Pair is the first network byte of the flow. The
// commit stays double-gated - the explicit tap, then the 200
// (commitPairing) - so the receipt confirms an attempt the user
// consented to, not a decision trailing an exchange that already
// succeeded.

/**
 * #234/#261: what the sheet may do around the pairing decision.
 * Top-level so the negative tests can pin the double-tap,
 * tap-after-decline and re-selection windows without loading any
 * Android machinery.
 */
enum class PairingAckDecision { SHOW_CONFIRMATION, START_CONNECT, DISCARD_AND_DISMISS, NOOP }

/**
 * #234/#261: pure transition table for the pairing decision.
 * SELECTION arms SHOW_CONFIRMATION exactly once - a re-selection
 * landing while the dialog is up is a NOOP, so there is one dialog and
 * one decision per sheet. Pair spends the gate on START_CONNECT - the
 * first network contact of the flow - and decline spends it on
 * DISCARD_AND_DISMISS with zero contact. Either terminal tap spends the
 * gate: the dialog dismisses on the first tap, so any second tap
 * resolves to NOOP. The user's decision, once made, stands. Every entry
 * arrives on main today (selection, dialog taps); @Synchronized stays
 * as defense for any future off-main arm, mirroring the sheet's
 * mainHandler idiom.
 */
class PairingAckGate {
    private var awaitingConfirmation = false
    private var spent = false

    @Synchronized
    fun onPairingSelected(): PairingAckDecision = when {
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
            PairingAckDecision.START_CONNECT
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

// ---- #256: the pairing dialogs' display data, made pure ----
//
// The pre-pair confirmation and the post-pair receipt both render
// from small pure functions over the payload + the directory
// snapshot, so the dialog content stays testable without Android
// machinery - the same shape as the #233/#234 layers above.

/**
 * #256: the directory display snapshot the pairing dialogs read -
 * written by the community list's select callback before the payload
 * reaches this sheet, cleared by clearDojo() with its siblings. A
 * snapshot renders only under the url-match guards below, so a stale
 * directory label can never ride a manual QR pairing.
 */
data class DojoDisplaySnapshot(
    val url: String?,
    val name: String?,
    val flag: String?,
    val jurisdiction: String?,
    // #250: the directory's network declaration for the selected
    // node - raw label ("mainnet"/"testnet"), normalized at every
    // read; the url-match guards keep a stale label out of every
    // dialog and every decision.
    val network: String? = null,
)

/**
 * #256: the directory header for the pre-pair dialog. The name
 * renders raw (nullable); the unnamed fallback is the binder's -
 * a resource lookup, not logic.
 */
data class DojoDirectoryHeader(
    val flag: String?,
    val name: String?,
)

/**
 * #256: the renderDirectoryHeader guard (DojoCredentialsBottomSheet),
 * made pure: the snapshot renders only when its URL matches the
 * payload's pairing URL and it carries a name or a flag. Null means
 * no header - manual pairing, re-pair elsewhere, or a payload
 * without directory metadata; absent means absent, never a
 * placeholder.
 */
fun directoryHeader(
    snapshot: DojoDisplaySnapshot?,
    pairingUrl: String?,
): DojoDirectoryHeader? {
    if (pairingUrl == null) return null
    val snap = snapshot ?: return null
    if (snap.url != pairingUrl) return null
    val name = snap.name?.takeIf { it.isNotBlank() }
    val flag = snap.flag?.takeIf { it.isNotBlank() }
    if (name == null && flag == null) return null
    return DojoDirectoryHeader(flag, name)
}

/**
 * #256: the pre-pair dialog's row values, payload + snapshot in. A
 * row renders only when its value exists - absent means absent,
 * never a placeholder. The URL row is the one guarantee (validate);
 * the explorer row carries only the URL, the type string stays out
 * (display/label-only per the DojoPairing census); jurisdiction is
 * directory metadata and rides the same url-match guard as the
 * header. The apikey is deliberately absent - it grants access to
 * the node (see DojoUtility.getPairing's FLAG_SECURE note).
 */
data class PairingDetailRows(
    val jurisdiction: String?,
    val url: String?,
    val version: String?,
    val explorer: String?,
    // #250: the network evidence for the pre-pair dialog's network
    // row - declared (directory label, url-match-guarded) or
    // whispered (URL mount). Null means the row stays hidden: no
    // evidence, never a guess.
    val network: DerivedNetwork? = null,
)

fun pairingDetailRows(
    pairing: DojoPairing,
    snapshot: DojoDisplaySnapshot?,
): PairingDetailRows {
    val pairingUrl = pairing.pairing?.url?.takeIf { it.isNotBlank() }
    val jurisdiction = snapshot
        ?.takeIf { pairingUrl != null && it.url == pairingUrl }
        ?.jurisdiction?.takeIf { it.isNotBlank() }
    // #250: the evidence - declaration first (contract), the URL
    // mount's whisper second (convention), neither means none.
    val declaredNetwork = snapshot
        ?.takeIf { pairingUrl != null && it.url == pairingUrl }
        ?.network?.let { normalizeDeclaredNetwork(it) }
    return PairingDetailRows(
        jurisdiction = jurisdiction,
        url = pairingUrl,
        version = pairing.pairing?.version?.takeIf { it.isNotBlank() },
        explorer = pairing.explorer?.url?.takeIf { it.isNotBlank() },
        network = declaredNetwork ?: NetworkClassifier.fromUrl(pairingUrl),
    )
}

/**
 * #256: the receipt's ONE identity line - a fixed ladder, never
 * blank, never a placeholder glyph:
 *   1. directory pairing (snapshot url matches the paired url):
 *      flag + name, the name falling back to the passed unnamed
 *      label when the directory lists none;
 *   2. manual/QR pairing: the Dojo URL - the node's only identity
 *      in the payload;
 *   3. a blank URL cannot happen past validate, but the ladder
 *      still ends non-blank.
 */
fun pairingIdentityLine(
    pairingUrl: String?,
    snapshot: DojoDisplaySnapshot?,
    unnamed: String,
): String {
    val url = pairingUrl?.takeIf { it.isNotBlank() }
    val snap = snapshot?.takeIf { url != null && it.url == url }
    if (snap != null) {
        val flag = snap.flag?.takeIf { it.isNotBlank() }
        val name = snap.name?.takeIf { it.isNotBlank() } ?: unnamed
        return listOfNotNull(flag, name).joinToString(" ")
    }
    return url ?: unnamed
}

class DojoConfigureBottomSheet : GenericBottomSheet() {
    private var payloadPassed: String? = null;
    private var dojoConfigurationListener: DojoConfigurationListener? = null
    private val prefsUtil: PrefsUtil by KoinJavaComponent.inject(PrefsUtil::class.java);

    // #233: single-flight for the bounded Tor wait. #261 narrowed the
    // entries to the Pair tap alone (the gate spends it exactly once),
    // so the latch is defense now - kept because a stacked await is
    // still a stacked POST at readiness.
    private var torDeferJob: Job? = null

    // #159: the dismissal seam. The listener fires at most once per
    // sheet lifetime, only from the success arm - the contract is
    // "pairing live, endpoints set". Every dismissal routes through
    // the main looper: no bare Handler() off a lucky call path, no
    // sleeps on OkHttp dispatcher threads.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listenerFired = false

    private var payload: String = ""

    // #234/#261: the decision gate. #261 moved it before the first
    // network contact: selection arms the dialog, the taps spend it.
    // The pendingPairing/pendingAuthBody staging fields died with the
    // old order - the dialog parses the payload directly, and the
    // response rides mainHandler into commitPairing() by closure.
    private val pairingAckGate = PairingAckGate()

    // #250: the network decision, computed when the pre-pair dialog
    // arms and consumed at the commit. Not a #261-style staging
    // field: the payload never lives here, only the dialog's own
    // outcome - held the way the gate holds its state.
    private var networkDecision: PairingNetworkDecision = PairingNetworkDecision.Proceed

    private val dojoUtil: DojoUtility by KoinJavaComponent.inject(DojoUtility::class.java);

    private var _binding: FragmentBottomsheetViewPagerBinding? = null
    private val binding get() = _binding!!

    // #231: the connect start latch. maybeStartConnect() is reachable
    // from two fires - the pager's page-selected callback and the
    // connect page's view-creation entry point - and this latch keeps
    // the pairing attempt once per sheet lifetime whichever lands.
    private var connectStarted = false

    // #260: the connect page's render state, latched by the sheet. A
    // page-selected fire can beat the adapter's transaction for page
    // 2 - connectPage() then finds no fragment, or a fragment whose
    // view is not created, and the old single-shot show*() call was
    // dropped with nothing left to re-trigger it: no spinner, no
    // state change, for the whole pairing POST on warm Tor (#260).
    // Every show*() site below latches state and re-renders; the
    // render no longer depends on who wins the view-creation race.
    private var torProgressShown = false
    private var torSuccessShown = false
    private var dojoProgressShown = false
    private var dojoSuccessShown = false

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
        // #260: the view exists now - render whatever the race
        // dropped. Idempotent: re-applying latched state changes
        // nothing where the calls already landed.
        renderConnectPageState()
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
        startTorAndPresentPairing()
    }

    /**
     * #181: the live connect page, whenever it exists - built fresh by
     * createFragment, restored by tag by the adapter, never a sheet
     * field. All sheet -> page progress writes go through this lookup.
     */
    private fun connectPage(): DojoConnectFragment? =
        childFragmentManager.fragments.filterIsInstance<DojoConnectFragment>().firstOrNull()

    /**
     * #260: apply the latched render state to the live connect page,
     * if there is one. Called by every former show*() site (state set
     * first) and by onConnectViewReady() when the page's view arrives
     * late. The page's own view == null gates still hold: a fragment
     * added but not yet created no-ops here and is caught by the
     * view-ready re-render. Success renders after progress in each
     * pair, so the checkmark wins when both are latched.
     */
    private fun renderConnectPageState() {
        val page = connectPage() ?: return
        if (torProgressShown) page.showTorProgress()
        if (torSuccessShown) page.showTorProgressSuccess()
        if (dojoProgressShown) page.showDojoProgress()
        if (dojoSuccessShown) page.showDojoProgressSuccess()
    }

    /**
     * #261: Tor lifecycle + the decision, in that order. The pairing
     * dialog opens BEFORE any network contact - the Dojo never learns
     * the attempt existed until the user taps Pair. Tor bootstrap is
     * not Dojo contact (circuit building touches the Tor network,
     * never the node), so the daemon still starts at selection: the
     * bootstrap overlaps the user reading the dialog, and the #260
     * latch renders the Tor spinner behind it across the
     * view-creation race. The observer below is indicator-only - the
     * POST start lives behind the Pair tap now, so the old handoff
     * latch (torHandoffDone) died with the arm it guarded.
     */
    private fun startTorAndPresentPairing() {
        if (pairingAckGate.onPairingSelected() == PairingAckDecision.SHOW_CONFIRMATION) {
            showPairingConfirmation()
        }
        if (SentinelTorManager.getTorState().state == EnumTorState.ON) {
            // #260: latched, not single-shot - the tor checkmark
            // renders even if the page's view is still mid-creation.
            torSuccessShown = true
            renderConnectPageState()
        } else {
            setUpTor()
            // #170: pairing start, no stomp on the user's toggle.
            SentinelTorManager.start()
            // #260: same latch for the bootstrap spinner.
            torProgressShown = true
            renderConnectPageState()
            SentinelTorManager.getTorStateLiveData().observe(this) {
                if (it.state == EnumTorState.ON) {
                    // #261: indicator-only. The old observer gated
                    // setDo() on the checkmark rendering - a Tor-ON
                    // landing inside the view-creation window dropped
                    // the pairing START (#260's harsher cold-Tor
                    // variant) - and single-flighted it with a handoff
                    // latch. With the POST behind Pair there is nothing
                    // to start here, and the #260 latch carries the
                    // render across the race.
                    torSuccessShown = true
                    renderConnectPageState()
                }
            }
        }
    }

    /**
     * #233: the pairing gate. #261: entered from the Pair tap alone -
     * strictly post-consent, the first network byte of the flow. It
     * re-decides at CALL TIME because the readiness inputs move under
     * the sheet (ON@boot=5 -> listener -> boot=100): the client is
     * built only on the POST_NOW arm, so it is never CONSTRUCTED in
     * the ON-but-not-ready state, let alone fired.
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
        // #260: latched - the "Connecting to Dojo" wheel is state
        // the page renders, not a single-shot call that can die in
        // the view-creation race.
        dojoProgressShown = true
        renderConnectPageState()
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
                    // #293: this callback does not use Call.await. string()
                    // closes on the success path; the finally covers a
                    // throw before that, and a null body (close is a no-op).
                    try {
                        val body = response.body?.string() ?: "{}"
                        if (response.code == 401 || (response.isSuccessful && !body.contains("authorizations"))) {
                            Timber.d("Unauthorized: wrong API key")
                            // #261: newly reachable AFTER consent - the
                            // deliberate wording lives in dismissAllOrToast.
                            dismissAllOrToast(false)
                        }
                        else if (response.isSuccessful) {
                            // #261: consent is already spent - the Pair tap
                            // preceded this response - so a 200 commits
                            // directly. The gate has nothing left to decide
                            // here (its old arm was the dialog, which now
                            // opens on selection); the pairing and the auth
                            // body ride the mainHandler closure into
                            // commitPairing(), the exact commit body #234
                            // gated and #256 receipted.
                            mainHandler.post { commitPairing(pairing, JSONObject(body)) }
                        }  else {
                            dismissAllOrToast(false)
                        }
                    } finally {
                        try {
                            response.body?.close()
                        } catch (_: Exception) {
                        }
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    // e.message can be null; `!!` here crashed inside the failure handler.
                    // #176: this arm decides nothing on the OkHttp dispatcher
                    // thread anymore - everything routes through the main
                    // looper. The old uncapped sleep-and-re-POST spin (5 s
                    // on this very thread) was first capped by the
                    // numRetries timer arm; #261 removes the auto-retry
                    // entirely: a retry starts a fresh decision in a fresh
                    // sheet, never a re-POST behind a spent gate.
                    mainHandler.post {
                        dismissAllOrToast(false)
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
            // #264 (Arc C): the exception never reaches the UI. This
            // catch swallows everything the try block throws, including
            // Request.Builder().url()'s IllegalArgumentException, whose
            // message embeds the pairing URL itself - an onion host for
            // any Tor Dojo. The user gets the same deliberate static
            // wording as every other post-consent failure arm; the
            // detail goes to Timber, where the #267 MaskingDebugTree
            // seam redacts both message and stack (the onion rides
            // inside exception text - see util/LogRedaction.kt).
            Timber.e(e, "pairing failed")
            dismissAllOrToast(false)
        }
    }

    /**
     * #256: the pre-pair confirmation - a labeled-row view
     * (dialog_dojo_pairing_details) instead of one long same-color
     * string. The directory header and the Jurisdiction row render
     * only for a matching directory snapshot; URL/Dojo
     * version/Explorer rows render only when the payload carries
     * them - absent means absent, never a placeholder. Title,
     * lead-in and Pair/Don't-pair stay on the builder. Non-cancelable
     * by design: an outside tap would be a silent decline, and the
     * explicit buttons own the decision. The apikey never renders
     * (see DojoUtility.getPairing's FLAG_SECURE note). #261: the
     * dialog opens BEFORE any network contact - directly on
     * selection - so the success checkmark that used to render behind
     * it (the dialog meant "authenticated") is gone: nothing has
     * succeeded yet, and the tick lands behind the receipt, where it
     * tells the truth.
     */
    private fun showPairingConfirmation() {
        if (context == null) return
        // #261: parse straight from the payload - no 200 preceded this
        // dialog anymore. validate() gated every entry path on this very
        // string (fromJSON is deterministic), so the null arm is pure
        // defense: pre-contact, dismiss, invalid-payload toast, zero
        // network bytes.
        val pairing = fromJSON<DojoPairing>(payload)
        if (pairing == null) {
            mainHandler.post {
                dismiss()
                if (context != null)
                    Toast.makeText(context, getString(R.string.invalid_pairing_payload), Toast.LENGTH_SHORT).show()
            }
            return
        }
        val snapshot = readDisplaySnapshot()
        val rows = pairingDetailRows(pairing, snapshot)
        val view = layoutInflater.inflate(R.layout.dialog_dojo_pairing_details, null)
        val header = directoryHeader(snapshot, pairing.pairing?.url)
        val headerRow = view.findViewById<View>(R.id.dojoPairingDirectoryHeader)
        if (header != null) {
            view.findViewById<TextView>(R.id.dojoPairingDirectoryName).text =
                header.name ?: getString(R.string.dojo_unnamed)
            val flagView = view.findViewById<TextView>(R.id.dojoPairingDirectoryFlag)
            if (header.flag != null) {
                flagView.text = header.flag
                flagView.visibility = View.VISIBLE
            } else {
                flagView.visibility = View.GONE
            }
            headerRow.visibility = View.VISIBLE
        }
        bindDetailRow(view, R.id.dojoPairingJurisdictionRow, R.id.dojoPairingJurisdictionValue, rows.jurisdiction)
        // #250: the network guard - evidence at dialog time, the
        // decision before the buttons render. Declared rides the
        // snapshot's url-match guard (a stale directory label can
        // never bless or veto a pairing); the whisper comes from
        // the URL mount. The #261 gate is untouched - the guard
        // only decides what the buttons do.
        val pairingUrl = pairing.pairing?.url
        val declaredNetwork = normalizeDeclaredNetwork(
            snapshot?.takeIf { pairingUrl != null && it.url == pairingUrl }?.network
        )
        networkDecision = pairingNetworkDecision(
            appEstablished = SentinelState.isNetworkEstablished(),
            appIsTestnet = prefsUtil.testnet == true,
            whisper = NetworkClassifier.fromUrl(pairingUrl),
            declared = declaredNetwork,
        )
        rows.network?.let { evidence ->
            bindDetailRow(
                view, R.id.dojoPairingNetworkRow, R.id.dojoPairingNetworkValue,
                networkName(evidence == DerivedNetwork.TESTNET)
            )
        }
        bindDetailRow(view, R.id.dojoPairingUrlRow, R.id.dojoPairingUrlValue, rows.url)
        bindDetailRow(view, R.id.dojoPairingVersionRow, R.id.dojoPairingVersionValue, rows.version)
        bindDetailRow(view, R.id.dojoPairingExplorerRow, R.id.dojoPairingExplorerValue, rows.explorer)
        val decision = networkDecision
        val appNetworkName = networkName(prefsUtil.testnet == true)
        val evidenceName = (declaredNetwork ?: NetworkClassifier.fromUrl(pairingUrl))
            ?.let { networkName(it == DerivedNetwork.TESTNET) }
        val message = getString(R.string.dojo_pairing_confirm_message) + when (decision) {
            is PairingNetworkDecision.ProceedAndSetNetwork ->
                "\n\n" + getString(
                    R.string.dojo_pairing_network_will_set,
                    networkName(decision.targetIsTestnet)
                )
            PairingNetworkDecision.ConfirmMismatch ->
                "\n\n" + getString(
                    R.string.dojo_pairing_network_mismatch,
                    evidenceName,
                    appNetworkName
                )
            PairingNetworkDecision.Refuse ->
                "\n\n" + getString(
                    R.string.dojo_pairing_network_refuse,
                    evidenceName,
                    appNetworkName
                )
            else -> ""
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dojo_pairing_confirm_title))
            .setMessage(message)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.dojo_pairing_confirm_accept)) { _, _ ->
                onPairingConfirmTapped()
            }
            .setNegativeButton(getString(R.string.dojo_pairing_confirm_decline)) { _, _ ->
                onPairingDeclineTapped()
            }
            .show()
        // #250: the refuse arm is read-only - the reason renders and
        // Pair cannot fire; Decline is the one way out, spending the
        // gate's decline arm (dismiss + toast, zero contact).
        if (networkDecision == PairingNetworkDecision.Refuse) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        }
    }

    /**
     * #256: the directory snapshot, read from the prefs the
     * community list's select callback writes before the payload
     * reaches this sheet (dojoDisplayUrl/Name/Flag and the new
     * dojoDisplayJurisdiction); clearDojo() clears them with its
     * siblings.
     */
    private fun readDisplaySnapshot(): DojoDisplaySnapshot =
        DojoDisplaySnapshot(
            prefsUtil.dojoDisplayUrl,
            prefsUtil.dojoDisplayName,
            prefsUtil.dojoDisplayFlag,
            prefsUtil.dojoDisplayJurisdiction,
            prefsUtil.dojoDisplayNetwork,
        )

    // #256: a detail row renders only when it carries real data -
    // absent means absent, never a placeholder.
    private fun bindDetailRow(view: View, rowId: Int, valueId: Int, value: String?) {
        if (value == null) {
            view.findViewById<View>(rowId).visibility = View.GONE
        } else {
            view.findViewById<TextView>(valueId).text = value
            view.findViewById<View>(rowId).visibility = View.VISIBLE
        }
    }

    /**
     * #250: the dialogs' network vocabulary - the Network page's
     * strings (#208), reused, never duplicated.
     */
    private fun networkName(isTestnet: Boolean): String =
        getString(if (isTestnet) R.string.network_testnet else R.string.network_mainnet)

    /**
     * #261: Pair tapped - the consent is spent and the connect
     * starts. The gate resolves to START_CONNECT exactly once; any
     * double-tap that lands before the dialog window closes is a
     * NOOP. The "Connecting to Dojo" wheel latches up from this tap
     * (#260), covering the DEFER wait too if Tor is still
     * bootstrapping, and setDo() runs - the POST decision, the first
     * network contact of the flow, strictly after the decision. The
     * commit waits for the 200: commitPairing(), off the response.
     */
    private fun onPairingConfirmTapped() {
        if (pairingAckGate.onUserConfirmed() != PairingAckDecision.START_CONNECT) return
        dojoProgressShown = true
        renderConnectPageState()
        setDojo()
    }

    /**
     * #261: the commit - verbatim the body #234 put behind the
     * explicit tap and #256 put behind the receipt, now behind the
     * 200 as well: payload, endpoints, tokens, payload store, then
     * the receipt with its OK-gated dismissal seam. Consent is
     * already spent (Pair) and the gate with it, so a successful
     * response commits directly - reached from the response handler
     * through mainHandler, which publishes these writes to main
     * before any read. The receipt confirms an attempt the user
     * consented to, not a decision trailing an exchange that
     * already succeeded.
     */
    private fun commitPairing(pairing: DojoPairing, authBody: JSONObject) {
        val node = pairing.pairing ?: return
        // #250: the consented network set - the empty-keychain arm of
        // the guard. The pairing IS the network's establishment
        // (#42's own rule; the Pair tap was the consent, the will-set
        // line the disclosure). Deliberately NOT performNetworkSwitch
        // (#208): that arm clears the dojo and restarts, built for
        // established keychains - here the prefs listener flips
        // networkParams with nothing to strand.
        (networkDecision as? PairingNetworkDecision.ProceedAndSetNetwork)?.let {
            prefsUtil.testnet = it.targetIsTestnet
        }
        dojoUtil.setDojoPayload(payload)
        prefsUtil.apiEndPointTor = node.url
        prefsUtil.apiEndPoint = node.url
        prefsUtil.dojoExplorerUrl = pairing.explorer?.url
        prefsUtil.dojoExplorerType = pairing.explorer?.type
        dojoUtil.setAuthToken(authBody.toString())
        apiScope.launch {
            dojoUtil.writePayload(pairing);
            withContext(Dispatchers.Main) {
                showPairingReceipt(node.url)
            }
        }
    }

    /**
     * #256: the receipt - a plain dialog, no custom layout:
     * "Sentinel is now paired with" plus ONE identity line (the pure
     * ladder in pairingIdentityLine: directory pairing -> flag +
     * name with the unnamed fallback; manual -> the URL; never
     * blank) and a single OK. It confirms, it does not re-list - the
     * pre-pair dialog just showed the rows and the user tapped Pair.
     * Non-cancelable: an outside tap would strand the burst; OK is
     * the only way out, and it fires the unchanged #159 dismissal
     * seam (showDojoProgressSuccess, 500 ms, listener at most once).
     * The green Dojo tick renders behind the dialog. If the sheet's
     * context is already gone the receipt cannot show and the seam
     * fires directly - the pairing is live, the burst must not
     * strand on a dead window.
     */
    private fun showPairingReceipt(pairingUrl: String?) {
        if (context == null) {
            dismissAllOrToast(true)
            return
        }
        dojoSuccessShown = true
        renderConnectPageState()
        val identity = pairingIdentityLine(
            pairingUrl,
            readDisplaySnapshot(),
            getString(R.string.dojo_unnamed),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dojo_pairing_success_title))
            .setMessage(
                getString(R.string.dojo_pairing_success_message) +
                        "\n\n" + identity
            )
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                dismissAllOrToast(true)
            }
            .show()
    }

    /**
     * #234/#261: Decline tapped - discard and dismiss, before any
     * contact. The gate sits before the first network byte, so
     * decline leaves zero trace: no /auth/login in the Dojo's logs,
     * no apikey off the device, endpoints unset, tokens unwritten,
     * payload store untouched - there is nothing to unwind (the old
     * pending-nulls died with the staging fields). The listener never
     * fires: the holders see a declined pairing exactly the way they
     * see a failed one (#159), and the xpub registration burst never
     * starts.
     */
    private fun onPairingDeclineTapped() {
        if (pairingAckGate.onUserDeclined() != PairingAckDecision.DISCARD_AND_DISMISS) return
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
     * itself as a live one to the holders. #261: every failure arm is
     * post-consent now (the POST only runs behind Pair), so the toast
     * is the deliberate wording - the user tapped Pair, the Dojo
     * refused or never answered: nothing was paired, no wallet data
     * out (dojo_pairing_failed; the generic dojo_connect_failed had
     * this sheet as its only caller and died with the old order). The
     * #176 auto-retry arm dies too: a retry starts a fresh decision in
     * a fresh sheet, never a re-POST behind a spent gate.
     */
    fun dismissAllOrToast(wasResponseSuccessful: Boolean) {
        if (wasResponseSuccessful) {
            dojoSuccessShown = true
            renderConnectPageState()
            mainHandler.postDelayed({
                if (!listenerFired) {
                    listenerFired = true
                    // #220: the host re-registers for this key in onCreate.
                    // The field listener stays for any caller that has not
                    // moved; the three #220 hosts do not set it, so success
                    // is delivered once.
                    val key = arguments?.getString(ARG_REQUEST_KEY).orEmpty()
                    if (key.isNotEmpty() && isAdded) {
                        parentFragmentManager.setFragmentResult(
                            key,
                            Bundle().apply { putBoolean(RESULT_PAIRED, true) }
                        )
                    }
                    dojoConfigurationListener?.onDismiss()
                }
                dismiss()
            }, 500)
        }
        else {
            mainHandler.post {
                dismiss()
                if (context != null)
                    Toast.makeText(context, getString(R.string.dojo_pairing_failed), Toast.LENGTH_LONG).show()
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

    companion object {
        const val ARG_REQUEST_KEY = "dojo_configure_arg_request_key"
        const val RESULT_PAIRED = "dojo_configure_result_paired"

        fun newInstance(requestKey: String, cancelable: Boolean = true): DojoConfigureBottomSheet =
            DojoConfigureBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_REQUEST_KEY, requestKey)
                    putBoolean(GenericBottomSheet.ARG_CANCELABLE, cancelable)
                }
            }
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
