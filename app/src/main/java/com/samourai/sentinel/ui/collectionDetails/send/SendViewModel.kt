package com.samourai.sentinel.ui.collectionDetails.send

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.db.dao.UtxoDao
import com.samourai.sentinel.send.FeeUtil
import com.samourai.sentinel.send.SuggestedFee
import com.samourai.sentinel.util.FormatsUtil
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Locale
import kotlin.collections.set

class SendViewModel : ViewModel() {

    private val utxos: ArrayList<Utxo> = arrayListOf()
    private val selectedUtxos: ArrayList<Utxo> = arrayListOf()
    private var address: String = ""
    private var amount: Double = 0.0
    private var enteredAmount: Double = 0.0
    var job: Job? = null
    private var selectedFee = 0L
    private var transactionComposer = TransactionComposer()
    private val utxoDao: UtxoDao by inject(UtxoDao::class.java)
    private var receivers: HashMap<String, BigInteger> = hashMapOf()

    private val _psbt = MutableLiveData("")
    val psbtLive: LiveData<String> = _psbt

    private val _minerFee = MutableLiveData("")
    val minerFee: LiveData<String> = _minerFee

    private val _validSpend = MutableLiveData(false)
    val validSpend: LiveData<Boolean> = _validSpend

    /**
     * #294: why composing is not possible, so the screen can say so.
     *
     * validSpend on its own only greys the button out. Every reason was
     * computed here and then discarded, leaving the user to guess whether the
     * address was wrong, on the wrong network, or simply more than they hold.
     * Published separately so existing observers of validSpend are unaffected.
     */
    enum class SpendBlocker {
        NONE,
        NO_ADDRESS,
        INVALID_ADDRESS,
        WRONG_NETWORK,
        NO_AMOUNT,
        NO_FUNDS,
        INSUFFICIENT_FUNDS,
        CANNOT_COMPOSE
    }

    private val _spendBlocker = MutableLiveData(SpendBlocker.NO_ADDRESS)
    val spendBlocker: LiveData<SpendBlocker> = _spendBlocker

    private fun block(reason: SpendBlocker): Boolean {
        _spendBlocker.postValue(reason)
        _validSpend.postValue(false)
        // #294: a figure computed from input we have just rejected is worse
        // than none - the fee kept reading 0.00000850 BTC next to a disabled
        // button on an amount the wallet could not cover.
        if (reason != SpendBlocker.NONE) {
            _minerFee.postValue("")
        }
        return false
    }

    /** Spendable total, excluding anything marked do-not-spend. */
    private fun spendableSats(): Long =
        utxos.filter { !UtxoMetaUtil.has(it) }.sumOf { it.value ?: 0L }


    init {
        //Listen for the miner fee change from TransactionComposer
        viewModelScope.launch {
            for (value in transactionComposer.getMinerFee()) {
                withContext(Dispatchers.Main) {
                    val formattedFee = MonetaryUtil.getInstance().getBTCDecimalFormat(value)
                    _minerFee.postValue("$formattedFee BTC")
                }
            }
        }
    }

    fun setPublicKey(
        pubKeyModel: PubKeyModel,
        lifecycleOwner: LifecycleOwner,
        mCollection: PubKeyCollection
    ) {
        if (mCollection.isImportFromWallet && pubKeyModel.label.equals("Deposit")) {
            transactionComposer.setPubKey(listOf(
                getPubKeyModelByLabel("Deposit BIP84", mCollection),
                getPubKeyModelByLabel("Deposit BIP49", mCollection),
                getPubKeyModelByLabel("Deposit BIP44", mCollection)
            ))
            utxoDao.getUTXObyPubKeys(listOf(
                getPubKeyModelByLabel("Deposit BIP84", mCollection).pubKey,
                getPubKeyModelByLabel("Deposit BIP49", mCollection).pubKey,
                getPubKeyModelByLabel("Deposit BIP44", mCollection).pubKey
            ))
                .observe(lifecycleOwner, utxoObserver)
        } else {
            transactionComposer.setPubKey(listOf(pubKeyModel))
            utxoDao.getUtxoWithPubKey(pubKeyModel.pubKey)
                .observe(lifecycleOwner, utxoObserver)
        }
    }

    private fun getPubKeyModelByLabel(label: String, mCollection: PubKeyCollection): PubKeyModel {
        mCollection.pubs?.forEach {
            if (it.label == label)
                return it
        }
        return mCollection.pubs[0]
    }

