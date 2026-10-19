package com.samourai.sentinel.ui.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.api.ApiService.ApiNotConfigured
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.data.repository.LabelRepository
import com.samourai.sentinel.data.db.dao.TxEntropyDao
import com.samourai.sentinel.data.entropy.ENTROPY_FEATURE_ENABLED
import com.samourai.sentinel.data.entropy.EXTERNAL_ANALYSIS_URL_PREFIX
import com.samourai.sentinel.data.entropy.EntropyBand
import com.samourai.sentinel.data.entropy.EntropyBands
import com.samourai.sentinel.databinding.ContentTransactionsDetailsBinding
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.ui.views.InputBottomSheet
import com.samourai.sentinel.ui.webview.ExplorerWebViewActivity
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*


/**
 * sentinel-android
 *
 * @author Sarath
 *
 */


/**
 * #210: recreation-safe shape. Tx is not Parcelable, so it travels in
 * `arguments` as its Gson JSON form (the same serialization the Room
 * converters already use for its input/output lists) and is parsed once,
 * lazily, then cached for the sheet's lifetime. The street-mode `secure`
 * flag rides the base-class argument. The legacy (tx, secure) constructor
 * is kept as a bridge so TransactionsListFragment compiles unchanged.
 */
class TransactionsDetailsBottomSheet() : GenericBottomSheet() {

    data class TxFeeData(val fee: Long?, val feeRate: Long?, val size: Long?)

    private var txCache: Tx? = null
    private val tx: Tx
        get() {
            txCache?.let { return it }
            val parsed = Gson().fromJson(
                requireArguments().getString(ARG_TX_JSON),
                Tx::class.java
            )
            txCache = parsed
            return parsed
        }

    /** #210: street-mode flag, read from arguments so it survives recreation. */
    private val secure: Boolean
        get() = arguments?.getBoolean(GenericBottomSheet.ARG_SECURE, false) == true

    /** #210 bridge for the pre-#210 `TransactionsDetailsBottomSheet(tx, secure)` shape. */
    constructor(tx: Tx, secure: Boolean = false) : this() {
        arguments = (arguments ?: Bundle()).apply {
            putString(ARG_TX_JSON, Gson().toJson(tx))
            putBoolean(GenericBottomSheet.ARG_SECURE, secure)
        }
        txCache = tx
    }

    private val apiService: ApiService by inject(ApiService::class.java)
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private val exchangeRateRepository: ExchangeRateRepository by inject(ExchangeRateRepository::class.java)
    private val labelRepository: LabelRepository by inject(LabelRepository::class.java)
    private val txEntropyDao: TxEntropyDao by inject(TxEntropyDao::class.java)
    var job: Job? = null

    private var currentLabel: String? = null

    private var _binding: ContentTransactionsDetailsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        _binding = ContentTransactionsDetailsBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.txDetailsOpenInExplorerBtn.setOnClickListener {
            SentinelState.selectedTx = tx
            startActivity(Intent(requireContext(), ExplorerWebViewActivity::class.java))
        }
        binding.txDetailsOpenInExplorerBtn2.setOnClickListener {
            SentinelState.selectedTx = tx
            startActivity(Intent(requireContext(), ExplorerWebViewActivity::class.java))
        }
        setTx(tx)
        fetchFee()

        // Boltzmann entropy row (issue #7): reads the ingest-time cache
        // by bare txid (same expression as the label row — the two can
        // never disagree about which tx this is). States are engine
        // truth only: honest numbers, "too complex to analyze" (engine
        // declined), zero entropy (nbCmbn = 1). No cached row (flag
        // off / not yet computed) hides the row — never a guess. Secure
        // mode (street mode) hides the entropy row too.
        // #105: kill switch. While ENTROPY_FEATURE_ENABLED is false the
        // row is GONE unconditionally- no DAO read on sheet open.
        if (secure || !ENTROPY_FEATURE_ENABLED) {
            binding.txDetailsEntropyRow.visibility = View.GONE
        } else {
            // #118: the row itself is the tap target (ripple + trailing
            // external-link icon live in the layout); ONE listener for
            // all three row classes - the #117-shipped advisory path,
            // unchanged.
            binding.txDetailsEntropyRow.setOnClickListener {
                showExternalAnalysisAdvisory()
            }
            apiScope.launch {
                val entry = txEntropyDao.findByTxid(tx.hash.split("-")[0])
                withContext(Dispatchers.Main) {
                    if (entry == null) {
                        binding.txDetailsEntropyRow.visibility = View.GONE
                        return@withContext
                    }
                    when {
                        entry.tooComplex -> {
                            // #115 grey band: no verdict - track only,
                            // nothing filled. Red no longer means
                            // "declined"; refusal plus a pointer.
                            binding.txDetailsEntropy.text = "too complex · analyze externally"
                            binding.txDetailsEntropy.alpha = 0.5f
                            binding.txDetailsEntropyBar.setDeclined()
                        }
                        entry.nbCmbn == 1 -> {
                            // Zero entropy: red band, 1 rung (nbCmbn=1
                            // <= 2 per the ladder).
                            binding.txDetailsEntropy.text = "0 bits · 1 interpretation"
                            binding.txDetailsEntropyBar.setState(EntropyBand.RED, 1)
                        }
                        else -> {
                            // #115 ladder: color = tier (red <=2,
                            // amber 3-4, green >=5 = the Stonewall
                            // pin), fill = min(nbCmbn, 5) rungs.
                            binding.txDetailsEntropy.text =
                                "%.2f bits · %d interpretations"
                                    .format(entry.entropyBits, entry.nbCmbn)
                            binding.txDetailsEntropyBar.setState(
                                EntropyBands.band(entry.nbCmbn),
                                EntropyBands.filled(entry.nbCmbn),
                            )
                        }
                    }
                }
            }
        }

