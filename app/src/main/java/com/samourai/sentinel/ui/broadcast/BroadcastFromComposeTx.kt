package com.samourai.sentinel.ui.broadcast

import android.content.Intent
import android.os.Bundle
import com.samourai.sentinel.databinding.ActivityBroadcastUnsignedTxBinding
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.fragments.QRBottomSheetDialog
import com.samourai.sentinel.ui.views.codeScanner.CameraFragmentBottomSheet


class BroadcastFromComposeTx : SentinelActivity() {

    private lateinit var binding: ActivityBroadcastUnsignedTxBinding


    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        binding = ActivityBroadcastUnsignedTxBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        supportFragmentManager.setFragmentResultListener(REQUEST_SCAN_SIGNED_TX, this) { _, bundle ->
            val decoded = bundle.getString(CameraFragmentBottomSheet.RESULT_TEXT) ?: return@setFragmentResultListener
            val intent = Intent(applicationContext, BroadcastTx::class.java).putExtra("signedTxHex", decoded)
            startActivity(intent)
            (supportFragmentManager.findFragmentByTag(TAG_SCAN_SIGNED_TX) as? CameraFragmentBottomSheet)?.dismiss()
        }
        val qrString: String = intent.getStringExtra("qrString").toString()

        binding.showQrCode.setOnClickListener { _ ->
            val dialog = QRBottomSheetDialog(
                    qrString,
                    "Unsigned Transaction",
                    qrString)
            dialog.show(supportFragmentManager, dialog.tag)
            binding.stepView.setStep(2)
        }

        binding.launchQRScanner.setOnClickListener { _ ->
            binding.stepView.setStep(3)
            CameraFragmentBottomSheet.newInstance(REQUEST_SCAN_SIGNED_TX)
                .show(supportFragmentManager, TAG_SCAN_SIGNED_TX)
        }


    }

    companion object {
        private const val REQUEST_SCAN_SIGNED_TX = "broadcast_scan_signed_tx"
        private const val TAG_SCAN_SIGNED_TX = "scan_signed_tx"
    }
}