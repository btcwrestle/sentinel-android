package com.samourai.sentinel.ui.fragments

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.invertedx.hummingbird.QRScanner
import com.samourai.sentinel.R
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.databinding.ContentChooseAddressTypeBinding
import com.samourai.sentinel.databinding.ContentCollectionSelectBinding
import com.samourai.sentinel.databinding.FragmentBottomsheetViewPagerBinding
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.adapters.CollectionsAdapter
import com.samourai.sentinel.ui.collectionEdit.CollectionEditActivity
import com.samourai.sentinel.ui.home.HomeActivity
import com.samourai.sentinel.ui.tools.sweep.SweepPrivKeyFragment
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.RecyclerViewItemDividerDecorator
import com.samourai.sentinel.ui.utils.shouldAdmitScan
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.ui.views.alertWithInput
import com.samourai.sentinel.util.ExportImportUtil
import com.samourai.sentinel.util.FormatsUtil
import com.samourai.wallet.util.PrivKeyReader
import com.samourai.wallet.util.XPUB
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.registry.CryptoAccount
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import com.sparrowwallet.hummingbird.registry.RegistryType
import android.os.SystemClock
import org.bitcoinj.crypto.ChildNumber
import org.bouncycastle.util.encoders.Hex
import org.json.JSONObject
import org.koin.java.KoinJavaComponent
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.samourai.sentinel.core.DerivedNetwork
import com.samourai.sentinel.core.NetworkClassifier


class AddNewPubKeyBottomSheet : GenericBottomSheet {

    /** #216: restore uses the no-arg constructor. A prefilled key rides in arguments. */
    constructor() : super()

    constructor(secure: Boolean) : super(secure)

    constructor(pubKey: String, secure: Boolean) : super(secure) {
        arguments = (arguments ?: Bundle()).apply { putString(ARG_PUBKEY, pubKey) }
    }

    private val pubKey: String
        get() = arguments?.getString(ARG_PUBKEY).orEmpty()

    private val secure: Boolean
        get() = arguments?.getBoolean(GenericBottomSheet.ARG_SECURE, false) == true

    private val scanPubKeyFragment = ScanPubKeyFragment()
    private var newPubKeyListener: ((pubKey: PubKeyModel?) -> Unit)? = null
    private val selectAddressTypeFragment = SelectAddressTypeFragment()
    private val chooseCollectionFragment = ChooseCollectionFragment()
    private var pubKeyString = ""
    private var pubKeyModel: PubKeyModel? = null
    private var selectedPubKeyCollection: PubKeyCollection? = null
    private val collectionRepository: CollectionRepository by KoinJavaComponent.inject(
        CollectionRepository::class.java
    )
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

    fun setSelectedCollection(pubKeyCollection: PubKeyCollection) {
        selectedPubKeyCollection = pubKeyCollection
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        setUpViewPager()

        liveScan().setOnScanListener {
            // #232: same order, new contract - validate() decides whether
            // the decode was consumed; the raw scan lands in pubKeyString
            // either way (pre-existing overwrite: validateXPUB re-reads it).
            val handled = validate(it)
            pubKeyString = it
            handled
        }

        liveScan().setOnMultiKeyScanListener { account ->
            // #254: multi-key UR payloads - a CRYPTO_ACCOUNT with several
            // output descriptors, or a BYTES export with several legs -
            // land as one labeled collection with every skip named:
            // no silent drops on the scan door.
            importURAccount(account)
        }

        liveType().setOnSelectListener {
            validateXPUB(it)
        }

        liveCollection().setOnSelectListener { selectedCollection ->
            // #243: check the duplicate HERE, before the intent - opening
            // CollectionEditActivity with a key it will reject used to land
            // the user on an empty key list where a save could wipe the
            // collection. Same getPubKey call, same message, one screen
            // earlier; the edit screen keeps its own check as the backstop.
            if (selectedCollection != null &&
                pubKeyModel?.pubKey?.let { key -> selectedCollection.getPubKey(key) } != null
            ) {
                if (context != null) {
                    Toast.makeText(
                        context,
                        "Public key already exists in this collection",
                        Toast.LENGTH_LONG
                    ).show()
                }
                this.dismiss()
                return@setOnSelectListener
            }
            startActivity(Intent(context, CollectionEditActivity::class.java).apply {
                this.putExtra("pubKey", pubKeyModel)
                if (selectedCollection != null) {
                    this.putExtra("editIndex", collectionRepository.findById(selectedCollection.id)?.pubs?.size!!)
                    this.putExtra("collection", selectedCollection.id)
                }
            })
            this.dismiss()
        }

        if (pubKey.isNotEmpty()) {
            validate(pubKey)
            pubKeyString = pubKey
        }
    }

