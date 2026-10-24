package com.samourai.sentinel.ui.collectionDetails.transactions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.samourai.sentinel.R
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.databinding.FragmentTransactionsBinding
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.collectionEdit.CollectionEditActivity
import com.samourai.sentinel.ui.dojo.PubKeyRescanner
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.utxos.UtxosActivity
import com.samourai.sentinel.util.BalanceDisplayFormatter
import com.samourai.sentinel.util.BalanceDisplayMode
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import org.koin.java.KoinJavaComponent.inject
import java.text.DecimalFormat


class TransactionsFragment : Fragment() {

    private lateinit var fiatBalanceLiveData: LiveData<String>
    private lateinit var balanceLiveData: LiveData<Long>
    private val transactionsViewModel: TransactionsViewModel by viewModels()
    private lateinit var collection: PubKeyCollection
    private val monetaryUtil: MonetaryUtil by inject(MonetaryUtil::class.java)
    private var _binding: FragmentTransactionsBinding? = null
    private val binding get() = _binding!!
    private val prefsUtil: com.samourai.sentinel.ui.utils.PrefsUtil by inject(com.samourai.sentinel.ui.utils.PrefsUtil::class.java)
    private val exchangeRateRepository: ExchangeRateRepository by inject(ExchangeRateRepository::class.java)
    val indexPubSelected: MutableLiveData<Int> by lazy { MutableLiveData<Int>() }
    val df = DecimalFormat("#")
    private var tabChangeListener: OnTabChangedListener? = null

