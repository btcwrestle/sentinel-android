package com.invertedx.hummingbird

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.common.util.concurrent.ListenableFuture
import com.google.zxing.Result
import com.sparrowwallet.hummingbird.ResultType
import com.sparrowwallet.hummingbird.URDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt


enum class QrDetectType {
    AUTO,
    QR_ONLY,
    UR_ONLY
}

class QRScanner : FrameLayout, DefaultLifecycleObserver {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var _decodeURCallback: (result: kotlin.Result<URDecoder.Result>) -> Unit = { _ -> }
    private var _decodeQrCallback: (result: String) -> Unit = {}
    private var _type = QrDetectType.AUTO
    private val _decoder = URDecoder()
    private var _progressIndicator: CircularProgressIndicator? = null
    private var _progressMessage: TextView? = null
    private var _enableURProgress = true
    private var _scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var _urTransmissionListener: (totalFrames: Int, processedFrames: Int, progress: Double) -> Unit =
        { _, _, _ -> }
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider> =
        ProcessCameraProvider.getInstance(this.context)
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var previewView: PreviewView? = null

    private var isScannerRunning = false
        private set
    private var lifecycleOwner: LifecycleOwner? = null

    constructor(context: Context) : super(context) {
        init(null, 0)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(attrs, 0)
    }

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    ) {
        init(attrs, defStyle)
    }

    fun isScannerRunning(): Boolean {
        return isScannerRunning
    }

    fun setLifeCycleOwner(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(this)
        this.lifecycleOwner = lifecycleOwner
    }

    override fun onResume(owner: LifecycleOwner) {
        if (!isScannerRunning) {
            startScanner()
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        if (isScannerRunning) {
            stopScanner()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        if (isScannerRunning) {
            stopScanner()
        }
        releaseResources()
    }

    private fun releaseResources() {
        if (isScannerRunning) {
            stopScanner()
        }

        cameraExecutor.shutdown()
        try {
            if (!cameraExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                cameraExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            cameraExecutor.shutdownNow()
        }

        _scope.cancel()

        previewView = null
        _progressIndicator = null
        _progressMessage = null

    }

    fun setUrTransmissionListener(callback: (totalFrames: Int, processedFrames: Int, progress: Double) -> Unit) {
        this._urTransmissionListener = callback
    }

    private fun init(attrs: AttributeSet?, defStyle: Int) {
        val attributeSet = context.obtainStyledAttributes(
            attrs, R.styleable.QRScanner, defStyle, 0
        )
        inflate(context, R.layout.layout_scanner_view, this)
        _type = QrDetectType.values()[attributeSet.getInt(R.styleable.QRScanner_enableQrMode, 0)]
        previewView = findViewById(R.id.preview)
        _progressIndicator = findViewById(R.id.progressBar)
        _progressMessage = findViewById(R.id.progressMessage)
        _progressMessage?.visibility = View.GONE
    }


    fun startScanner() {
        isScannerRunning = true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            cameraProviderFuture.addListener({
                cameraProvider = cameraProviderFuture.get()
                cameraProvider!!.unbindAll()
                val cameraSelector =
                    CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build()
                val viewFrameRect = Rect()
                this.getGlobalVisibleRect(viewFrameRect)
                val previewBuilder = Preview.Builder()
                preview =
                    previewBuilder.build()
                        .apply { setSurfaceProvider(previewView?.surfaceProvider) }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
                        it.setAnalyzer(cameraExecutor, QrCodeAnalyzer { qrResult ->
                            setScanResult(qrResult)
                        })
                    }
                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner!!, cameraSelector, preview, imageAnalysis
                )

            }, ContextCompat.getMainExecutor(this.context))

        } else {
            Toast.makeText(context, "Permission not granted", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopScanner() {
        isScannerRunning = false
        cameraProvider?.unbindAll()
        camera = null
        preview = null
        cameraProvider = null
    }

    private fun setScanType(type: QrDetectType) {
        this._type = type
    }

    fun setURDecodeListener(callback: (result: kotlin.Result<URDecoder.Result>) -> Unit) {
        this._decodeURCallback = callback
    }

    fun showURProgress(enable: Boolean) {
        this._enableURProgress = enable
        this.invalidate()
    }

    fun setQRDecodeListener(callback: (result: String) -> Unit) {
        this._decodeQrCallback = callback
    }

    @SuppressLint("SetTextI18n")
    private fun setScanResult(it: Result) {
        val string = it.text
        // #246: ONE dispatch point per decoded frame. This coroutine used to
        // mirror the arm below: QR_ONLY fired here AND below, and AUTO fired
        // every plain QR here AND again below through the receivePart
        // fallthrough - so each decoded frame reached the app listener
        // twice. The arm below is now the single dispatch: QR_ONLY fires
        // once on Main; AUTO routes a plain QR through its single
        // receivePart fallthrough; UR frames keep the decoder machinery.
        _scope.launch {
            try {
                if (_type == QrDetectType.QR_ONLY) {
                    withContext(Dispatchers.Main){
                        _decodeQrCallback.invoke(string)
                    }
                    return@launch
                }
                val receivedPart = _decoder.receivePart(string)
                if (!receivedPart && _decoder.expectedPartCount == 0) {
                    withContext(Dispatchers.Main){
                        _decodeQrCallback.invoke(string)
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    if (_enableURProgress) {
                        _progressIndicator?.visibility = View.VISIBLE
                        _progressMessage?.visibility = View.VISIBLE
                        _progressIndicator?.max = 100
                        _progressIndicator?.setProgressCompat(
                            (_decoder.estimatedPercentComplete * 100).roundToInt(),
                            false
                        )
                        _progressMessage?.text =
                            "${(_decoder.estimatedPercentComplete * 100).roundToInt()}%"
                    } else {
                        _progressIndicator?.visibility = View.GONE
                        _progressMessage?.visibility = View.GONE
                    }
                    if (_decoder.expectedPartCount != 0) {
                        _urTransmissionListener.invoke(
                            _decoder.expectedPartCount,
                            _decoder.processedPartsCount,
                            _decoder.estimatedPercentComplete
                        )
                    }
                    if (_decoder.result != null && _decoder.result.type == ResultType.SUCCESS) {
                        _progressIndicator?.setProgressCompat(
                            100,
                            false
                        )
                        _progressMessage?.text =
                            "100%"
                        val result = kotlin.Result.success(_decoder.result)
                        _decodeURCallback.invoke(result)
                        return@withContext
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main){
                    _progressIndicator?.setProgressCompat(
                        0,
                        false
                    )
                    _progressMessage?.text =
                        "0%"
                    withContext(Dispatchers.Main) {
                        val result = kotlin.Result.failure<URDecoder.Result>(Throwable(e))
                        _decodeURCallback.invoke(result)
                    }
                }
            }
        }
    }

}