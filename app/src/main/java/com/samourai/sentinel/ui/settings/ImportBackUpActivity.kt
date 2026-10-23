package com.samourai.sentinel.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.transition.TransitionManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.NetworkClassifier
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.databinding.ActivityImportBackUpBinding
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.dojo.isAcceptableDojoPairingUrl
import com.samourai.sentinel.ui.dojo.validateDojoPairingPayload
import com.samourai.sentinel.ui.home.HomeActivity
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.views.LockScreenDialog
import com.samourai.sentinel.util.ExportImportUtil
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader

class ImportBackUpActivity : SentinelActivity() {

    enum class ImportType {
        SAMOURAI,
        SENTINEL,
        SENTINEL_LEGACY
    }

    class ImportBackUpViewModel : ViewModel()

    private var payloadObject: JSONObject? = null
    private var importType = ImportType.SENTINEL
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private var requireRestart = false
    private val viewModel: ImportBackUpViewModel by viewModels()
    private lateinit var binding: ActivityImportBackUpBinding
    private val repository: CollectionRepository by inject(CollectionRepository::class.java)
    private val apiService: ApiService by inject(ApiService::class.java)

    // #220: the import work captured by the pin pad. Set only while this
    // activity instance is alive. A recreated activity finds the pad and
    // no continuation, and closes the pad. The decrypted backup is not saved.
    private var importPinContinuation: ((String) -> Unit)? = null

    // #80: real failures in the import launches must reach the
    // invokeOnCompletion reporting path, not the process. Masking
    // them as CancellationException destroyed type and stack.
    private val importExceptionHandler = CoroutineExceptionHandler { _, t ->
        Timber.e(t, "import failed")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBackUpBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        setSupportActionBar(binding.toolbarImportActivity)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        window.navigationBarColor = ContextCompat.getColor(this, R.color.v3_background)

        // #220: the pad posts here. No continuation means this screen was
        // rebuilt under a pad from the previous instance — close it.
        supportFragmentManager.setFragmentResultListener(
            REQUEST_KEY_IMPORT_PIN,
            this
        ) { _, bundle ->
            supportFragmentManager.clearFragmentResult(REQUEST_KEY_IMPORT_PIN)
            val continuation = importPinContinuation
            if (continuation == null) {
                dismissImportPinDialog()
            } else {
                continuation(bundle.getString(LockScreenDialog.RESULT_PIN).orEmpty())
            }
        }
        if (supportFragmentManager.findFragmentByTag(TAG_IMPORT_PIN) != null && importPinContinuation == null) {
            dismissImportPinDialog()
        }

        binding.importChoosePayloadBtn.setOnClickListener {
            var intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "*/*"
            intent = Intent.createChooser(intent, "Choose a file")
            startActivityForResult(intent, REQUEST_FILE_CODE)
            binding.importPayloadTextView.text = ""
        }

        binding.importPastePayloadBtn.setOnClickListener {
            if (AndroidUtil.getClipBoardString(applicationContext) != null) {
                AndroidUtil.getClipBoardString(applicationContext)?.let {
                    binding.importPayloadTextView.text = ""
                    validatePayload(it)
                }
            }
        }

        binding.importStartBtn.isEnabled = false

        binding.importStartBtn.setOnClickListener {
            // #48: empty password is NO LONGER refused. Backups exported with
            // an empty password (before the export-side guard existed) are
            // valid AES payloads keyed on the empty string; refusing empty
            // here made them permanently unimportable. A wrong password on a
            // protected backup still fails honestly in decryptPayload().
            decryptPayload()
        }
        showImportButton(true)
    }

    /**
     * Registers every xpub with the configured server (#48).
     *
     * Per-item containment: one failing registration must NOT cancel the
     * rest (this was the 2-of-3 partial import). Awaited by the caller so
     * the completion handler reports the real outcome. Returns
     * (imported, failed).
     */
    private suspend fun importAllXpubs(): Pair<Int, Int> {
        var imported = 0
        var failed = 0
        val pubKeyCollectionsCopy = ArrayList(repository.collectionsSnapshot())
        pubKeyCollectionsCopy.forEach { collection ->
            collection.pubs.forEach { pub ->
                try {
                    apiService.importXpub(pub.pubKey, "bip${pub.getPurpose()}")
                    imported++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiService.ApiNotConfigured) {
                    // #125: NOT a crash site - this launch carries importExceptionHandler
                    // (:67), which already absorbed the class. The defect was reporting
                    // fidelity: without this arm the throw skipped the per-item ladder and
                    // aborted the loop, losing the (ok of ok+bad) partial tally at
                    // :343-347, so an import that registered nothing could report clean.
                    failed++
                    Timber.e(e, "Failed to register xpub (bip${pub.getPurpose()})")
                } catch (e: Exception) {
                    failed++
                    Timber.e(e, "Failed to register xpub (bip${pub.getPurpose()})")
                }
            }
        }
        return imported to failed
    }

