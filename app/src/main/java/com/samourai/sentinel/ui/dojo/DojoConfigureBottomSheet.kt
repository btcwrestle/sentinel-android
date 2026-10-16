package com.samourai.sentinel.ui.dojo

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.invertedx.hummingbird.QRScanner
import com.samourai.sentinel.R
import com.samourai.sentinel.databinding.FragmentBottomsheetViewPagerBinding
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
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

class DojoConfigureBottomSheet : GenericBottomSheet() {
    private var payloadPassed: String? = null;
    private val scanFragment = ScanFragment()
    private val dojoConfigureBottomSheet = DojoNodeInstructions()
    private val dojoConnectFragment = DojoConnectFragment()
    private var dojoConfigurationListener: DojoConfigurationListener? = null
    private val prefsUtil: PrefsUtil by KoinJavaComponent.inject(PrefsUtil::class.java);
    private var numRetries = 0

    // #159: the dismissal seam. The listener fires at most once per
    // sheet lifetime, only from the success arm - the contract is
    // "pairing live, endpoints set". Every dismissal routes through
    // the main looper: no bare Handler() off a lucky call path, no
    // sleeps on OkHttp dispatcher threads.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listenerFired = false

    private var payload: String = ""

    private val dojoUtil: DojoUtility by KoinJavaComponent.inject(DojoUtility::class.java);