    /**
     * #254: the multi-key UR arm. The cross-network guard first -
     * validate()'s per-key rule, applied to the whole payload (one
     * offending key refuses the account: it is one network by
     * construction) - then a confirm dialog naming every key and every
     * skip, then one collection with merge semantics like the backup
     * door's arms (startImportCollections precedent:
     * WalletPairingFragment, ImportBackUpActivity).
     */
    private fun importURAccount(account: URAccountDecode) {
        if (SentinelState.isNetworkEstablished()) {
            val offending = account.keys.firstOrNull {
                (SentinelState.isTestNet() && !isPublicKeyTesnet(it.xpub)) ||
                    (!SentinelState.isTestNet() && isPublicKeyTesnet(it.xpub))
            }
            if (offending != null) {
                val message = if (SentinelState.isTestNet())
                    "Can't track Mainnet public keys in Testnet"
                else
                    "Can't track Testnet public keys in Mainnet"
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                this.dismiss()
                return
            }
        }
        val keyLines = account.keys.joinToString("\n") { key -> "- ${key.label}" }
        val skipLines = account.skipped.joinToString("\n") { skip -> "- ${skip}" }
        val message = when {
            account.keys.isEmpty() ->
                "This payload carries no importable keys." +
                    (if (skipLines.isNotEmpty()) "\n\nSkipped:\n${skipLines}" else "")
            else ->
                "This payload carries ${account.keys.size} keys:\n${keyLines}" +
                    (if (skipLines.isNotEmpty()) "\n\nSkipped:\n${skipLines}" else "")
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                if (account.keys.isEmpty()) "Nothing to import" else "Import account"
            )
            .setMessage(message)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
        if (account.keys.isNotEmpty()) {
            dialog.setPositiveButton("Import") { d, _ ->
                d.dismiss()
                val collection = PubKeyCollection()
                collection.collectionLabel = "UR Import"
                collection.pubs = ArrayList(account.keys.map { key ->
                    PubKeyModel(
                        pubKey = key.xpub,
                        label = key.label,
                        type = key.type,
                        fingerPrint = liveScan().getFingerprint()
                    )
                })
                lifecycleScope.launch(Dispatchers.Main) {
                    try {
                        ExportImportUtil().startImportCollections(
                            arrayListOf(collection),
                            false
                        )
                        Toast.makeText(
                            context,
                            "Imported ${account.keys.size} keys",
                            Toast.LENGTH_LONG
                        ).show()
                        this@AddNewPubKeyBottomSheet.dismiss()
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "Import failed: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun validateXPUB(addressTypes: AddressTypes) {

        pubKeyModel = if ((addressTypes == AddressTypes.BIP49 || addressTypes == AddressTypes.BIP84) && (pubKeyString.startsWith("xpub") || pubKeyString.startsWith("tpub"))) {
            val xpub = XPUB(pubKeyString)
            xpub.decode()
            pubKeyString =
                if (addressTypes == AddressTypes.BIP49 && pubKeyString.startsWith("tpub"))
                    XPUB.makeXPUB(XPUB.MAGIC_UPUB, xpub.depth, xpub.fingerprint, xpub.child, xpub.chain, xpub.getPubkey())
                else if (addressTypes == AddressTypes.BIP49 && pubKeyString.startsWith("xpub"))
                    XPUB.makeXPUB(XPUB.MAGIC_YPUB, xpub.depth, xpub.fingerprint, xpub.child, xpub.chain, xpub.getPubkey())
                else if (addressTypes == AddressTypes.BIP84 && pubKeyString.startsWith("tpub"))
                    XPUB.makeXPUB(XPUB.MAGIC_VPUB, xpub.depth, xpub.fingerprint, xpub.child, xpub.chain, xpub.getPubkey())
                else if (addressTypes == AddressTypes.BIP84 && pubKeyString.startsWith("xpub"))
                    XPUB.makeXPUB(XPUB.MAGIC_ZPUB, xpub.depth, xpub.fingerprint, xpub.child, xpub.chain, xpub.getPubkey())
                else
                    pubKeyString
            PubKeyModel(pubKey = pubKeyString, balance = 0, account_index = 0, change_index = 1, label = "Untitled", type = addressTypes, fingerPrint = liveScan().getFingerprint())
        } else if (pubKeyString.startsWith("ypub") || pubKeyString.startsWith("upub")) {
            PubKeyModel(pubKey = pubKeyString, balance = 0, account_index = 0, change_index = 1, label = "Untitled", type = addressTypes, fingerPrint = liveScan().getFingerprint())
        } else if (pubKeyString.startsWith("zpub") || pubKeyString.startsWith("vpub")) {
            PubKeyModel(pubKey = pubKeyString, balance = 0, account_index = 0, change_index = 1, label = "Untitled", type = addressTypes, fingerPrint = liveScan().getFingerprint())
        } else {
            PubKeyModel(pubKey = pubKeyString, balance = 0, account_index = 0, change_index = 1, label = "Untitled", type = AddressTypes.BIP44, fingerPrint = liveScan().getFingerprint())
        }

        if (newPubKeyListener != null) {
            this.newPubKeyListener?.let { it(pubKeyModel) }
            this.dismiss()
            newPubKeyListener = null
            return
        }
        if (selectedPubKeyCollection != null) {
            startActivity(Intent(context, CollectionEditActivity::class.java).apply {
                this.putExtra("pubKey", pubKeyModel)
                this.putExtra("collection", selectedPubKeyCollection?.id)
            })
            this.dismiss()
        } else {
            binding.pager.setCurrentItem(2, true)
        }

    }

    private fun validate(code: String): Boolean {
        // #297: trim once, before anything reads the input. A key pasted from
        // a message or a text file carries leading or trailing whitespace, and
        // every check below used to see the raw string: extractPublicKey()
        // matches its "bitcoin:" prefixes with startsWith, the xpub arm tested
        // the untrimmed scan, and the accepted value was stored untrimmed. A
        // perfectly good key was rejected as "Invalid public key or payload",
        // and an address only passed because its own check trimmed in place
        // while the PubKeyModel still kept the padded string.
        val payload = FormatsUtil.extractPublicKey(code.trim()).trim()
        val type = FormatsUtil.getPubKeyType(payload)
        if (FormatsUtil.isValidBitcoinAddress(payload) || FormatsUtil.isValidXpub(payload)) {
            if (SentinelState.isNetworkEstablished() && isPublicKeyTesnet(payload) && !SentinelState.isTestNet()) {
                if (context != null)
                    Toast.makeText(context, "Can't track Testnet public keys in Mainnet", Toast.LENGTH_LONG).show()
                this.dismiss()
                return true
            }
            if (SentinelState.isNetworkEstablished() && !isPublicKeyTesnet(payload) && SentinelState.isTestNet()) {
                if (context != null)
                    Toast.makeText(context, "Can't track Mainnet public keys in Testnet", Toast.LENGTH_LONG).show()
                this.dismiss()
                return true
            }
        }
        when {
            PrivKeyReader(payload, SentinelState.getNetworkParam()).format != null -> {
                if (isAdded && activity != null) {
                    this.dismiss()
                    val bottomSheetFragment = SweepPrivKeyFragment(payload, secure)
                    bottomSheetFragment.show(requireActivity().supportFragmentManager, bottomSheetFragment.tag)
                }
            }

            FormatsUtil.isValidBitcoinAddress(payload) -> {
                if (newPubKeyListener != null) {
                    val pubKey = PubKeyModel(pubKey = payload, type = AddressTypes.ADDRESS, label = "Untitled", fingerPrint = liveScan().getFingerprint())
                    newPubKeyListener?.let { it(pubKey) }
                    this.dismiss()
                    newPubKeyListener = null
                    return true
                } else {
                    pubKeyModel = PubKeyModel(pubKey = payload, type = AddressTypes.ADDRESS, label = "Untitled", fingerPrint = liveScan().getFingerprint())
                    // Skip type selection screen since payload is an address
                    binding.pager.setCurrentItem(2, true)
                }
            }
            FormatsUtil.isValidXpub(payload) -> {
                //show option to choose xpub type
                if (type == AddressTypes.BIP84 || type == AddressTypes.BIP49) {
                    pubKeyString = payload
                    validateXPUB(type)
                }
                else {
                    binding.pager.setCurrentItem(1, true)
                    binding.pager.post {
                        liveType().setType(type)
                    }
                }
            }
            isDojoPairingPayload(payload) -> {
                if (isAdded && activity != null) {
                    this.dismiss()
                    val bottomSheetFragment = WalletPairingFragment(payload, secure)
                    bottomSheetFragment.show(requireActivity().supportFragmentManager, bottomSheetFragment.tag)
                }
            }
            else -> {
                // #232: the dead-end arm - the only unconsumed decode. The
                // toast lands here, once per admitted decode (the gate in
                // ScanPubKeyFragment owns the re-decode window); the
                // scanner's dispatch keeps the camera running, so this arm
                // never strands it under a live sheet.
                Toast.makeText(context, "Invalid public key or payload", Toast.LENGTH_LONG).show()
                return false
            }
        }
        // Every other arm dismisses the sheet or advances the pager; the
        // decode is consumed in all of them (the not-added guards too - a
        // detached sheet is tearing down, restarting its scanner is pointless).
        return true
    }

    private fun isPublicKeyTesnet(payload: String): Boolean {
        val lower = payload.lowercase()
        // Address prefixes stay here (legacy heuristic for tb/2/m/n
        // addresses); the xpub-family question belongs to the
        // derivation primitive (core.NetworkClassifier, #42).
        if (lower.startsWith("tb") || lower.startsWith("2") ||
            lower.startsWith("m") || lower.startsWith("n")
        ) return true
        return NetworkClassifier.fromXpub(payload) == DerivedNetwork.TESTNET
    }

    private fun setUpViewPager() {
        val item = arrayListOf<Fragment>()
        item.add(scanPubKeyFragment)
        item.add(selectAddressTypeFragment)
        item.add(chooseCollectionFragment)
        binding.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int {
                return item.size
            }

            override fun createFragment(position: Int): Fragment {
                return item[position]
            }

        }
        binding.pager.isUserInputEnabled = false
    }

    /** #216: ViewPager2 restores pages under f0/f1/f2. Those are not these fields. */
    private fun liveScan(): ScanPubKeyFragment =
        childFragmentManager.findFragmentByTag("f0") as? ScanPubKeyFragment ?: scanPubKeyFragment

    private fun liveType(): SelectAddressTypeFragment =
        childFragmentManager.findFragmentByTag("f1") as? SelectAddressTypeFragment ?: selectAddressTypeFragment

    private fun liveCollection(): ChooseCollectionFragment =
        childFragmentManager.findFragmentByTag("f2") as? ChooseCollectionFragment ?: chooseCollectionFragment

    fun setPubKeyListener(listener: (pubKey: PubKeyModel?) -> Unit) {
        newPubKeyListener = listener
    }

    companion object {
        private const val ARG_PUBKEY = "add_new_pubkey_arg_pubkey"

        internal fun isDojoPairingPayload(payload: String): Boolean = runCatching {
            val json = JSONObject(payload.trim())
            json.has("external") && json.has("payload")
        }.getOrDefault(false)
    }
}


/**
 * #254: one key of a multi-key UR payload, labeled for import.
 */
internal data class URAccountKey(
    val xpub: String,
    val label: String,
    val type: AddressTypes
)

/**
 * #254: a UR payload's full content - every key it carried, plus every
 * entry it could not carry, named with its reason. The old contract
 * surfaced one String (the FIRST descriptor, or only the bip84 leg)
 * and silently dropped the rest.
 */
internal data class URAccountDecode(
    val keys: List<URAccountKey>,
    val skipped: List<String>
)

/**
 * #254: the derivation purpose out of an origin path - "84" from
 * "84'/0'/0'" (an "m/" prefix tolerated). Null on a path too short,
 * blank or digitless: the descriptor is then a NAMED skip, never a
 * coercion - the old bare substring(0..1) threw on short paths and
 * rewrote every unknown purpose into BIP84 version bytes.
 */
internal fun urPurposeFromPath(path: String?): String? {
    val cleaned = path?.trim()?.removePrefix("m/")?.removePrefix("M/")
        ?: return null
    val first = cleaned.substringBefore('/').removeSuffix("'")
    return if (first.isNotEmpty() && first.all { it.isDigit() }) first else null
}

/**
 * #254: purpose to address type. 44/49/84 map; anything else (taproot,
 * account 0, garbage) is null - a named skip, not a silent rewrite.
 */
internal fun urAccountType(purpose: String?): AddressTypes? = when (purpose) {
    "44" -> AddressTypes.BIP44
    "49" -> AddressTypes.BIP49
    "84" -> AddressTypes.BIP84
    else -> null
}

/**
 * #254: the UR BYTES payload's keys - the bip44/bip49/bip84 legs a
 * Samourai-style export carries, each an object with "_pub". The old
 * read took bip84 only; the other legs never surfaced. A leg present
 * but unreadable (no _pub) and any other bipNN leg are named skips.
 */
internal fun urBytesKeys(xpubsJson: JSONObject): URAccountDecode {
    val keys = ArrayList<URAccountKey>()
    val skipped = ArrayList<String>()
    for ((name, type) in listOf(
        "bip44" to AddressTypes.BIP44,
        "bip49" to AddressTypes.BIP49,
        "bip84" to AddressTypes.BIP84
    )) {
        when {
            !xpubsJson.has(name) -> Unit
            xpubsJson.getJSONObject(name).has("_pub") -> keys.add(
                URAccountKey(
                    xpub = xpubsJson.getJSONObject(name).getString("_pub"),
                    label = "Deposit ${name.uppercase()}",
                    type = type
                )
            )
            else -> skipped.add("$name present but carries no _pub")
        }
    }
    xpubsJson.keys().forEach { name ->
        if (name.startsWith("bip") &&
            name !in listOf("bip44", "bip49", "bip84")
        ) {
            skipped.add("$name not supported")
        }
    }
    return URAccountDecode(keys, skipped)
}

/**
 * #254: intra-payload duplicates (the same account surfacing twice)
 * import once - and the collapse is NAMED, never silent.
 */
internal fun dedupeAccountKeys(keys: List<URAccountKey>): URAccountDecode {
    val seen = HashSet<String>()
    val unique = ArrayList<URAccountKey>()
    val skipped = ArrayList<String>()
    keys.forEach { key ->
        if (seen.add(key.xpub)) unique.add(key)
        else skipped.add("duplicate descriptor (${key.label}) - already in this payload")
    }
    return URAccountDecode(unique, skipped)
}


class ScanPubKeyFragment : Fragment() {

    private lateinit var  mCodeScanner: QRScanner;
    private val appContext: Context by KoinJavaComponent.inject(Context::class.java)
    private var onScan: (scanData: String) -> Boolean = { false }
    // #254: multi-key UR payloads hand their whole account here - the
    // single-key contract above stays untouched for single payloads.
    private var onMultiKeyScan: ((URAccountDecode) -> Unit)? = null
    private var urPayloadDecoder: ((URDecoder.Result) -> String?)? = null
    // #232: the shared invalid-payload policy's state - the payload and
    // clock stamp of the last ADMITTED decode (valid or not): the same QR
    // held in frame and the library's same-frame double-fire stay under
    // one window.
    private var lastDecodePayload: String? = null
    private var lastDecodeAtMs = 0L
    private var pasteVisible: Boolean = true
    private var fingerprintHex: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.content_scan_layout, container, false)
    }

    fun setOnScanListener(callback: (scanData: String) -> Boolean) {
        this.onScan = callback
    }

    internal fun setOnMultiKeyScanListener(callback: (URAccountDecode) -> Unit) {
        this.onMultiKeyScan = callback
    }

    fun setURPayloadDecoder(decoder: (URDecoder.Result) -> String?) {
        this.urPayloadDecoder = decoder
    }

    fun resumeScan() {
        if (AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, appContext)) {
            // #232: guarded - startScanner() unbinds and rebinds even when
            // the scanner already runs, so an unnecessary resume would
            // blink the preview; only a stopped scanner restarts.
            if (mCodeScanner?.isScannerRunning() != true) {
                mCodeScanner?.startScanner()
            }
        }
    }