        // Label row: shows the label, or a dimmed "Add label" prompt.
        // Click edits (blank save = remove, BIP329 semantics), long-press
        // copies. The list and sheet re-render themselves via Room
        // LiveData when the write lands.
        labelRepository.observeTxLabel(tx.hash.split("-")[0]).observe(viewLifecycleOwner) { entry ->
            currentLabel = entry?.label
            if (entry != null) {
                binding.txDetailsLabel.text = entry.label
                binding.txDetailsLabel.alpha = 1f
            } else {
                binding.txDetailsLabel.text = "Add label"
                binding.txDetailsLabel.alpha = 0.5f
            }
        }
        // #210: durable result listener for the label editor - registered
        // on every onViewCreated, so a label sheet that survives a
        // recreation still saves. Blank input deletes the record
        // (BIP329: empty = remove), exactly as before.
        childFragmentManager.setFragmentResultListener(REQUEST_KEY_TX_LABEL, viewLifecycleOwner) { _, bundle ->
            val text = bundle.getString(InputBottomSheet.RESULT_INPUT).orEmpty()
            apiScope.launch { labelRepository.setTxLabel(tx.hash.split("-")[0], text) }
        }
        binding.txDetailsLabel.setOnClickListener { showLabelEditor() }
        binding.txDetailsLabel.setOnLongClickListener {
            copyToClipBoard(binding.txDetailsLabel)
            true
        }