    /**
     * Prefs may carry a second copy of the Dojo URL. Apply it only when
     * it meets the pairing rule. [com.samourai.sentinel.ui.dojo.DojoUtility.setDojo]
     * has already stored the pairing URL; an unacceptable prefs copy is left unused.
     */
    private fun applyImportedDojoEndpoints(prefs: JSONObject) {
        prefs.optString("apiEndPointTor").takeIf { isAcceptableDojoPairingUrl(it) }
            ?.let { prefsUtil.apiEndPointTor = it }
        prefs.optString("apiEndPoint").takeIf { isAcceptableDojoPairingUrl(it) }
            ?.let { prefsUtil.apiEndPoint = it }
    }

    /**
     * Dojo pairing leg (#48). DojoUtil.setDojo persists creds + endpoints
     * LOCALLY first, then makes ONE network auth call - which is what dies
     * with "unable to resolve host" while the Tor circuit is still
     * bootstrapping. So: wait for Tor readiness (bootstrap is legitimately
     * slow), retry the auth call boundedly, and report failure as a
     * PARTIAL (collections + prefs + creds already imported) instead of
     * letting one auth exception fail the whole import's report.
     *
     * Returns true when pairing succeeded (or there was nothing to do).
     */
    private suspend fun importDojoWithRetry(dojo: JSONObject?): Boolean {
        if (dojo == null) return true
        // Same gate as a scanned pairing. Fail before the Tor wait so a
        // payload with a blank credential or a plain-http host is not retried.
        if (!validateDojoPairingPayload(dojo.toString())) return false
        // #99: the bar now spans the Tor wait AND the bounded auth-retry
        // window. The old code hid it at ON - before repeat(3), where rounds
        // fail on a not-yet-usable circuit (10-02: two auth attempts failed
        // ~1s after ON; attempt 3 succeeded ~12.7s after ON).
        showTorWait(true)
        try {
            // start() stays pre-ON only: an already-running daemon must not
            // be re-started. An ON-but-proxy-less window just waits below.
            if (SentinelTorManager.getTorState().state != EnumTorState.ON) {
                // #170: pairing-leg start, no stomp on the user's toggle.
                SentinelTorManager.start()
            }
            // #10 precedent (pairing sheet): readiness is state==ON AND a
            // live SOCKS proxy - ON alone is not SOCKS-ready (10-02 D3).
            // The poll keeps updateTorProgressBar alive through the post-ON
            // climb, so the import screen now shows the climb too.
            val deadline = System.currentTimeMillis() + 60_000L
            while ((SentinelTorManager.getTorState().state != EnumTorState.ON ||
                    SentinelTorManager.getProxy() == null) &&
                System.currentTimeMillis() < deadline
            ) {
                updateTorProgressBar(SentinelTorManager.getTorState().progressIndicator)
                delay(500L)
            }
            val torReady = SentinelTorManager.getTorState().state == EnumTorState.ON &&
                SentinelTorManager.getProxy() != null
            if (!torReady) {
                Timber.e(
                    "dojo import: Tor not ready within 60s " +
                        "(state=${SentinelTorManager.getTorState().state}, " +
                        "proxyLive=${SentinelTorManager.getProxy() != null})"
                )
                return false
            }
            repeat(3) { attempt ->
                setTorStageLabel("Waiting for Tor circuit\u2026 (attempt ${attempt + 1} of 3)")
                try {
                    ExportImportUtil().importDojo(dojo)
                    return true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "dojo import auth attempt ${attempt + 1}/3 failed")
                }
                if (attempt < 2) delay(3_000L)
            }
            return false
        } finally {
            // Every exit hides the bar: Tor ready, the 60s deadline, or
            // cancellation. finally only hides views - it never swallows
            // the CE rethrow above (#80).
            showTorWait(false)
        }
    }

    /**
     * #99: retry-window stage on textView19 (the bar's own label). Main-
     * confined like every other view write on this screen (#80). Cleared
     * by showTorWait(false) in the finally above.
     */
    private fun setTorStageLabel(text: String) {
        runOnUiThread {
            binding.textView19.text = text
        }
    }

    /**
     * The import runs long (Tor wait up to 60s + network legs) with no
     * other feedback; without this the app reads as hung (#48 QA).
     * The start button doubles as the progress cue.
     */
    private fun setImportBusy(busy: Boolean) {
        // #80: completion handlers run on the completing thread (an IO
        // worker); confine the view writes to main.
        runOnUiThread {
            binding.importStartBtn.text = if (busy) "Importing\u2026" else "Import Sentinel Backup"
            binding.importStartBtn.isEnabled = !busy
        }
    }

    /**
     * #94: shows/hides the Tor bootstrap bar for the Dojo pairing wait.
     * Main-confined per setImportBusy (#80); every exit path hides
     * cleanly so no residue carries into later screens.
     */
    private fun showTorWait(show: Boolean) {
        runOnUiThread {
            val bar = binding.importTorProgressBar
            if (show) {
                bar.visibility = View.VISIBLE
            } else {
                bar.visibility = View.GONE
                // Re-arm indeterminate while GONE for the next wait.
                bar.isIndeterminate = true
                binding.textView19.text = ""
            }
        }
    }

    /**
     * #94: drives the Tor bar from the existing 500ms poll tick - no
     * second poller. Determinate while a bootstrap % exists (the mode
     * flip runs INVISIBLE-first, never on a VISIBLE indicator),
     * indeterminate below that; textView19 carries the %.
     */
    private fun updateTorProgressBar(progress: Int) {
        runOnUiThread {
            val bar = binding.importTorProgressBar
            if (progress in 1..99) {
                if (bar.isIndeterminate) {
                    bar.visibility = View.INVISIBLE
                    bar.isIndeterminate = false
                    bar.visibility = View.VISIBLE
                }
                bar.setProgressCompat(progress, true)
                binding.textView19.text = "Connecting via Tor\u2026 $progress%"
            } else {
                binding.textView19.text = "Connecting via Tor\u2026"
            }
        }
    }

    private fun showImportButton(hide: Boolean) {
        val sharedAxis = MaterialSharedAxis(MaterialSharedAxis.Y, !hide)
        TransitionManager.beginDelayedTransition(binding.importStartBtn.rootView as ViewGroup, sharedAxis)
        binding.importStartBtn.isEnabled = !hide
        binding.importStartBtn.visibility = if (hide) View.GONE else View.VISIBLE
    }

    private fun decryptPayload() {
        setImportBusy(true)
        when (importType) {
            ImportType.SENTINEL -> {
                // #82: the decrypt (AES + PBKDF2) ran on the click
                // thread and stalled Main (Davey 2534ms, 227 skipped
                // frames). Run it on IO; setImportBusy(true) above
                // keeps the progress cue for the duration.
                viewModel.viewModelScope.launch(Dispatchers.Main + importExceptionHandler) {
                    var payload: Triple<ArrayList<PubKeyCollection>?, JSONObject, JSONObject?>? = null
                    val payloadText = payloadObject.toString()
                    val password = binding.importPasswordInput.text.toString()
                    try {
                        payload = withContext(Dispatchers.IO) {
                            ExportImportUtil().decryptSentinel(payloadText, password)
                        }
                    } catch (e: Exception) {
                        this@ImportBackUpActivity.showFloatingSnackBar(
                            binding.importPayloadTextView.parent as ViewGroup,
                            text = "Incorrect password, please try again.",
                            duration = Snackbar.LENGTH_SHORT
                        )
                    }

                    if (payload == null) {
                        setImportBusy(false)
                        return@launch
                    }

                    // #272: the backup door's network plan, computed
                    // post-decrypt, pre-write - and pre-PIN: no point
                    // asking for the PIN of an import that will be
                    // refused. Pure planner in its own file (the
                    // PairingNetworkDecision precedent), tick reads
                    // captured here on Main (#82 pattern).
                    val collectionsTicked = binding.importCollections.isChecked
                    val clearExisting = binding.importClearExisting.isChecked
                    val prefsTicked = binding.importPrefs.isChecked
                    val dojoTicked = binding.importDojo.isChecked
                    val currentTestnet = SentinelState.isTestNet()
                    val networkPlan = backupImportNetworkPlan(
                        currentTestnet = currentTestnet,
                        existingVotes = repository.collectionsSnapshot()
                            .flatMap { it.pubs }
                            .map { NetworkClassifier.fromXpub(it.pubKey) },
                        backupVotes = payload.first.orEmpty()
                            .flatMap { it.pubs }
                            .map { NetworkClassifier.fromXpub(it.pubKey) },
                        importCollections = collectionsTicked,
                        clearExisting = clearExisting,
                        importPrefs = prefsTicked,
                        backupPrefsTestnet = if (prefsTicked)
                            payload.second.optBoolean("testnet", currentTestnet)
                        else null,
                        dojoWhisper = if (dojoTicked)
                            NetworkClassifier.fromUrl(
                                payload.third?.optJSONObject("pairing")?.optString("url")
                            )
                        else null
                    )
                    if (networkPlan.refusal != null) {
                        setImportBusy(false)
                        showFloatingSnackBar(
                            binding.importPastePayloadBtn, networkPlan.refusal,
                            anchorView = binding.importStartBtn.id
                        )
                        return@launch
                    }

                    if (payload.second.get("pinEnabled").equals(true)) {
                        if (supportFragmentManager.findFragmentByTag(TAG_IMPORT_PIN) != null) {
                            setImportBusy(false)
                            return@launch
                        }
                        val lockScreenDialog = LockScreenDialog.newInstance(
                            message = "Enter pin code",
                            requestKey = REQUEST_KEY_IMPORT_PIN,
                            purpose = LockScreenDialog.PURPOSE_IMPORT
                        )
                        importPinContinuation = { entered ->
                            if (AccessFactory.getInstance(this@ImportBackUpActivity).validateHash(entered,
                                    payload.second.get("pinHash").toString()
                                )) {
                                importPinContinuation = null
                                AccessFactory.getInstance(this@ImportBackUpActivity).pin = entered
                                lockScreenDialog.dismiss()

                                // Hoisted OUT of the launch body: the
                                // invokeOnCompletion lambda below is outside
                                // that scope and must close over it (#48).
                                var xpubImport: Pair<Int, Int>? = null
                                var dojoPairingFailed = false
                                viewModel.viewModelScope.launch(Dispatchers.IO + importExceptionHandler) {
                                    try {
                                        withContext(Dispatchers.Main) {
                                            binding.importPasswordInputLayout.visibility = View.INVISIBLE
                                            binding.importSentinelBackUpLayout.visibility = View.VISIBLE
                                            binding.importCollections.text =
                                                binding.importCollections.text.toString().substringBefore(" (") +
                                                    " (${payload.first?.size})"
                                        }

                                        if (binding.importPrefs.isChecked) {
                                            payload.second.let { ExportImportUtil().importPrefs(it) }
                                        }

                                        if (binding.importCollections.isChecked) {
                                            payload.first?.let {
                                                ExportImportUtil().startImportCollections(
                                                    it,
                                                    binding.importClearExisting.isChecked
                                                )
                                            }
                                        }
                                        if (binding.importDojo.isChecked) {
                                            // #272: the plan may refuse the backup-borne
                                            // Dojo (cross-network whisper) - the loud
                                            // objection rides the completion snackbar
                                            // and the pairing leg is skipped.
                                            if (networkPlan.importDojo && importDojoWithRetry(payload.third)) {
                                                applyImportedDojoEndpoints(payload.second)
                                                // #273's PROCEED_AND_SET_NETWORK arm: the
                                                // import tap was the consent, and the
                                                // backup's Dojo is the first
                                                // network-bearing input.
                                                if (networkPlan.dojoSetsNetwork) {
                                                    prefsUtil.testnet = networkPlan.postImportTestnet
                                                }
                                            } else if (networkPlan.importDojo) {
                                                dojoPairingFailed = true
                                            }
                                        }
                                        else {
                                            xpubImport = importAllXpubs()
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        throw e
                                    }
                                }.invokeOnCompletion {
                                    runOnUiThread {
                                    if (it == null) {
                                        binding.importStartBtn.text = "Imported \u2713"
                                        requireRestart = true
                                        val xpubNote = xpubImport?.let { (ok, bad) ->
                                            if (bad > 0)
                                                " ($ok of ${ok + bad} keys registered - retry from Network screen)"
                                            else ""
                                        } ?: ""
                                        // #272: network notes, runtime-composed -
                                        // a successful will-set leg supersedes the
                                        // interim prefs/keychain flip note; a failed
                                        // one leaves it standing.
                                        val networkNote =
                                            if (networkPlan.dojoSetsNetwork && !dojoPairingFailed)
                                                networkPlan.dojoSetNote.orEmpty()
                                            else
                                                networkPlan.flipNote.orEmpty() +
                                                    networkPlan.dojoObjection.orEmpty()
                                        val note = "$xpubNote" +
                                            (if (dojoPairingFailed)
                                                " Dojo pairing failed - retry from Network screen."
                                            else "") +
                                            networkNote
                                        showFloatingSnackBar(
                                            binding.importPastePayloadBtn, "Successfully imported$note",
                                            anchorView = binding.importStartBtn.id,
                                            actionText = "restart",
                                            actionClick = { restart() }
                                        )
                                    } else if (it is CancellationException) {
                                        // Scope teardown, not a failure (#80).
                                        setImportBusy(false)
                                    } else {
                                        setImportBusy(false)
                                        Timber.e(it)
                                        showFloatingSnackBar(
                                            binding.importPastePayloadBtn,
                                            "Error: ${it.message}",
                                            anchorView = binding.importStartBtn.id
                                        )
                                    }
                                    }
                                }
                            }
                            else {
                                lockScreenDialog.showError()
                            }
                        }
                        supportFragmentManager.beginTransaction()
                            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                            .add(android.R.id.content, lockScreenDialog, TAG_IMPORT_PIN)
                            .commit()
                    } else {
                        // Hoisted OUT of the launch body (see branch above).
                        var xpubImport: Pair<Int, Int>? = null
                        var dojoPairingFailed = false
                        viewModel.viewModelScope.launch(Dispatchers.IO + importExceptionHandler) {
                            try {
                                withContext(Dispatchers.Main) {
                                    binding.importPasswordInputLayout.visibility = View.INVISIBLE
                                    binding.importSentinelBackUpLayout.visibility = View.VISIBLE
                                    binding.importCollections.text =
                                        binding.importCollections.text.toString().substringBefore(" (") +
                                            " (${payload.first?.size})"
                                }

                                if (binding.importPrefs.isChecked) {
                                    payload.second.let { ExportImportUtil().importPrefs(it) }
                                }

                                if (binding.importCollections.isChecked) {
                                    payload.first?.let {
                                        ExportImportUtil().startImportCollections(
                                            it,
                                            binding.importClearExisting.isChecked
                                        )
                                    }
                                }
                                if (binding.importDojo.isChecked) {
                                    // #272: the plan may refuse the backup-borne
                                    // Dojo (cross-network whisper) - the loud
                                    // objection rides the completion snackbar
                                    // and the pairing leg is skipped.
                                    if (networkPlan.importDojo && importDojoWithRetry(payload.third)) {
                                        applyImportedDojoEndpoints(payload.second)
                                        // #273's PROCEED_AND_SET_NETWORK arm: the
                                        // import tap was the consent, and the
                                        // backup's Dojo is the first
                                        // network-bearing input.
                                        if (networkPlan.dojoSetsNetwork) {
                                            prefsUtil.testnet = networkPlan.postImportTestnet
                                        }
                                    } else if (networkPlan.importDojo) {
                                        dojoPairingFailed = true
                                    }
                                }
                                else {
                                    xpubImport = importAllXpubs()
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                throw e
                            }
                        }.invokeOnCompletion {
                            runOnUiThread {
                            if (it == null) {
                                binding.importStartBtn.text = "Imported \u2713"
                                requireRestart = true
                                val xpubNote = xpubImport?.let { (ok, bad) ->
                                    if (bad > 0)
                                        " ($ok of ${ok + bad} keys registered - retry from Network screen)"
                                    else ""
                                } ?: ""
                                // #272: network notes, runtime-composed -
                                // a successful will-set leg supersedes the
                                // interim prefs/keychain flip note; a failed
                                // one leaves it standing.
                                val networkNote =
                                    if (networkPlan.dojoSetsNetwork && !dojoPairingFailed)
                                        networkPlan.dojoSetNote.orEmpty()
                                    else
                                        networkPlan.flipNote.orEmpty() +
                                            networkPlan.dojoObjection.orEmpty()
                                val note = "$xpubNote" +
                                    (if (dojoPairingFailed)
                                        " Dojo pairing failed - retry from Network screen."
                                    else "") +
                                    networkNote
                                showFloatingSnackBar(
                                    binding.importPastePayloadBtn, "Successfully imported$note",
                                    anchorView = binding.importStartBtn.id,
                                    actionText = "restart",
                                    actionClick = { restart() }
                                )
                            } else if (it is CancellationException) {
                                // Scope teardown, not a failure (#80).
                                setImportBusy(false)
                            } else {
                                setImportBusy(false)
                                Timber.e(it)
                                showFloatingSnackBar(
                                    binding.importPastePayloadBtn,
                                    "Error: ${it.message}",
                                    anchorView = binding.importStartBtn.id
                                )
                            }
                            }
                        }
                    }
                }
            }
            ImportType.SAMOURAI -> {
                // #81 residue: SAMOURAI was assigned by validatePayload
                // but never handled here - the start tap fell through
                // to the else branch's "choose a valid file" snackbar.
                // The parser is the same one the WalletPairingFragment
                // pairing sheet uses.
                // #82 pattern: EditText reads on Main, crypto on IO.
                val payloadText = payloadObject.toString()
                val password = binding.importPasswordInput.text.toString()
                viewModel.viewModelScope.launch(Dispatchers.Main + importExceptionHandler) {
                    val collection = withContext(Dispatchers.IO) {
                        ExportImportUtil().decryptAndParseSamouraiPayload(payloadText, password)
                    }
                    if (collection == null) {
                        setImportBusy(false)
                        this@ImportBackUpActivity.showFloatingSnackBar(
                            binding.importPayloadTextView.parent as ViewGroup,
                            text = "Import failed - check the password, or use the wallet's Pairing tool for watch-only setup.",
                            duration = Snackbar.LENGTH_SHORT
                        )
                        return@launch
                    }
                    // #272: the same door plan for the Samourai arm -
                    // these imports always merge (replace=false), so an
                    // established wallet plus a cross-network backup is
                    // a hard refusal; an empty wallet announces the flip.
                    val currentTestnet = SentinelState.isTestNet()
                    val networkPlan = backupImportNetworkPlan(
                        currentTestnet = currentTestnet,
                        existingVotes = repository.collectionsSnapshot()
                            .flatMap { it.pubs }
                            .map { NetworkClassifier.fromXpub(it.pubKey) },
                        backupVotes = collection.pubs
                            .map { NetworkClassifier.fromXpub(it.pubKey) },
                        importCollections = true,
                        clearExisting = false,
                        importPrefs = false,
                        backupPrefsTestnet = null,
                        dojoWhisper = null
                    )
                    if (networkPlan.refusal != null) {
                        setImportBusy(false)
                        showFloatingSnackBar(
                            binding.importPastePayloadBtn, networkPlan.refusal,
                            anchorView = binding.importStartBtn.id
                        )
                        return@launch
                    }
                    viewModel.viewModelScope.launch(Dispatchers.IO + importExceptionHandler) {
                        try {
                            ExportImportUtil().startImportCollections(
                                arrayListOf(collection),
                                false
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                            throw e
                        }
                    }.invokeOnCompletion {
                        runOnUiThread {
                            if (it == null) {
                                binding.importStartBtn.text = "Imported \u2713"
                                requireRestart = true
                                showFloatingSnackBar(
                                    binding.importPastePayloadBtn, "Successfully imported${networkPlan.flipNote.orEmpty()}",
                                    anchorView = binding.importStartBtn.id,
                                    actionText = "restart",
                                    actionClick = { restart() }
                                )
                            } else if (it is CancellationException) {
                                // Scope teardown, not a failure (#80).
                                setImportBusy(false)
                            } else {
                                setImportBusy(false)
                                showFloatingSnackBar(
                                    binding.importPastePayloadBtn,
                                    "Error: ${it.message}",
                                    anchorView = binding.importStartBtn.id
                                )
                            }
                        }
                    }
                }
            }
            ImportType.SENTINEL_LEGACY -> {
                // #82 pattern: capture the EditText reads on Main (the
                // click thread) before the IO hop.
                val payloadText = payloadObject.toString()
                val password = binding.importPasswordInput.text.toString()
                // #272: hoisted for the completion handler - a refusal
                // is the import's outcome (not an error, not a success).
                var networkRefusal: String? = null
                var networkFlipNote: String? = null
                // #276: the dojo leg's completion state - the SENTINEL
                // arm's hoisted pattern, mirrored for this arm.
                var dojoPairingFailed = false
                var networkDojoSets = false
                var networkDojoObjection: String? = null
                var networkDojoSetNote: String? = null
                viewModel.viewModelScope.launch(Dispatchers.IO + importExceptionHandler) {
                    try {
                        val payload = ExportImportUtil().decryptSentinelLegacy(
                                payloadText,
                                password
                        )
                        val pubKeys = payload.first
                        // #276: the validated dojo (decryptSentinelLegacy
                        // returns it as the pair's second) is no longer
                        // dropped at this door - it gets its leg below.
                        val dojo = payload.second
                        if (pubKeys.isNotEmpty()) {
                            val collection = PubKeyCollection()
                            collection.pubs = pubKeys
                            collection.collectionLabel = "Sentinel Import"
                            // #272: the door plan, post-decrypt pre-write.
                            // Legacy imports always merge (no clear tick on
                            // this arm), carry no prefs - and their dojo
                            // leg rides the plan below (#276).
                            val networkPlan = backupImportNetworkPlan(
                                currentTestnet = SentinelState.isTestNet(),
                                existingVotes = repository.collectionsSnapshot()
                                    .flatMap { it.pubs }
                                    .map { NetworkClassifier.fromXpub(it.pubKey) },
                                backupVotes = pubKeys
                                    .map { NetworkClassifier.fromXpub(it.pubKey) },
                                importCollections = true,
                                clearExisting = false,
                                importPrefs = false,
                                backupPrefsTestnet = null,
                                // #276: the backup-borne dojo rides the
                                // same URL whisper as the SENTINEL arm.
                                dojoWhisper = NetworkClassifier.fromUrl(
                                    dojo?.optJSONObject("pairing")?.optString("url")
                                )
                            )
                            if (networkPlan.refusal != null) {
                                networkRefusal = networkPlan.refusal
                            } else {
                                networkFlipNote = networkPlan.flipNote
                                networkDojoSets = networkPlan.dojoSetsNetwork
                                networkDojoObjection = networkPlan.dojoObjection
                                networkDojoSetNote = networkPlan.dojoSetNote
                                ExportImportUtil().startImportCollections(
                                        arrayListOf(collection),
                                        false
                                )
                                // #276: the parsed-and-validated dojo is no
                                // longer dropped on the floor here - the
                                // same leg, the same helper and the same
                                // #273 decision table as the SENTINEL arm.
                                if (networkPlan.importDojo && dojo != null) {
                                    if (importDojoWithRetry(dojo)) {
                                        if (networkPlan.dojoSetsNetwork) {
                                            prefsUtil.testnet = networkPlan.postImportTestnet
                                        }
                                    } else {
                                        dojoPairingFailed = true
                                    }
                                }
                            }
                        } else {
                            throw IllegalStateException("0 public keys found")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        throw e
                    }
                }
                        .invokeOnCompletion {
                            runOnUiThread {
                            val refusalMsg = networkRefusal
                            if (refusalMsg != null) {
                                // #272: the refusal IS the outcome - not a
                                // success toast, not an error stack.
                                setImportBusy(false)
                                showFloatingSnackBar(
                                        binding.importPastePayloadBtn, refusalMsg,
                                        anchorView = binding.importStartBtn.id
                                )
                            } else if (it == null) {
                                binding.importStartBtn.text = "Imported \u2713"
                                requireRestart = true
                                // #276: the dojo leg's outcome rides the
                                // completion note - the SENTINEL arm's
                                // runtime composition, mirrored.
                                val networkNote =
                                    if (networkDojoSets && !dojoPairingFailed)
                                        networkDojoSetNote.orEmpty()
                                    else
                                        networkFlipNote.orEmpty() +
                                                networkDojoObjection.orEmpty()
                                val note =
                                    (if (dojoPairingFailed)
                                        " Dojo pairing failed - retry from Network screen."
                                    else "") + networkNote
                                showFloatingSnackBar(
                                        binding.importPastePayloadBtn, "Successfully imported$note",
                                        anchorView = binding.importStartBtn.id,
                                        actionText = "restart",
                                        actionClick = { restart() }
                                )
                            } else if (it is CancellationException) {
                                // Scope teardown, not a failure (#80).
                                setImportBusy(false)
                            } else {
                                setImportBusy(false)
                                showFloatingSnackBar(
                                        binding.importPastePayloadBtn,
                                        "Error: ${it.message}",
                                        anchorView = binding.importStartBtn.id
                                )
                            }
                            }
                        }
            }
            else -> {
                setImportBusy(false)
                showFloatingSnackBar(binding.importPastePayloadBtn, "Please choose a valid Sentinel backup file")
            }
        }
    }

    private fun restart() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        overridePendingTransition(R.anim.fade_in, R.anim.bottom_sheet_slide_out)
        finish()
    }

    /**
     * Validates backup payloads
     * Method uses coroutines to parse json
     */
    private fun validatePayload(string: String) {
        viewModel.viewModelScope.launch(Dispatchers.Default + importExceptionHandler) {
            try {
                val json = JSONObject(string)
                withContext(Dispatchers.Main) {
                    binding.importPayloadTextView.text = "${binding.importPayloadTextView.text}${json.toString(2)}"
                    // #81 residue: drop any previously-validated payload
                    // before classifying the new one - a failed or
                    // different-type re-pick must not leave the old
                    // payload armed or the Sentinel checkbox panel
                    // visible.
                    payloadObject = null
                    importType = ImportType.SENTINEL
                    binding.importSentinelBackUpLayout.visibility = View.GONE
                    if (json.has("external") && json.has("payload")) {
                        payloadObject = json
                        importType = ImportType.SAMOURAI
                        showImportButton(false)
                    } else if (json.has("time") && json.has("payload")) {
                        payloadObject = json
                        importType = ImportType.SENTINEL
                        showImportButton(false)
                        binding.importSentinelBackUpLayout.visibility = View.VISIBLE
                    } else if (json.has("payload")) {
                        payloadObject = json
                        importType = ImportType.SENTINEL_LEGACY
                        showImportButton(false)
                    } else {
                        // #81 residue: showImportButton(false) SHOWS the
                        // start button; an invalid payload must restore
                        // the hidden baseline, not arm it.
                        showImportButton(true)
                        showFloatingSnackBar(binding.importStartBtn, text = "Invalid payload")
                    }
                }
            } catch (e: Exception) {
                throw e
            }
        }.invokeOnCompletion {
            if (it != null && it !is CancellationException) {
                Timber.e(it)
            }
        }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (data != null && data.data != null && data.data!!.path != null && requestCode == REQUEST_FILE_CODE) {
            val job =
                    viewModel.viewModelScope.launch(Dispatchers.Main + importExceptionHandler) {
                        try {
                            // #82: up to 5MB of file I/O ran on Main in
                            // the picker result; the read (size guard
                            // included) belongs on IO.
                            val string = withContext(Dispatchers.IO) {
                                val inputStream = contentResolver.openInputStream(data.data!!)
                                val reader = BufferedReader(InputStreamReader(inputStream))
                                val size = inputStream?.available()
                                if (size != null) {
                                    if (size > 5e+6) {
                                        throw IOException("File size is too large to open")
                                    }
                                }
                                reader.buffered().readText()
                            }
                            withContext(Dispatchers.Main) {
                                validatePayload(string)
                            }
                        } catch (fn: FileNotFoundException) {
                            fn.printStackTrace()
                            throw fn
                        } catch (ioe: IOException) {
                            ioe.printStackTrace()
                            throw ioe
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                            throw ex
                        }
                    }
            job.invokeOnCompletion {
                if (it != null && it !is CancellationException) {
                    this.showFloatingSnackBar(binding.importPastePayloadBtn, "Error ${it.message}")
                }
            }
        }
    }

    private fun dismissImportPinDialog() {
        val existing = supportFragmentManager.findFragmentByTag(TAG_IMPORT_PIN) ?: return
        supportFragmentManager.beginTransaction().remove(existing).commit()
    }

    override fun onBackPressed() {
        if (requireRestart) {
            startActivity(Intent(this, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
            overridePendingTransition(R.anim.fade_in, R.anim.bottom_sheet_slide_out)
            finish()
        } else
            super.onBackPressed()
    }

    companion object {
        const val REQUEST_FILE_CODE = 44
        const val TAG_IMPORT_PIN = "lock_screen_import"
        const val REQUEST_KEY_IMPORT_PIN = "lock_screen_import_result"
    }

}