    fun setPasteVisible(visible: Boolean) {
        pasteVisible = visible
        view?.findViewById<Button>(R.id.pastePubKey)?.visibility =
            if (visible) View.VISIBLE else View.GONE
    }

    fun getFingerprint(): String? {
        return fingerprintHex
    }

    // #232: the shared invalid-payload policy, applied at the scanner. A
    // repeat of the last admitted payload inside the window - the same QR
    // held in frame, or the library's same-frame double-fire (setScanResult
    // can invoke the QR callback twice per decoded frame) - never reaches
    // the consumer.
    private fun admitThroughGate(decoded: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!shouldAdmitScan(lastDecodePayload, lastDecodeAtMs, decoded, now)) {
            return false
        }
        lastDecodePayload = decoded
        lastDecodeAtMs = now
        return true
    }

    // #232: the decode dispatch behind the gate. The old QR listener
    // stopped the scanner BEFORE the consumer decided: valid payloads
    // dismissed the sheet or advanced the pager, but the invalid
    // dead-end arm only toasted, so the camera died under a live sheet
    // (#232's frozen New-button preview). The stop moves behind the
    // decision: consumed decodes stop the scanner, everything else
    // keeps it running - no decode path can strand it.
    private fun dispatch(decoded: String) {
        if (!admitThroughGate(decoded)) return
        if (onScan(decoded)) {
            mCodeScanner.stopScanner()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.pastePubKey).text = "Paste"
        view.findViewById<Button>(R.id.pastePubKey).visibility =
            if (pasteVisible) View.VISIBLE else View.GONE
        mCodeScanner = view.findViewById(R.id.scannerViewXpub);
        mCodeScanner.setLifeCycleOwner(this)

        val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        mCodeScanner.setQRDecodeListener { decoded ->
            // #232: gate then dispatch - see dispatch() below.
            dispatch(decoded)
        }

        mCodeScanner.setURDecodeListener { result ->
            // #232: no upfront stopScanner anymore - the invalid-UR arms
            // only showed their dialogs and left the camera stopped after
            // Ok, freezing the preview behind the dialog (#232's UR-dialog
            // sibling of the frozen New-button scanner). The camera keeps
            // running behind every dialog and every unconsumed decode;
            // only a consumed decode stops it (dispatch()). The library
            // re-invokes this listener per frame while a completed UR QR
            // sits in frame, so every arm passes the gate - keyed on the
            // payload each arm would hand onward, or the arm's error
            // identity for the dialogs.
            result.fold(
                onSuccess = {
                    val decoded = urPayloadDecoder?.invoke(it)
                    if (decoded != null) {
                        dispatch(decoded)
                    }
                    else if (urPayloadDecoder != null) {
                        if (admitThroughGate("ur-error-decoded-payload")) {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle("Error decoding payload")
                                .setPositiveButton("Ok") { dialog, which ->
                                    dialog.dismiss()
                                }.show()
                        }
                    }
                    else {
                        // #254: the payload's full content - every key it
                        // carried, every entry it could not. Census: this
                        // default arm's only consumer is the sheet itself
                        // (which registers the multi-key listener); the
                        // labels sheet rides its own decoder and never
                        // reaches this arm.
                        val account = getXpubFromUR(it)
                        when {
                            account.keys.size == 1 && account.skipped.isEmpty() ->
                                dispatch(account.keys.first().xpub)
                            account.keys.isEmpty() && account.skipped.isEmpty() -> {
                                if (admitThroughGate("ur-error-public-key")) {
                                    MaterialAlertDialogBuilder(requireContext())
                                        .setTitle("Error decoding public key")
                                        .setPositiveButton("Ok") { dialog, which ->
                                            dialog.dismiss()
                                        }.show()
                                }
                            }
                            else -> {
                                // #254: multi-key payloads gate on what they
                                // would import (#232 doctrine: the identity is
                                // the payload the arm hands onward), then hand
                                // the whole account to the sheet. The camera
                                // keeps running behind the confirm dialog; a
                                // confirmed import dismisses the sheet, whose
                                // onPause stops the scanner.
                                val multi = onMultiKeyScan
                                if (multi != null &&
                                    admitThroughGate(
                                        account.keys.joinToString("|") { key -> key.xpub }
                                    )
                                ) {
                                    multi(account)
                                } else if (multi == null) {
                                    // Loud fallback, never a quiet nothing:
                                    // a consumer that never registered the
                                    // listener still hears the decode.
                                    if (admitThroughGate("ur-error-public-key")) {
                                        MaterialAlertDialogBuilder(requireContext())
                                            .setTitle("Error decoding public key")
                                            .setPositiveButton("Ok") { dialog, which ->
                                                dialog.dismiss()
                                            }.show()
                                    }
                                }
                            }
                        }
                    }
                },
                onFailure = {
                    if (admitThroughGate("ur-error-${it.message}")) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Error decoding UR")
                            .setMessage("Exception: ${it.message}")
                            .setPositiveButton("Ok") { dialog, which ->
                                dialog.dismiss()
                            }.show()
                    }
                }
            )
        }

        view.findViewById<Button>(R.id.pastePubKey).setOnClickListener {
            when {
                !clipboard.hasPrimaryClip() -> {
                    Toast.makeText(context, "PubKey not found in clipboard", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    try {
                        val item = clipboard.primaryClip?.getItemAt(0)
                        onScan(item?.text.toString())
                    } catch (e: Exception) {
                        Toast.makeText(context, "PubKey not found in clipboard", Toast.LENGTH_SHORT).show()
                    }

                }
            }
        }

    }

    private fun getXpubFromUR(it: URDecoder.Result): URAccountDecode {
        if (it.ur.registryType == RegistryType.CRYPTO_ACCOUNT) {
            val cryptoAccount = it.ur.decodeFromRegistry() as CryptoAccount
            fingerprintHex = Hex.toHexString(cryptoAccount.masterFingerprint).lowercase()
            val keys = ArrayList<URAccountKey>()
            val skipped = ArrayList<String>()
            for (outputDescriptor in cryptoAccount.outputDescriptors) {
                val cryptoHDKey = outputDescriptor.hdKey
                var lastChild = ChildNumber.ZERO
                var depth = 1
                var parentFingerprint = ByteArray(4)
                if (cryptoHDKey.origin != null) {
                    if (cryptoHDKey.origin.components.isNotEmpty()) {
                        val lastComponent =
                            cryptoHDKey.origin.components[cryptoHDKey.origin.components.size - 1]
                        if (lastComponent is IndexPathComponent) {
                            lastChild = ChildNumber(lastComponent.index, lastComponent.isHardened)
                        }
                        depth = cryptoHDKey.origin.depth
                    }
                    if (cryptoHDKey.origin.sourceFingerprint != null) {
                        parentFingerprint = cryptoHDKey.parentFingerprint
                    }
                }
                val parentFingerprintInt = ByteBuffer.wrap(parentFingerprint).int

                // #254: purpose parsed guarded - the old bare
                // substring(0..1) threw on short paths, NPE'd on a null
                // origin, and coerced unknown purposes into BIP84 bytes.
                val purpose = urPurposeFromPath(cryptoHDKey.origin?.path)
                val type = urAccountType(purpose)
                if (type == null) {
                    skipped.add("purpose ${purpose ?: "unparsed"} (unsupported)")
                    continue
                }
                val version = when (type) {
                    AddressTypes.BIP44 ->
                        if (SentinelState.isTestNet()) XPUB.MAGIC_TPUB else XPUB.MAGIC_XPUB
                    AddressTypes.BIP49 ->
                        if (SentinelState.isTestNet()) XPUB.MAGIC_UPUB else XPUB.MAGIC_YPUB
                    else ->
                        if (SentinelState.isTestNet()) XPUB.MAGIC_VPUB else XPUB.MAGIC_ZPUB
                }
                val xpub = XPUB.makeXPUB(
                    version,
                    depth.toByte(),
                    parentFingerprintInt,
                    lastChild.i,
                    cryptoHDKey.chainCode,
                    cryptoHDKey.key
                )
                keys.add(
                    URAccountKey(
                        xpub = xpub,
                        label = "Deposit BIP${purpose}",
                        type = type
                    )
                )
            }
            // #254: every descriptor is emitted - the old loop returned on
            // the first one; duplicates collapse loudly, never silently.
            val deduped = dedupeAccountKeys(keys)
            return URAccountDecode(deduped.keys, skipped + deduped.skipped)
        }
        else if (it.ur.registryType == RegistryType.BYTES) {
            val urBytes = it.ur.decodeFromRegistry()
            val decoder = StandardCharsets.UTF_8.newDecoder()
            val buf = ByteBuffer.wrap(urBytes as ByteArray)
            val charBuffer = decoder.decode(buf)
            val xpubsJson = runCatching { JSONObject(charBuffer.toString()) }
                .getOrElse { return URAccountDecode(emptyList(), emptyList()) }
            try {
                fingerprintHex = xpubsJson.getString("xfp").lowercase()
            } catch (e: Exception) {}
            // #254: every leg the payload carries - the old read surfaced
            // bip84 only and dropped bip44/bip49 on the floor.
            return urBytesKeys(xpubsJson)
        }
        return URAccountDecode(emptyList(), emptyList())
    }

    override fun onResume() {
        super.onResume()
        if (AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, appContext)) {
            mCodeScanner?.startScanner()
        }
    }

    override fun onPause() {
        mCodeScanner?.stopScanner()
        super.onPause()
    }

}

