package com.samourai.sentinel.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.samourai.sentinel.R
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.registry.RegistryType
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class ImportLabelsScanBottomSheet : BottomSheetDialogFragment() {

    private var onLabelsDecoded: ((String) -> Unit)? = null
    private val scanFragment = ScanPubKeyFragment()

    fun setOnLabelsDecodedListener(callback: (String) -> Unit) {
        onLabelsDecoded = callback
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.import_labels_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scanFragment.setPasteVisible(false)
        scanFragment.setOnScanListener { payload ->
            when (val action = labelScanAction(payload)) {
                is LabelScanAction.Import -> {
                    onLabelsDecoded?.invoke(action.jsonl)
                    dismiss()
                }
                LabelScanAction.NotifyBbqrUnsupported -> {
                    Toast.makeText(
                        context,
                        "BBQR label export not yet supported",
                        Toast.LENGTH_SHORT
                    ).show()
                    scanFragment.resumeScan()
                }
                LabelScanAction.IgnoreAndResume -> scanFragment.resumeScan()
            }
        }
        scanFragment.setURPayloadDecoder(::labelsFromUR)
        childFragmentManager.beginTransaction()
            .replace(R.id.importLabelsScanContainer, scanFragment)
            .commit()
    }

    private fun labelsFromUR(result: URDecoder.Result): String? {
        if (result.ur.registryType != RegistryType.BYTES) return null
        val text = runCatching {
            String(result.ur.decodeFromRegistry() as ByteArray, StandardCharsets.UTF_8)
        }.getOrElse { return null }
        return if (isValidLabelsJsonl(text)) text else null
    }
}

fun isValidLabelsJsonl(text: String): Boolean {
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
    return runCatching { JSONObject(firstLine) }.isSuccess
}

sealed class LabelQrPayload {
    object UrFrame : LabelQrPayload()
    object BbqrFrame : LabelQrPayload()
    data class Labels(val jsonl: String) : LabelQrPayload()
    object Invalid : LabelQrPayload()
}

fun classifyLabelQrPayload(payload: String): LabelQrPayload = when {
    payload.startsWith("UR:", ignoreCase = true) -> LabelQrPayload.UrFrame
    payload.startsWith("BQR", ignoreCase = true) -> LabelQrPayload.BbqrFrame
    isValidLabelsJsonl(payload) -> LabelQrPayload.Labels(payload)
    else -> LabelQrPayload.Invalid
}

sealed class LabelScanAction {
    data class Import(val jsonl: String) : LabelScanAction()
    object NotifyBbqrUnsupported : LabelScanAction()
    object IgnoreAndResume : LabelScanAction()
}

fun labelScanAction(payload: String): LabelScanAction =
    when (val classified = classifyLabelQrPayload(payload)) {
        is LabelQrPayload.Labels -> LabelScanAction.Import(classified.jsonl)
        LabelQrPayload.BbqrFrame -> LabelScanAction.NotifyBbqrUnsupported
        LabelQrPayload.UrFrame, LabelQrPayload.Invalid ->
            LabelScanAction.IgnoreAndResume
    }