    private var _binding: FragmentBottomsheetViewPagerBinding? = null
    private val binding get() = _binding!!

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
        // #165: the connect trigger rides the connect page's own
        // onViewCreated. The pager's onPageSelected fired in the same
        // frame as setCurrentItem - one frame BEFORE FragmentStateAdapter
        // created page 2's view - so the sheet crashed on
        // DojoConnectFragment's unset lateinit views. Registered here,
        // ahead of every payload entry path; the fragment's latch holds
        // the #159 single-fire contract.
        dojoConnectFragment.setConnectViewReadyListener { startTorAndConnect() }
        dojoConfigureBottomSheet.setConnectListener(View.OnClickListener {
            binding.pager.setCurrentItem(1, true)
        })
        dojoConfigureBottomSheet.setBrowseCommunityDojoListener(View.OnClickListener {
            showCommunityDojoPrivacyWarning()
        })
        scanFragment.setManualDetailsListener {
            val jsonDojo = AndroidUtil.getClipBoardString(requireContext())

            if (jsonDojo != null && dojoUtil.validate(jsonDojo!!)) {
                payload = jsonDojo
                binding.pager.setCurrentItem(2, true)
            } else
                Toast.makeText(requireContext(), getString(R.string.invalid_pairing_payload), Toast.LENGTH_SHORT).show()
        }
        scanFragment.setOnScanListener {
            if (dojoUtil.validate(it)) {
                payload = it
                binding.pager.setCurrentItem(2, true)
            } else {
                scanFragment.resetCamera()
                Toast.makeText(requireContext(), getString(R.string.invalid_pairing_payload), Toast.LENGTH_SHORT).show()
            }
        }
        //validate payload that is passed
        payloadPassed?.let {
            if (dojoUtil.validate(it)) {
                payload = it
                binding.pager.setCurrentItem(2, true)
            } else {
                scanFragment.resetCamera()
                Toast.makeText(requireContext(), getString(R.string.invalid_payload), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startTorAndConnect() {
        if (SentinelTorManager.getTorState().state == EnumTorState.ON) {
            dojoConnectFragment.showTorProgressSuccess()
            setDojo()
        } else {
            setUpTor()
            // #170: pairing start, no stomp on the user's toggle.
            SentinelTorManager.start()
            dojoConnectFragment.showTorProgress()
            SentinelTorManager.getTorStateLiveData().observe(this) {
                if (it.state == EnumTorState.ON) {
                    if (dojoConnectFragment.showTorProgressSuccess()) {
                        setDojo()
                    }
                }
            }
        }
    }

    private fun setDojo() {
        dojoConnectFragment.showDojoProgress()
        try {
            val pairing = fromJSON<DojoPairing>(payload)
                ?: throw  Exception("Invalid payload")
            val client = OkHttpClient.Builder().proxy(SentinelTorManager.getProxy()).build()

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
                        val json = JSONObject(body)
                        dojoUtil.setDojoPayload(payload)
                        prefsUtil.apiEndPointTor = pairing.pairing.url
                        prefsUtil.apiEndPoint = pairing.pairing.url
                        prefsUtil.dojoExplorerUrl = pairing.explorer?.url
                        prefsUtil.dojoExplorerType = pairing.explorer?.type
                        dojoUtil.setAuthToken(json.toString())
                        apiScope.launch {
                            dojoUtil.writePayload(pairing);
                            withContext(Dispatchers.Main) {
                                dismissAllOrToast(true)
                            }
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
     * #159: the dismissal seam, rebuilt. Success is the only
     * listener-firing arm, and it fires at most once per sheet lifetime
     * - the latch holds against any residual multi-fire. Failure toasts
     * and dismisses without firing: a failed pairing must not present
     * itself as a live one to the holders. Retries re-enqueue off a
     * main-looper timer instead of sleeping on an OkHttp thread.
     */
    fun dismissAllOrToast(wasResponseSuccessful: Boolean, retry: Boolean = false) {
        if (wasResponseSuccessful) {
            dojoConnectFragment.showDojoProgressSuccess()
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
        val item = arrayListOf<Fragment>()
        item.add(dojoConfigureBottomSheet)
        item.add(scanFragment)
        item.add(dojoConnectFragment)
        binding.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int {
                return item.size
            }

            override fun createFragment(position: Int): Fragment {
                return item[position];
            }

        }
        binding.pager.isUserInputEnabled = false

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

    private var connectOnClickListener: View.OnClickListener? = null
    private var browseCommunityDojoOnClickListener: View.OnClickListener? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bottomsheet_dojo_configure_instruction, container, false);
    }

    fun setConnectListener(listener: View.OnClickListener) {
        connectOnClickListener = listener
    }

    fun setBrowseCommunityDojoListener(listener: View.OnClickListener) {
        browseCommunityDojoOnClickListener = listener
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialButton>(R.id.connect_dojo).setOnClickListener { view ->
            connectOnClickListener?.onClick(view)
        }
        view.findViewById<MaterialButton>(R.id.browse_community_dojos).setOnClickListener { view ->
            browseCommunityDojoOnClickListener?.onClick(view)
        }
    }


}

class DojoConnectFragment : Fragment() {


    private lateinit var checkImageTor: ImageView
    private lateinit var checkImageDojo: ImageView
    private lateinit var progressTor: ProgressBar
    private lateinit var progressDojo: ProgressBar

    // #165: the connect trigger, registered by the sheet before any
    // setCurrentItem(2) can run. onViewCreated here is the earliest
    // moment the lateinit views above are guaranteed initialized;
    // the latch keeps the fire once per fragment lifetime even if
    // the view is recreated (FragmentStateAdapter reuses this
    // instance, and the pager is input-disabled so page 2 is only
    // ever reached via setCurrentItem).
    private var connectViewReadyListener: (() -> Unit)? = null
    private var connectTriggered = false

    fun setConnectViewReadyListener(callback: () -> Unit) {
        connectViewReadyListener = callback
    }

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
        if (!connectTriggered) {
            connectTriggered = true
            connectViewReadyListener?.invoke()
        }
    }

    fun showTorProgress() {
        progressTor.visibility = View.VISIBLE
        checkImageTor.visibility = View.INVISIBLE
        progressTor.animate()
                .alpha(1f)
                .setDuration(600)
                .start()
    }

    @Synchronized
    fun showTorProgressSuccess() : Boolean {
        if (checkImageTor.visibility != View.VISIBLE) {
            progressTor.visibility = View.INVISIBLE
            checkImageTor.visibility = View.VISIBLE
            return true
        }
        return false
    }

    fun showDojoProgress() {
        progressDojo.visibility = View.VISIBLE
        checkImageDojo.visibility = View.INVISIBLE
    }

    fun showDojoProgressSuccess() {
        progressDojo.visibility = View.INVISIBLE
        checkImageDojo.visibility = View.VISIBLE
    }
}

class ScanFragment : Fragment() {

    private var mCodeScanner: QRScanner? = null
    private val appContext: Context by KoinJavaComponent.inject(Context::class.java);
    private var onScan: (scanData: String) -> Unit = {}
    private var manualDetailsListener: View.OnClickListener? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.content_scan_layout, container, false);
    }

    fun setManualDetailsListener(listener: View.OnClickListener) {
        manualDetailsListener = listener
    }

    fun setOnScanListener(callback: (scanData: String) -> Unit) {
        this.onScan = callback
    }

    fun resetCamera() {
        this.mCodeScanner?.stopScanner()
        this.mCodeScanner?.startScanner()
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mCodeScanner = view.findViewById(R.id.scannerViewXpub);
        mCodeScanner?.setLifeCycleOwner(this)
        view.findViewById<TextView>(R.id.scanInstructions).text = getString(R.string.dojo_scan_instruction)
        view.findViewById<TextView>(R.id.scanInstructions).textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        mCodeScanner?.setQRDecodeListener {
            lifecycleScope.launch {
                mCodeScanner?.stopScanner()
                onScan(it)
            }
        }

        view.findViewById<Button>(R.id.pastePubKey).setOnClickListener {
            manualDetailsListener?.onClick(view)
        }

    }


    override fun onResume() {
        super.onResume()
        if (AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, appContext)) {
            mCodeScanner?.startScanner()
        }
    }

}