class SelectAddressTypeFragment : Fragment() {
    private var onSelect: (type: AddressTypes) -> Unit = {}
    var addressType: AddressTypes = AddressTypes.BIP84

    private var _binding: ContentChooseAddressTypeBinding? = null
    private val binding get() = _binding!!


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = ContentChooseAddressTypeBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    fun setOnSelectListener(callback: (type: AddressTypes) -> Unit = {}) {
        this.onSelect = callback
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        binding.radioGroup.setOnCheckedChangeListener { _, checkedId ->
            addressType = when (checkedId) {
                R.id.buttonBIP84 -> AddressTypes.BIP84
                R.id.buttonBIP49 -> AddressTypes.BIP49
                R.id.buttonBIP44 -> AddressTypes.BIP44
                else -> addressType
            }
        }
        binding.nextBtn.setOnClickListener {
            onSelect(addressType)
        }

        binding.buttonBIP84.setOnClickListener(View.OnClickListener {
            addressType = AddressTypes.BIP84
        })
        binding.buttonBIP49.setOnClickListener(View.OnClickListener {
            addressType = AddressTypes.BIP49
        })
        binding.buttonBIP44.setOnClickListener(View.OnClickListener {
            addressType = AddressTypes.BIP44
        })

        when (addressType) {
            AddressTypes.BIP44 -> {
                binding.buttonBIP44.isChecked = true
            }
            AddressTypes.BIP49 -> {
                binding.buttonBIP49.isChecked = true
            }
            AddressTypes.BIP84 -> {
                binding.buttonBIP84.isChecked = true
            }
            AddressTypes.ADDRESS -> {
                //No-op
            }
        }
    }