    // #204: pager wiring state. The tab pager is wired ONCE (onViewCreated)
    // and rebuilt ONLY when the pubkey set actually changes. The previous
    // shape re-ran the full wiring on EVERY collectionsLiveData emission
    // (once per pubkey per sync round): that tore down all tab lists
    // mid-sync, stacked one TabLayoutMediator per emission - each re-attach
    // re-selected tab 0, the "rolls back to All" bug - and stacked duplicate
    // tab-selected listeners.
    private var tabMediator: TabLayoutMediator? = null
    private var pagerWired = false
    private var lastPubsFingerprint: String? = null


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTransactionsBinding.inflate(inflater, container, false)
        val view = binding.root
        df.minimumIntegerDigits = 1
        df.minimumFractionDigits = 8
        df.maximumFractionDigits = 8

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.mpm_black)
        requireActivity().window.navigationBarColor = ContextCompat.getColor(requireContext(), R.color.grey_homeActivity)

        initViewModel()

        setUpToolBar()
    }

    /**
     * #204: ONE-TIME wiring of the tab pager, run from onViewCreated. The
     * per-emission entry point (initViewModel(PubKeyCollection)) guards on
     * pagerWired so a re-delivery can never land here twice.
     */
    private fun initViewModel() {
        transactionsViewModel.setCollection(collection)

        // #284: tab pages are children of THIS fragment. Hosting them on
        // the activity (the #181 bug) lets the pager bind a page before
        // its view exists; that page then never rebinds, so the balance
        // (this fragment) shows and the tx list stays empty until the
        // screen is destroyed and recreated.
        binding.txViewPager.adapter = CollectionPubKeysViewpager(this, collection)
        binding.txViewPager.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.grey_homeActivity))
        binding.txViewPager.offscreenPageLimit = 5

        tabMediator = TabLayoutMediator(binding.tabLayout, binding.txViewPager) { tab, position ->
            if (collection.isImportFromWallet) {
                when (position) {
                    0 -> tab.text = "All"
                    1 -> tab.text = "Deposit"
                    2 -> tab.text = "Premix"
                    3 -> tab.text = "Postmix"
                    4 -> tab.text = "Badbank"
                }
            }
            else {
                if (position == 0)
                    tab.text = "All"
                else
                    tab.text = collection.pubs[position - 1].label
            }
        }.also { it.attach() }


        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab:
                                       TabLayout.Tab
            ) {
                if (tabChangeListener != null)
                    tabChangeListener?.onTabChanged(tab?.position!!-1)
                indexPubSelected.value = tab?.position!!
                setBalance(tab.position-1)
            }

            override fun onTabUnselected(tab:
                                         TabLayout.Tab
            ) {
                println("Tab unselected: " + tab?.text)
            }

            override fun onTabReselected(tab:
                                         TabLayout.Tab
            ) {
                println("Tab reselected: " + tab?.text)
            }

        })

        balanceLiveData.observe(viewLifecycleOwner) {
            if (it != null) {
                renderBalance(it)
                setBalance(-1)
            }
        }

        binding.collectionBalanceBtc.setOnLongClickListener {
            goToUTXOActivity()
            true
        }

        // Tap to cycle BTC -> sats -> masked -> BTC (same global mode as home).
        binding.collectionBalanceBtc.setOnClickListener {
            prefsUtil.balanceDisplayMode =
                BalanceDisplayMode.fromString(prefsUtil.balanceDisplayMode).next().name
            if (indexPubSelected.value != null)
                setBalance(indexPubSelected.value!! - 1)
            else
                setBalance(-1)
        }

        binding.collectionBalanceFiat.setOnLongClickListener {
            goToUTXOActivity()
            true
        }

        binding.collectionBalanceFiat.visibility =
            if (prefsUtil.fiatDisabled == true || prefsUtil.streetMode == true
                || BalanceDisplayMode.fromString(prefsUtil.balanceDisplayMode) != BalanceDisplayMode.BTC
            ) View.INVISIBLE else View.VISIBLE
        fiatBalanceLiveData.observe(viewLifecycleOwner, Observer {
            if (isAdded) setBalance(indexPubSelected.value?.minus(1) ?: -1)
        })
        transactionsViewModel.getMessage().observe(
            this.viewLifecycleOwner
        ) {
            if (it != "null")
                (requireActivity() as AppCompatActivity)
                    .showFloatingSnackBar(binding.collectionBalanceBtc, "Error : $it")
        }

        pagerWired = true
        lastPubsFingerprint = pubsFingerprint(collection)
    }

    private fun goToUTXOActivity() {
        startActivityForResult(Intent(context, UtxosActivity::class.java).apply {
            putExtra("collection", collection.id)
            putExtra("indexPub", indexPubSelected.value)
        }, EDIT_REQUEST_ID)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnTabChangedListener) {
            tabChangeListener = context
        } else {
            throw ClassCastException("$context must implement OnButtonClickListener")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onResume() {
        if (indexPubSelected.value != null)
            setBalance(indexPubSelected.value!!-1)
        else
            setBalance(-1)
        super.onResume()
    }

    fun setBalance(pubkeyIndex: Int) {
        //Handle deposit tab in collection imported from wallet
        if (collection.isImportFromWallet && pubkeyIndex == 0) {
            var blockedUtxoBalanceSum = 0L
            var balance = 0L
            collection.pubs.forEach { pub ->
                if (pub.label.lowercase().contains("deposit")) {
                    balance += pub.balance
                    val blockedUtxos1 =
                        UtxoMetaUtil.getBlockedAssociatedWithPubKey(pub.pubKey)
                    blockedUtxos1.forEach { blockedUtxo ->
                        blockedUtxoBalanceSum += blockedUtxo.amount
                    }
                }
            }
            val finalBalance = balance - blockedUtxoBalanceSum
            renderBalance(finalBalance)
        }
        else {
            if (pubkeyIndex != -1) {
                var blockedUtxoBalanceSum = 0L
                val blockedUtxos =
                    UtxoMetaUtil.getBlockedAssociatedWithPubKey(collection.pubs[pubkeyIndex].pubKey)
                blockedUtxos.forEach { utxo ->
                    blockedUtxoBalanceSum += utxo.amount
                }
                val balance = collection.pubs[pubkeyIndex].balance - blockedUtxoBalanceSum

                renderBalance(balance)
            } else {
                //Handle "All" tab
                var blockedUtxosBalanceSum = 0L
                collection.pubs.forEach { pubKeyModel ->
                    val blockedUtxos1 =
                        UtxoMetaUtil.getBlockedAssociatedWithPubKey(pubKeyModel.pubKey)
                    blockedUtxos1.forEach { blockedUtxo ->
                        blockedUtxosBalanceSum += blockedUtxo.amount
                    }
                }
                val balance = collection.balance - blockedUtxosBalanceSum

                renderBalance(balance)
            }
        }
    }

    /**
     * Single render point for the collection balance. Honors the global
     * display mode (BTC / sats / masked) and street mode, which always masks.
     */
    private fun renderBalance(sats: Long) {
        val mode =
            if (prefsUtil.streetMode == true)
                BalanceDisplayMode.MASKED
            else
                BalanceDisplayMode.fromString(prefsUtil.balanceDisplayMode)
        binding.collectionBalanceBtc.text =
            BalanceDisplayFormatter.format(sats, mode)
        binding.collectionBalanceFiat.text =
            if (mode == BalanceDisplayMode.MASKED)
                BalanceDisplayFormatter.MASKED_TEXT
            else
                getFiatBalance(sats, exchangeRateRepository.getRateLive().value)
        binding.collectionBalanceFiat.visibility =
            if (prefsUtil.fiatDisabled == true || mode != BalanceDisplayMode.BTC)
                View.INVISIBLE
            else
                View.VISIBLE
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

    private fun setUpToolBar() {
        (activity as SentinelActivity).setSupportActionBar(binding.toolbarCollectionDetails)
        (activity as SentinelActivity).supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbarCollectionDetails.title = collection.collectionLabel
        binding.toolbarCollectionDetails.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.mpm_black))
        setBalance(-1)
    }

    /**
     * #204: per-emission entry. The activity's collectionsLiveData observer
     * delivers the live collection on EVERY emission - once per pubkey per
     * sync round (CollectionRepository.update -> sync -> emit), plus
     * background websocket refetches - and each delivery used to re-run the
     * full pager wiring. Now: always swap the live pointer so fetch() and
     * balance reads see the fresh object, but rebuild the pager ONLY when
     * the pubkey set actually changed (count / order / labels / import
     * flag). Balance-only refreshes leave the pager, tabs, lists and scroll
     * untouched - no teardown, no mediator stacking, no snap back to "All".
     */
    fun initViewModel(collection: PubKeyCollection) {
        val fingerprint = pubsFingerprint(collection)
        val pubsChanged = fingerprint != lastPubsFingerprint
        this.collection = collection
        this.lastPubsFingerprint = fingerprint
        // #217: a restored fragment is added before its view exists.
        // The binding work waits for onViewCreated, which calls the
        // private initViewModel once this field is set.
        if (view != null) {
            transactionsViewModel.setCollection(collection)
            if (!pagerWired) {
                // Emission arrived after attach but before onViewCreated.
                initViewModel()
            } else if (pubsChanged) {
                rebuildPager()
            }
            setUpToolBar()
        }
    }

    /**
     * #204: per-emission alias for CollectionDetailsActivity's observer
     * call site, so the hot path stays greppable against the one-time
     * checkIntent entry (which keeps calling initViewModel directly).
     */
    fun onCollectionRefreshed(collection: PubKeyCollection) = initViewModel(collection)

    /**
     * #204: real pubkey-set change (add / remove / relabel / reorder).
     * Follows the TabLayoutMediator contract (material 1.4.0-beta01,
     * verified in-tree): detach() before swapping the pager adapter,
     * attach() after - attach() re-captures the new adapter and
     * repopulates the tab strip on its own. The user's selected tab is
     * captured before the swap and restored after (clamped if the new
     * set is smaller), so a refresh mid-edit no longer snaps to "All".
     */
    private fun rebuildPager() {
        val currentTab = binding.txViewPager.currentItem
        tabMediator?.detach()
        binding.txViewPager.adapter = CollectionPubKeysViewpager(this, collection)
        val newItemCount = binding.txViewPager.adapter?.itemCount ?: 0
        if (currentTab < newItemCount) {
            binding.txViewPager.setCurrentItem(currentTab, false)
        }
        tabMediator?.attach()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.collection_detail_transaction_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        if (item.itemId == R.id.collection_details_transaction_edit_option) {
            startActivityForResult(Intent(context, CollectionEditActivity::class.java).apply {
                putExtra("collection", collection.id)
            }, EDIT_REQUEST_ID)
        }
        if (item.itemId == R.id.collection_details_transaction_utxos) {
            startActivityForResult(Intent(context, UtxosActivity::class.java).apply {
                putExtra("collection", collection.id)
                putExtra("indexPub", indexPubSelected.value)
            }, EDIT_REQUEST_ID)
        }
        if (item.itemId == R.id.collection_details_transaction_rescan) {
            // Every key in this collection: a missing balance is noticed here,
            // and the user has no reason to know which key the Dojo is missing.
            PubKeyRescanner.confirmAndRescan(
                requireActivity() as SentinelActivity,
                collection.pubs,
                listOf(collection.id)
            )
        }

        return super.onOptionsItemSelected(item)
    }

    fun setBalance(balance: LiveData<Long>) {
        this.balanceLiveData = balance
    }

    fun setBalanceFiat(fiatBalance: LiveData<String>) {
        this.fiatBalanceLiveData = fiatBalance

    }

    companion object {
        const val EDIT_REQUEST_ID = 11

        /**
         * #204: everything that determines the tab pager's SHAPE - tab
         * count, order, and labels. Deliberately excludes balance,
         * lastRefreshed, account_index and change_index: those mutate on
         * every sync emission (they are the bug trigger), and none of them
         * alter the tab strip.
         */
        internal fun pubsFingerprint(collection: PubKeyCollection): String {
            val sb = StringBuilder()
            sb.append(if (collection.isImportFromWallet) "W|" else "K|")
            collection.pubs.forEach {
                sb.append(it.pubKey).append(' ')
                sb.append(it.label).append('\u0000')
            }
            return sb.toString()
        }
    }

    private class CollectionPubKeysViewpager(
        fragment: Fragment,
        private val collection: PubKeyCollection,

        ) : FragmentStateAdapter(fragment) {
        override fun createFragment(position: Int): Fragment {
            return TransactionsListFragment.newInstance(position, collection)
        }

        override fun getItemCount(): Int {
            return if (collection.isImportFromWallet)
                collection.pubs.size - 1
            else
                collection.pubs.size + 1
        }
    }

    interface OnTabChangedListener {
        fun onTabChanged(position: Int)
    }
}
