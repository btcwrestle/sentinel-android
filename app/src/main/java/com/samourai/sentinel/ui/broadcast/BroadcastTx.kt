package com.samourai.sentinel.ui.broadcast

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.invertedx.hummingbird.QRScanner
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.databinding.LayoutBroadcastBottomSheetBinding
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.settings.ImportBackUpActivity
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PermissionResult
import com.samourai.sentinel.ui.utils.permissionResultOf
import com.samourai.sentinel.ui.utils.shouldAdmitScan
import com.samourai.sentinel.ui.views.SuccessfulBottomSheet
import com.samourai.wallet.psbt.PSBT
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import com.sparrowwallet.hummingbird.registry.RegistryType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Transaction
import org.bouncycastle.util.encoders.Hex
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class BroadcastTx : SentinelActivity() {

    class BroadcastVm : ViewModel() {

        private val _hex = MutableLiveData("")
        val hex: LiveData<String> get() = _hex
        private val apiService: ApiService by inject(ApiService::class.java)
        // #125: ApiNotConfigured (:318/:326) and InvalidResponse (:359) extend
        // Throwable, so the catch(Exception) below cannot intercept them, and this
        // launch had no handler: the throw reached the process-killing default
        // handler. No arms here - BroadcastVm has no Context, so it cannot Toast,
        // while :143 invokeOnCompletion already maps a failed Job to "Unable to
        // broadcast ..." (:154-160). The original type survives (#80), so no false
        // success sheet. Shape mirrors ApiService.authenticateDojo (:86-89).
        private val broadcastFailureHandler = CoroutineExceptionHandler { _, t ->
            Timber.e(t, "Broadcast failed outside the Exception wall")
        }

        fun broadCast(): Job {
            return viewModelScope.launch(Dispatchers.IO + broadcastFailureHandler) {
                hex.value?.let {
                    // #129: the old wrap (catch (Exception) { throw
                    // CancellationException(e.message) }) laundered real failures -
                    // type and stack destroyed, and a genuine failure became
                    // indistinguishable from scope teardown. #126's
                    // broadcastFailureHandler contains any escape, and the
                    // consumer below reads the real cause from
                    // invokeOnCompletion; genuine cancellation arrives as CE and
                    // is guarded there.
                    apiService.broadcast(it)
                }
            }
        }

        fun setHex(hex: String) {
            _hex.postValue(hex)
        }
    }

    private var onBroadcastSuccess: ((hash: String) -> Unit)? = null

    private val model: BroadcastVm by viewModels()
    private var hash = ""
    private lateinit var binding: LayoutBroadcastBottomSheetBinding

    // #247/#232: the shared scan-gate state, same names as the #245
    // surfaces - payload + clock stamp of the last ADMITTED decode
    // (valid or not): a repeat inside the window - the same QR still in
    // frame, the library's same-frame double-fire - is dropped before
    // it reaches validate().
    private var lastDecodePayload: String? = null
    private var lastDecodeAtMs = 0L

    // #145: the file-import launch had no handler - a de-laundered escape
    // (FileNotFoundException/IOException) would reach the process-killing
    // default handler, which is exactly what the old CE wraps were
    // suppressing. Handler first (#126-shape, BroadcastVm precedent above),
    // then the wraps come off in onActivityResult.
    private val fileImportHandler = CoroutineExceptionHandler { _, t ->
        Timber.e(t, "File import failed outside the Exception wall")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutBroadcastBottomSheetBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        window.statusBarColor = ContextCompat.getColor(this, R.color.v3_background)
        binding.materialToolbar.setNavigationOnClickListener {
            this.onBackPressed()
        }

        window.navigationBarColor = ContextCompat.getColor(this, R.color.v3_background)

        disableBtn(binding.broadCastTransactionBtn, false)

        if (intent.hasExtra("signedTxHex")) {
            val signedTxHex: String = intent.getStringExtra("signedTxHex").toString()
            signedTxHex.let {
                model.viewModelScope.launch(Dispatchers.Default) {
                    validate(it, true)
                }
            }
        }

        binding.pasteHex.setOnClickListener { _ ->
            val string = AndroidUtil.getClipBoardString(applicationContext)
            string?.let {
                model.viewModelScope.launch(Dispatchers.Default) {
                    validate(it)
                }
            }
        }

        binding.hexTextView.movementMethod = ScrollingMovementMethod()

        binding.pasteHex.setOnClickListener {
            val clipboardData = AndroidUtil.getClipBoardString(applicationContext)
            clipboardData?.takeIf { it.isNotEmpty() }?.let { string ->
                model.viewModelScope.launch(Dispatchers.Default) {
                    validate(string)
                }
            }
        }
        binding.scanHex.setOnClickListener {
            if (!AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, applicationContext))
                this.askCameraPermission()
            else {
                scanTx()
            }
        }

        binding.fileImportBtn.setOnClickListener {
            var intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "*/*"
            intent = Intent.createChooser(intent, "Choose a file")
            startActivityForResult(intent, ImportBackUpActivity.REQUEST_FILE_CODE)
        }

        binding.broadCastTransactionBtn.setOnClickListener {
            showLoading(true)
            model.broadCast().invokeOnCompletion {
                model.viewModelScope.launch(Dispatchers.Main) {
                    showLoading(false)
                    if (it == null) {
                        onBroadcastSuccess?.invoke(hash)
                        val bottomSheet = SuccessfulBottomSheet.newInstance("Broadcast Successful", hash)
                        bottomSheet.show(supportFragmentManager, bottomSheet.tag)
                        binding.hexTextView.text = ""
                        model.setHex("")
                    } else if (it is CancellationException) {
                        // #129: genuine scope teardown, not a broadcast failure -
                        // nothing to report. Pre-de-laundering this arrived as the
                        // laundered cause and toasted "Unable to broadcast".
                    } else {
                        Toast.makeText(
                                applicationContext,
                                "Unable to broadcast ${it.message}",
                                Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun scanTx() {
        val camera = ScanTxFragment()
        camera.show(supportFragmentManager, "scanner_tag")
        camera.setOnScanListener { decoded ->
            // #247: gate at the consumer like the #245 surfaces - a repeat
            // of the last admitted payload inside the window never
            // re-enters validation.
            val now = SystemClock.elapsedRealtime()
            if (!shouldAdmitScan(lastDecodePayload, lastDecodeAtMs, decoded, now)) {
                return@setOnScanListener
            }
            lastDecodePayload = decoded
            lastDecodeAtMs = now
            // #247: the whole decode rides the main looper - the old
            // launch(Dispatchers.Default) ran camera.dismiss() (a
            // BottomSheetDialogFragment) on the Default worker.
            // validate()'s own UI writes already hop to Main; the decode
            // itself is small-string parsing, fine here.
            model.viewModelScope.launch(Dispatchers.Main) {
                validate(decoded, true)
                camera.dismiss()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // NOTE: grantResults can be EMPTY when the dialog is cancelled - never index it directly.
        if (requestCode != Companion.CAMERA_PERMISSION) return
        when (permissionResultOf(grantResults)) {
            PermissionResult.GRANTED -> scanTx()
            PermissionResult.DENIED ->
                Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_LONG).show()
            PermissionResult.CANCELLED -> Unit
        }
    }

    private suspend fun validate(hex: String, isQRFormat: Boolean = false) {
        val errorText =
            if (isQRFormat)
                "Incompatible QR format. Cannot parse transaction."
            else
                "Incompatible format. Cannot parse transaction."
        try {
            val transaction = Transaction(SentinelState.getNetworkParam(), Hex.decode(hex))
            hash = transaction.hashAsString
            withContext(Dispatchers.Main) {
                disableAllButtons(true)
                binding.hexTextView.text = hex
                model.setHex(hex)
            }
        } catch (ex: Exception) {
            try {
                val psbt = PSBT.fromBytes(Hex.decode(hex), SentinelState.getNetworkParam())
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        applicationContext,
                        errorText,
                        Toast.LENGTH_LONG
                    ).show()
                    disableBtn(binding.broadCastTransactionBtn, false)
                }
                return
            } catch (ex: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        applicationContext,
                        errorText,
                        Toast.LENGTH_LONG
                    ).show()
                    disableBtn(binding.broadCastTransactionBtn, false)
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.broadcastProgress.visibility = if (show) View.VISIBLE else View.GONE
        binding.broadCastTransactionBtn?.text = if (show) " " else getString(R.string.broadcast_transaction)
        disableAllButtons(!show)
    }

    fun setOnBroadcastSuccess(listener: (hash: String) -> Unit) {
        this.onBroadcastSuccess = listener
    }

    private fun disableAllButtons(enable: Boolean) {
        disableBtn(binding.hexTextView, enable)
        disableBtn(binding.scanHex, enable)
        disableBtn(binding.pasteHex, enable)
        disableBtn(binding.broadCastTransactionBtn, enable)
    }

    private fun disableBtn(button: View, enable: Boolean) {
        button.isEnabled = enable
        button.alpha = if (enable) 1F else 0.5f
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (data != null && data.data != null && data.data!!.path != null && requestCode == ImportBackUpActivity.REQUEST_FILE_CODE) {
            val job =
                // #145: the three CE wraps here laundered real failures into
                // cancellation, and existed to suppress process death on a
                // handler-less launch. Sequenced per #145: this launch now
                // carries fileImportHandler (above), so the wraps come off and
                // the real types flow - the size-guard IOException included.
                // The job completes with the real cause; the CEH contains any
                // escape, and the consumer below guards genuine teardown.
                model.viewModelScope.launch(Dispatchers.Main + fileImportHandler) {
                    val inputStream = contentResolver.openInputStream(data.data!!)
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val size = inputStream?.available()
                    if (size != null) {
                        if (size > 5e+6) {
                            throw  IOException("File size is too large to open")
                        }
                    }
                    var string = ""
                    string = reader.buffered().readText()
                    withContext(Dispatchers.Main) {
                        validate(string)
                    }
                }
            job.invokeOnCompletion {
                if (it != null && it !is CancellationException) {
                    // #145: report the real cause - the type name keeps a
                    // null-message failure honest (previously rendered
                    // "Error null"). Genuine scope teardown is not an error.
                    val reason = it.message ?: it::class.simpleName ?: "unknown error"
                    Toast.makeText(this, "Error: $reason", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

class ScanTxFragment : BottomSheetDialogFragment() {

    private lateinit var  mCodeScanner: QRScanner;
    private val appContext: Context by inject(Context::class.java)
    private var onScan: (scanData: String) -> Unit = {}

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.content_scan_layout, container, false)
    }

    fun setOnScanListener(callback: (scanData: String) -> Unit) {
        this.onScan = callback
    }


    @SuppressLint("RestrictedApi")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.pastePubKey).alpha = 0f
        mCodeScanner = view.findViewById(R.id.scannerViewXpub);
        mCodeScanner.setLifeCycleOwner(this)

        mCodeScanner.setQRDecodeListener {
            lifecycleScope.launch {
                    onScan(it)
            }
        }

        mCodeScanner.setURDecodeListener { result ->
            mCodeScanner.stopScanner()
            result.fold(
                onSuccess = {
                    if (it.ur.registryType == RegistryType.CRYPTO_PSBT) {
                        val cryptoPSBT = it.ur.decodeFromRegistry() as CryptoPSBT
                        onScan(String(Hex.encode(cryptoPSBT.psbt)))
                    }
                    else {
                        val urBytes = it.ur.decodeFromRegistry()
                        try {
                            val cryptoPSBT = it.ur.decodeFromRegistry() as CryptoPSBT
                            onScan(String(Hex.encode(cryptoPSBT.psbt)))
                        } catch (e: java.lang.Exception) {
                            //ignore, bytes not parsable as PSBT
                        }
                        try {
                            val transaction = Transaction(SentinelState.getNetworkParam(),
                                urBytes as ByteArray?
                            )
                            onScan(String(org.apache.commons.codec.binary.Hex.encodeHex(transaction.bitcoinSerialize())))
                        } catch (e: java.lang.Exception) {
                            //ignore, bytes not parsable as tx
                        }

                        try {
                            val decoder = StandardCharsets.UTF_8.newDecoder()
                            val buf = ByteBuffer.wrap(urBytes as ByteArray)
                            val charBuffer = decoder.decode(buf)
                        } catch (e: java.lang.Exception) {
                            //ignore, bytes not parsable as utf-8
                        }
                    }
                },
                onFailure = {
                    mCodeScanner.stopScanner()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Error decoding UR")
                        .setMessage("Exception: ${it.message}")
                        .setPositiveButton("Ok") { dialog, which ->
                            dialog.dismiss()
                        }.show()
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, appContext)) {
            mCodeScanner.startScanner()
        }
    }

    override fun onPause() {
        mCodeScanner.stopScanner()
        super.onPause()
    }

}