    fun setType(type: AddressTypes?) {
        addressType = when (type) {
            AddressTypes.BIP49 -> AddressTypes.BIP49
            AddressTypes.BIP84 -> AddressTypes.BIP84
            else -> AddressTypes.BIP84
        }

        if (_binding != null) {
            when (addressType) {
                AddressTypes.BIP44 -> binding.buttonBIP44.isChecked = true
                AddressTypes.BIP49 -> binding.buttonBIP49.isChecked = true
                AddressTypes.BIP84 -> binding.buttonBIP84.isChecked = true
                AddressTypes.ADDRESS -> Unit
            }
        }
    }

}

class ChooseCollectionFragment : Fragment() {

    private val repository: CollectionRepository by KoinJavaComponent.inject(CollectionRepository::class.java)
    private val collectionsAdapter = CollectionsAdapter()
    private var onSelect: (PubKeyCollection?) -> Unit = {}

    private var _binding: ContentCollectionSelectBinding? = null
    private val binding get() = _binding!!


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = ContentCollectionSelectBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    fun setOnSelectListener(callback: (PubKeyCollection?) -> Unit = {}) {
        this.onSelect = callback
    }


    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setUpCollectionSelectList()
        binding.createNewCollection.setOnClickListener {
            this.onSelect(null)
        }
    }

    private fun setUpCollectionSelectList() {
        repository.collectionsLiveData.observe(viewLifecycleOwner, Observer {
            val iterator = it.iterator()

            while (iterator.hasNext()) {
                val collection = iterator.next()
                if (collection.isImportFromWallet)
                    iterator.remove()
            }

            collectionsAdapter.update(it)
        })
        collectionsAdapter.setLayoutType(CollectionsAdapter.Companion.LayoutType.STACKED)
        val linearLayoutManager = LinearLayoutManager(context)
        linearLayoutManager.orientation = LinearLayoutManager.VERTICAL
        val decorator = RecyclerViewItemDividerDecorator(ContextCompat.getDrawable(requireContext(), R.drawable.divider_grey)!!)
        binding.collectionSelectRecyclerView.apply {
            adapter = collectionsAdapter
            layoutManager = linearLayoutManager
            setHasFixedSize(true)
            addItemDecoration(decorator)
        }

        collectionsAdapter.setOnClickListener {
            this.onSelect(it)
        }
    }


}