        binding.txDetailsBlockId.setOnClickListener { copyToClipBoard(binding.txDetailsBlockId) }
        binding.txDetailsConfirmation.setOnClickListener { copyToClipBoard(binding.txDetailsConfirmation) }
        binding.txDetailsFees.setOnClickListener { copyToClipBoard(binding.txDetailsFees) }
        binding.txDetailsHash.setOnClickListener { copyToClipBoard(binding.txDetailsHash) }
        binding.txDetailsFeeRate.setOnClickListener { copyToClipBoard(binding.txDetailsFeeRate) }
        binding.txDetailsAmount.setOnClickListener { copyToClipBoard(binding.txDetailsAmount) }
    }

    /**
     * #107 tier 1 / #116: consent gate before the only exit from
     * the app's Tor-only path - now on every entropy row (declined
     * or honest). Wording per #114: names am-i.exposed, states it
     * is backend-less (static page; the txid rides the link
     * fragment, never sent to a server), names the page's own
     * mempool.space fetch as the privacy cost (that server sees
     * the txid and the timing, not the IP - Tor-routed webview),
     * and names self-hosting as the escape hatch.
     */
    private fun showExternalAnalysisAdvisory() {
        val txid = tx.hash.split("-")[0]
        // #111: the Continue tap can land after the sheet (and
        // this fragment) is dismissed - requireContext() in the button
        // lambda crashed at :210 (device leg 1 of #110). Capture the
        // host context at dialog build (attachment proven here - the
        // builder needs it); the captured context outlives the detach
        // and the confirmed intent still launches.
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("External privacy analysis")
            .setMessage(
                "This opens the public am-i.exposed website - a static " +
                    "page with no backend; it sees nothing, and the txid " +
                    "stays in the link fragment, which is never sent to " +
                    "a server. To analyze the transaction, the page " +
                    "fetches it from mempool.space in your browser: " +
                    "mempool.space sees the txid and the timing, but " +
                    "not your IP - the webview routes over Tor. " +
                    "Self-hosters can run the tool beside their own " +
                    "mempool instance instead."
            )
            .setPositiveButton("Continue") { _, _ ->
                SentinelState.selectedTx = tx
                ctx.startActivity(
                    Intent(ctx, ExplorerWebViewActivity::class.java)
                        .putExtra(
                            ExplorerWebViewActivity.EXTRA_URL,
                            EXTERNAL_ANALYSIS_URL_PREFIX + txid,
                        )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLabelEditor() {
        // #210: recreation-safe label editor. The old construction wired a
        // captured lambda through InputBottomSheet's onViewReady; the
        // editor sheet now carries its own presentation state in
        // arguments and posts the typed text to REQUEST_KEY_TX_LABEL,
        // received by the durable listener in onViewCreated. maxLen 64
        // kept - the helper default (34) is address-length; labels need
        // more room.
        val editor = InputBottomSheet.newInstance(
            label = "Transaction label",
            buttonLabel = "Save",
            value = currentLabel ?: "",
            maxLen = 64,
            requestKey = REQUEST_KEY_TX_LABEL
        )
        editor.show(childFragmentManager, editor.tag)
    }

    private fun setTx(tx: Tx) {
        val fmt = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
        fmt.timeZone = TimeZone.getDefault()
        binding.txDetailsAmount.text = "${MonetaryUtil.getInstance().formatToBtc(tx.result)} BTC | ${getFiatBalance(tx.result, exchangeRateRepository.getRateLive().value)} "
        binding.txDetailsBlockId.text = "${tx.block_height ?: "__"}"
        var latestBlockHeight = 1L
        if (SentinelState.blockHeight != null)
            latestBlockHeight = SentinelState.blockHeight?.height!!
        val txBlockHeight = tx.block_height ?: 0
        binding.txDetailsConfirmation.text = tx.confirmations.toString() ?: "0"
        if (tx.result != null)
            binding.txDetailsTime.text = "${fmt.format(Date(tx.time * 1000))} "
        binding.txDetailsHash.text = tx.hash.split("-")[0]

    }


    private fun setFeeDetails(txFeeData: TxFeeData) {
        binding.txDetailsFeesProgress.visibility = View.GONE
        binding.txDetailsFeesRateProgress.visibility = View.GONE
        binding.txDetailsFees.text = txFeeData.fee.toString()
        binding.txDetailsFeeRate.text = txFeeData.feeRate.toString()
        binding.txDetailsSize.text = txFeeData.size.toString()
    }

    private fun fetchFee() {
        // #112: the completion lambda below can fire after the sheet is
        // dismissed; requireContext() there would throw on the detached
        // fragment (same class as the #111 advisory crash). Capture the
        // app context at call time - fetchFee runs from onViewCreated,
        // attachment proven - and the async path never touches the
        // fragment lifecycle again.
        val appContext = requireContext().applicationContext
        job = apiScope.launch(Dispatchers.IO) {
            try {
                val response = apiService.getTx(tx.hash.split("-")[0])
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val jsonObject = JSONObject(body)
                        var fee = 0L
                        var feeRate = 0L
                        var size = 0L
                        if (jsonObject.has("fees")) {
                            fee = jsonObject.getLong("fees")
                        }
                        if (jsonObject.has("vfeerate")) {
                            feeRate = jsonObject.getLong("vfeerate")
                        }
                        if (jsonObject.has("size")) {
                            size = jsonObject.getLong("size")
                        }
                        val txFeeData = TxFeeData(fee, feeRate, size)
                        withContext(Dispatchers.Main) {
                            setFeeDetails(txFeeData)
                        }
                    }
                }
            } catch (_: Exception) {
            } catch (e: ApiNotConfigured) {
                Timber.e(e)
            }
        }
        job?.invokeOnCompletion {
            if (it != null)
                CoroutineScope(Dispatchers.Main).launch {
                    if (!(it is CancellationException))
                        Toast.makeText(appContext, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun getFiatBalance(balance: Long?, rate: ExchangeRateRepository.Rate?): String {
        if (rate != null) {
            balance?.let {
                return try {
                    val fiatRate = MonetaryUtil.getInstance().getFiatFormat(prefsUtil.selectedCurrency)
                            .format((balance / 1e8) * rate.rate)
                    "$fiatRate ${rate.currency}"
                } catch (e: Exception) {
                    "00.00 ${rate.currency}"
                }
            }
            return "00.00"
        } else {
            return "00.00"
        }
    }


    private fun copyToClipBoard(txtView: TextView) {
        val cm = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        val clipData = ClipData
                .newPlainText("", (txtView).text)
        if (cm != null) {
            cm.setPrimaryClip(clipData)
            Toast.makeText(context, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        job?.let {
            if (it.isActive) it.cancel()
        }
        super.onDestroy()
    }

    companion object {
        const val ARG_TX_JSON = "tx_details_bottom_sheet_arg_tx_json"
        const val REQUEST_KEY_TX_LABEL = "tx_details_bottom_sheet_request_key_label"
    }
}