    private fun prepareSpend(): Boolean {
        selectedUtxos.clear()

        if (address.isBlank() || address.isEmpty()) {
            return block(SpendBlocker.NO_ADDRESS)
        }
        // #294: the address was previously only judged inside compose(), which
        // reports one undifferentiated failure. Check it here so the three
        // cases the user can actually act on are told apart.
        if (!FormatsUtil.isValidBitcoinAddress(address)) {
            return block(SpendBlocker.INVALID_ADDRESS)
        }
        if (isWrongNetworkAddress(address)) {
            return block(SpendBlocker.WRONG_NETWORK)
        }

        val dAmount: Double = enteredAmount
        amount = (dAmount * 1e8)

        if (dAmount == 0.0) {
            return block(SpendBlocker.NO_AMOUNT)
        }

        if (utxos.isEmpty()) {
            return block(SpendBlocker.NO_FUNDS)
        }
        if (amount <= 0.0) {
            return block(SpendBlocker.NO_AMOUNT)
        }
        // Checked before composing so "more than you hold" is distinguishable
        // from a compose that failed for any other reason. The fee is not
        // known yet, so this does not catch an amount that only fails once the
        // fee is added; compose() still reports that as CANNOT_COMPOSE.
        if (amount.toLong() > spendableSats()) {
            return block(SpendBlocker.INSUFFICIENT_FUNDS)
        }
        job?.let {
            if (it.isActive) {
                it.cancel()
            }
        }
        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                receivers = HashMap()
                receivers[address] = BigInteger.valueOf(amount.toLong())
                try {
                    val isValid = transactionComposer.compose(
                        inputAddress = address,
                        inputAmount = amount,
                        inputFee = selectedFee,
                        inputUtxos = ArrayList<Utxo>(utxos.filter { utxo ->  !UtxoMetaUtil.has(utxo)})
                    )
                    withContext(Dispatchers.Main) {
                        _validSpend.postValue(isValid)
                        _spendBlocker.postValue(
                            if (isValid) SpendBlocker.NONE else SpendBlocker.CANNOT_COMPOSE
                        )
                    }
                } catch (e: com.samourai.sentinel.api.ApiService.ApiNotConfigured) {
                    // #125: compose() reaches getTxHex (:351/:375) -> buildClient ->
                    // getAPIUrl, throwing this on an unpaired install. Throwable, so the
                    // Exception wall below is blind and viewModelScope has no CEH: the
                    // throw would have escaped the compose job from the send screen. Same
                    // degrade as :132-136. FQN deliberate: this file has no ApiService
                    // import; simple-name cleanup may follow.
                    Timber.e(e, "Compose aborted: no API endpoint configured")
                    viewModelScope.launch(Dispatchers.Main) {
                        block(SpendBlocker.CANNOT_COMPOSE)
                    }
                } catch (e: Exception) {
                    viewModelScope.launch(Dispatchers.Main) {
                        block(SpendBlocker.CANNOT_COMPOSE)
                    }
                    Timber.e(e, "Compose failed")
                }
            } catch (ex: TransactionComposer.ComposeException) {
                throw CancellationException("unable to compose")
            }
        }
        job?.invokeOnCompletion {
            if (it != null) {
                it.printStackTrace()
                viewModelScope.launch(Dispatchers.Main) {
//                    Toast.makeText(requireContext(), it.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
//        transactionComposer.setFeeCallBack { l, l2 ->
////            composeTransactionFragment.setMinerFeeTotal("${MonetaryUtil.getInstance().formatToBtc(l)} BTC")
//        }

        return true
    }

    /**
     * #294: a bech32 address for the other network.
     *
     * isValidBitcoinAddress() decodes bech32 without comparing the human
     * readable part to the active network, so a mainnet bc1 address passes it
     * on testnet and then fails inside compose() with no explanation. Base58
     * addresses are already rejected by that function, since it builds an
     * Address against the active NetworkParameters.
     */
    private fun isWrongNetworkAddress(address: String): Boolean {
        val hrp = address.lowercase().take(2)
        if (hrp != "bc" && hrp != "tb") return false
        return if (SentinelState.isTestNet()) hrp == "bc" else hrp == "tb"
    }

    fun setAmount(amount: Double) {
        enteredAmount = amount
        prepareSpend()
    }

    private val utxoObserver: Observer<List<Utxo>> = Observer<List<Utxo>> {
        utxos.clear()
        utxos.addAll(it)
        if (utxos.isEmpty()) {
            block(SpendBlocker.NO_FUNDS)
        } else {
            prepareSpend()
        }
    }

    fun setDestinationAddress(enteredAddress: String) {
        this.address = enteredAddress
        prepareSpend()
    }

    fun setFee(fee: Float) {
        Timber.i("setFee: $fee")
        val decFormat = NumberFormat.getInstance(Locale.US)
        decFormat.maximumFractionDigits = 3
        decFormat.minimumFractionDigits = 0
        var customValue = 0.0
        customValue = try {
            fee.toDouble()
        } catch (e: java.lang.Exception) {
            return
        }
        val suggestedFee = SuggestedFee()
        suggestedFee.isStressed = false
        suggestedFee.isOK = true
        suggestedFee.defaultPerKB = BigInteger.valueOf((customValue).toLong())
        FeeUtil.getInstance().suggestedFee = suggestedFee
        selectedFee = fee.toLong()
        prepareSpend()
    }

    fun makeTx(): Boolean {
        if (validSpend.value == true) {
            val psbt = transactionComposer.getPSBT()
            psbt?.let {
                _psbt.postValue(it.toString())
                return true
            }
            return false
        }
        return false
    }

    fun getPsbtBytes(): ByteArray? {
        if (validSpend.value == true) {
            val psbt = transactionComposer.getPSBT()
            return psbt?.toBytes()
        }
        return null
    }


}
