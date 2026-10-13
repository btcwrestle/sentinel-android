package com.samourai.sentinel.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.tor.TorState
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.dojo.DojoConfigureBottomSheet
import com.samourai.sentinel.ui.dojo.DojoCredentialsBottomSheet
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PermissionResult
import com.samourai.sentinel.ui.utils.permissionResultOf
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.views.confirm
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

class NetworkActivity : SentinelActivity() {

    var torRenewBtn: TextView? = null
    var torConnectionStatus: TextView? = null
    var dojoConnectionStatus: TextView? = null
    var torButton: Button? = null
    var dojoButton: Button? = null
    var dojoDetailsButton: Button? = null
    var torConnectionIcon: ImageView? = null
    var dojoConnectionIcon: ImageView? = null
    var activeColor = 0
    var disabledColor = 0
    var waiting = 0
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java);
    private val dojoUtility: DojoUtility by inject(DojoUtility::class.java);
    private val repository: CollectionRepository by inject(CollectionRepository::class.java)
    private val apiService: ApiService by inject(ApiService::class.java)

    // #85: erase-all-data unsets the Dojo pairing while this activity's
    // IO launches can still be mid-flight; a failure there must reach
    // logging, not the process (#81's handler pattern).
    private val networkExceptionHandler = CoroutineExceptionHandler { _, t ->
        Timber.e(t, "network import failed")
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network)
        setSupportActionBar(findViewById(R.id.toolbarCollectionDetails))
        if (supportActionBar != null) {
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        }
        activeColor = ContextCompat.getColor(this, R.color.green_ui_2)
        disabledColor = ContextCompat.getColor(this, R.color.disabledRed)
        waiting = ContextCompat.getColor(this, R.color.warning_yellow)
        torButton = findViewById(R.id.networking_tor_btn)
        torRenewBtn = findViewById(R.id.networking_tor_renew)
        dojoButton = findViewById(R.id.networking_dojo_btn)
        dojoDetailsButton = findViewById(R.id.networking_dojo_details_btn)
        torConnectionIcon = findViewById(R.id.network_tor_status_icon)
        torConnectionStatus = findViewById(R.id.network_tor_status)
        dojoConnectionIcon = findViewById(R.id.network_dojo_status_icon)
        dojoConnectionStatus = findViewById(R.id.network_dojo_status)
        torRenewBtn?.setOnClickListener {
            SentinelTorManager.newIdentity()
            this.showFloatingSnackBar(findViewById(R.id.toolbarCollectionDetails), text = getString(R.string.tor_identity_renewed))
        }
        setTorConnectionState(SentinelTorManager.getTorState())
        SentinelTorManager.getTorStateLiveData().observe(this, {
            setTorConnectionState(it)
        })
        dojoDetailsButton?.setOnClickListener {
            showDojoCredentialsBottomSheet()
        }
        dojoButton?.setOnClickListener {
            if (dojoUtility.isDojoEnabled()) {
                confirm(label = "Remove Dojo?", positiveText = "Remove", negativeText = "Cancel") {
                    if (it)
                        removeDojo()
                }
            } else {
                if (!AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, applicationContext))
                    this.askCameraPermission()
                else
                    showDojoSetUpBottomSheet()
            }
        }
        torButton!!.setOnClickListener {
            if (SentinelTorManager.getTorState().state == EnumTorState.ON) {
                if (!dojoUtility.isDojoEnabled()) {
                    SentinelTorManager.stop()
                    prefsUtil.enableTor = false
                }
                else
                    this.showFloatingSnackBar(torButton!!.rootView, text = getString(R.string.tor_disable_dojo_blocked))
            } else {
                SentinelTorManager.start()
                prefsUtil.enableTor = true
            }

        }
        setDojoStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // NOTE: grantResults can be EMPTY when the dialog is cancelled - never index it directly.
        if (requestCode != Companion.CAMERA_PERMISSION) return
        when (permissionResultOf(grantResults)) {
            PermissionResult.GRANTED -> showDojoSetUpBottomSheet()
            PermissionResult.DENIED -> {
                Toast.makeText(this, getString(R.string.camera_permission_denied), Toast.LENGTH_LONG).show()
                showDojoSetUpBottomSheet()
            }
            PermissionResult.CANCELLED -> Unit
        }
    }

    private fun removeDojo() {
        dojoUtility.clearDojo()
        setDojoStatus()
        if (prefsUtil.apiEndPoint.isNullOrEmpty()) {
            if (!AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, applicationContext)) {
                this.askCameraPermission()
            } else {
                showDojoSetUpBottomSheet()
            }
        }
    }

    private fun importAllXpubs() {
        // #85: guard the invariant here, not just at the caller - the
        // erase-all-data flow can clear the endpoints after the caller's
        // check has passed and while this loop is still mid-flight.
        if (!prefsUtil.isAPIEndpointEnabled()) {
            return
        }
        // #158/#159: single-flight - one live burst at a time. The pairing
        // sheet can fire its listener more than once per pairing (#159);
        // a second launch while one is live is skipped, never interleaved
        // onto the same row.
        if (!KeyRegistrationBurst.markStarted()) {
            return
        }
        apiScope.launch(networkExceptionHandler) {
            var registered = 0
            var failed = 0
            var roundRan = false
            try {
                val toImport = mutableListOf<Pair<String, String>>()

                repository.collectionsSnapshot().forEach { collection ->
                    collection.pubs.forEach { pub ->
                        val purpose = "bip${pub.getPurpose()}"
                        toImport.add(pub.pubKey to purpose)
                    }
                }

                // #98: an empty wallet has nothing to register - skip the
                // burst UI with no noise; the pairing sheet already showed
                // its own progress. The finally below resets the holder
                // silently - no residue, no report (the silence leg).
                if (toImport.isEmpty()) {
                    return@launch
                }

                // #98: the burst ran fire-and-forget with zero view writes -
                // the flow-1 device observation ("no progress indication at
                // any point", #97 leg). Per-item progress on the Dojo row,
                // completion tally at the end; setDojoStatus() owns the row's
                // rest state.
                // #158: the holder mirrors the round so a re-entered screen
                // renders from state, and the finally guarantees every round
                // reports - retries included, never zero times.
                roundRan = true
                KeyRegistrationBurst.begin(toImport.size)
                toImport.forEachIndexed { index, (pubKey, purpose) ->
                    // #85: re-check per iteration - the pairing can be erased
                    // mid-loop, and importXpub -> buildClient -> getAPIUrl
                    // throws ApiNotConfigured once the endpoints are gone.
                    if (!prefsUtil.isAPIEndpointEnabled()) {
                        return@forEachIndexed
                    }
                    // #158: attempt counter in the holder - option B keeps
                    // the row counting attempts; failures advance it too.
                    KeyRegistrationBurst.onItemStarted(index + 1)
                    // #98: main-confined, destroy-guarded view write; the
                    // registration work itself is untouched.
                    showKeyRegistrationProgress(index + 1, toImport.size)
                    try {
                        apiService.importXpub(pubKey, purpose)
                        registered++
                    } catch (e: ApiService.ApiNotConfigured) {
                        // #85: ApiNotConfigured is a Throwable, NOT an
                        // Exception - the generic catch below could never
                        // intercept it, so it escaped the launch and killed
                        // the process.
                        Timber.e(e, "Dojo pairing gone, skipping remaining xpub imports")
                        return@forEachIndexed
                    } catch (e: Exception) {
                        Log.d("NetworkActivity", "Error: ${e}")
                        failed++
                    }
                }
            } catch (e: CancellationException) {
                // #129 discipline: cancellation is rethrown, never laundered.
                // apiScope is app-lifetime and never cancels today; the arm
                // order is the contract anyway.
                throw e
            } catch (t: Throwable) {
                // #158 leg 2: the finally below still reports - this catch
                // only keeps an escape-class failure off the process (with
                // the launch CEH) while the report lands anyway.
                Timber.e(t, "derivation path registration burst escaped mid-flight")
            } finally {
                if (roundRan) {
                    // #158: every round states its own delta; the gate
                    // re-arms so the next retry round reports too.
                    KeyRegistrationBurst.complete(registered, failed, KeyRegistrationBurst.total)
                    showKeyRegistrationResult(registered, failed, KeyRegistrationBurst.total)
                } else {
                    KeyRegistrationBurst.markFinished()
                }
            }
        }
    }

    // #98: per-item progress on the Dojo status row - the surface the
    // pairing flow already owns. Main-confined like every view write on
    // this screen (setTorConnectionState precedent); destroy-guarded
    // because the burst runs on apiScope and can outlive the screen by
    // design - #85's containment semantics are untouched, only the view
    // write skips when the screen is gone.
    private fun showKeyRegistrationProgress(done: Int, total: Int) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            dojoConnectionStatus?.text = KeyRegistrationBurst.progressText(done, total)
        }
    }

    // #98: completion report - restore the row, then surface the tally.
    // Partial registration (per-item failures, or the #85 pairing-erased
    // mid-loop skip) gets the retry note - the import screen's
    // partial-tally vocabulary (#48/#125), same shape.
    private fun showKeyRegistrationResult(registered: Int, failed: Int, total: Int) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            setDojoStatus()
            this@NetworkActivity.showFloatingSnackBar(
                findViewById(R.id.network_dojo_layout),
                text = KeyRegistrationBurst.resultText(registered, failed, total)
            )
        }
    }

    fun setDojoStatus() {
        if (dojoUtility.isDojoEnabled()) {
            // #158: render from burst state - in-flight shows the re-entry
            // marker, a partial last round shows its residue, a clean
            // slate shows the plain rest label.
            dojoConnectionStatus?.text = KeyRegistrationBurst.statusText(getString(R.string.Enabled))
            dojoButton?.text = getString(R.string.disable_dojo)
            dojoConnectionIcon!!.setColorFilter(activeColor)
            // Only offer pairing details when a payload actually exists.
            dojoDetailsButton?.visibility = View.VISIBLE
        } else {
            dojoConnectionStatus?.text = getString(R.string.disabled)
            dojoButton?.text = getString(R.string.enable)
            dojoConnectionIcon!!.setColorFilter(disabledColor)
            dojoDetailsButton?.visibility = View.GONE
        }
    }

    /**
     * Shows the pairing credentials for the connected Dojo.
     *
     * The sheet sets FLAG_SECURE because the payload contains the node API key.
     */
    private fun showDojoCredentialsBottomSheet() {
        if (!dojoUtility.isDojoEnabled()) {
            this.showFloatingSnackBar(
                findViewById(R.id.toolbarCollectionDetails),
                text = getString(R.string.no_dojo_connected)
            )
            return
        }
        val sheet = DojoCredentialsBottomSheet()
        sheet.show(supportFragmentManager, sheet.tag)
    }

    private fun setTorConnectionState(torState: TorState) {
        runOnUiThread {
            when (torState.state) {

                EnumTorState.ON -> {
                    torButton!!.text = getString(R.string.disable)
                    torButton!!.isEnabled = true
                    torRenewBtn!!.visibility = View.VISIBLE
                    // #99: ON lands at boot=5; the 5->100 climb is real work
                    // (relay handshake, consensus, descriptors, circuits).
                    // Render it with a stage summary in the Tor Browser's
                    // voice; plain "Enabled" only at boot=100, which is when
                    // the home light goes GREEN.
                    if (torState.progressIndicator in 1..99) {
                        torConnectionIcon!!.setColorFilter(waiting)
                        torConnectionStatus!!.text =
                            torBootstrapStage(torState.progressIndicator)
                    } else {
                        torConnectionIcon!!.setColorFilter(activeColor)
                        torConnectionStatus!!.text = getString(R.string.Enabled)
                    }
                }
                EnumTorState.STARTING -> {
                    torRenewBtn!!.visibility = View.INVISIBLE
                    torButton!!.text = getString(R.string.loading)
                    torButton!!.isEnabled = false
                    torConnectionIcon!!.setColorFilter(waiting)
                    // #94: surface the bootstrap % so "Tor slow today" is
                    // distinguishable from "wallet struggling" at a glance.
                    torConnectionStatus!!.text =
                        if (torState.progressIndicator in 1..99) {
                            "${getString(R.string.tor_initializing)}\u2026 ${torState.progressIndicator}%"
                        } else {
                            getString(R.string.tor_initializing)
                        }
                }
                else -> {
                    torRenewBtn!!.visibility = View.INVISIBLE
                    torButton!!.text = getString(R.string.enable)
                    torButton!!.isEnabled = true
                    torConnectionIcon!!.setColorFilter(disabledColor)
                    torConnectionStatus!!.text = getString(R.string.disabled)
                }
            }
        }
    }

    /**
     * #99: Tor-Browser-voice stage for the post-ON climb. Derived client-side
     * from progressIndicator only - the control-spec bootstrap phases are
     * monotonic and ordered (Tor spec 5.5), so the % is enough to locate the
     * stage. TorState stays untouched (declared non-change); code-set text
     * matches this file's #94 interpolation precedent.
     */
    private fun torBootstrapStage(progress: Int): String {
        val summary = when {
            progress in 1..19 -> "Reaching the first Tor relay\u2026"
            progress in 20..44 -> "Loading network consensus\u2026"
            progress in 45..74 -> "Loading relay directory info\u2026"
            progress in 75..89 -> "Building Tor circuits\u2026"
            else -> "Almost ready\u2026"
        }
        return "Tor $progress% - $summary"
    }

    private fun showDojoSetUpBottomSheet() {
        val dojoConfigureBottomSheet = DojoConfigureBottomSheet()
        dojoConfigureBottomSheet.show(supportFragmentManager, dojoConfigureBottomSheet.tag)
        dojoConfigureBottomSheet.setDojoConfigurationListener(object : DojoConfigureBottomSheet.DojoConfigurationListener {
            override fun onDismiss() {
                //Update dojo status
                setDojoStatus()
                if (!prefsUtil.isAPIEndpointEnabled()) {
                    //removeDojo()
                    Toast.makeText(applicationContext, getString(R.string.no_dojo_connected), Toast.LENGTH_SHORT).show()
                } else
                    importAllXpubs()
            }
        })
    }


    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
