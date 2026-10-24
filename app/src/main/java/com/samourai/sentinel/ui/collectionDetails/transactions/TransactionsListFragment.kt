package com.samourai.sentinel.ui.collectionDetails.transactions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.samourai.sentinel.R
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.db.dao.TxDao
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.LabelRepository
import com.samourai.sentinel.data.repository.TransactionsRepository
import com.samourai.sentinel.ui.fragments.TransactionsDetailsBottomSheet
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.RecyclerViewItemDividerDecorator
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent
import timber.log.Timber

/**
 * #217: the old primary constructor (position, collection) has no
 * zero-argument form. FragmentManager restores by class name plus
 * that constructor, so a recreated Collection Details screen crashed
 * inside Activity.onCreate, before #300's lock gate could run.
 * Position and collection id travel in the arguments Bundle, which
 * the FragmentManager already saves. The collection itself is loaded
 * back from the repository when the list is first shown.
 */
class TransactionsListFragment : Fragment() {

    private val transactionAdapter: TransactionAdapter = TransactionAdapter()
    private val prefs: PrefsUtil by KoinJavaComponent.inject(PrefsUtil::class.java)
    private val labelRepository: LabelRepository by KoinJavaComponent.inject(LabelRepository::class.java)
    private val repository: CollectionRepository by KoinJavaComponent.inject(CollectionRepository::class.java)

    class TransactionsViewModel(val pubKeyCollection: PubKeyCollection, val position: Int) : ViewModel() {
        private fun getPubKeyModelByLabel(label: String): PubKeyModel {
            pubKeyCollection.pubs.forEach {
                if (it.label == label)
                    return it
            }
            return pubKeyCollection.pubs[0]
        }

        private val txDao: TxDao by KoinJavaComponent.inject(TxDao::class.java)
        // #284: Room LiveData, same invalidation path as the sync reconcile.
        // A PagedList built while this table was still empty (balance is
        // published per pubkey BEFORE saveTx) stayed empty until this
        // fragment was destroyed and recreated.
        val txLiveData: LiveData<List<Tx>> = if (position == 0)
            txDao.observeCollectionTx(pubKeyCollection.id)
        else if (pubKeyCollection.isImportFromWallet && position == 1)
            txDao.observeTxForPubKeys(pubKeyCollection.id,
                listOf(getPubKeyModelByLabel("Deposit BIP84").pubKey,
                    getPubKeyModelByLabel("Deposit BIP49").pubKey,
                    getPubKeyModelByLabel("Deposit BIP44").pubKey))
        else txDao.observeTxForPubKey(pubKeyCollection.id, pubKeyCollection.pubs[position - 1].pubKey)

        class TransactionsViewModelFactory(private val pubKeyCollection: PubKeyCollection, private val position: Int) : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(TransactionsViewModel::class.java)) {
                    return TransactionsViewModel(pubKeyCollection, position) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }

        companion object {
            fun getFactory(pubKeyCollection: PubKeyCollection, position: Int): TransactionsViewModelFactory {
                return TransactionsViewModelFactory(pubKeyCollection, position)
            }
        }
    }


    private val transactionViewModel: TransactionsViewModel by viewModels(factoryProducer = {
        val args = requireArguments()
        val position = args.getInt(ARG_POSITION)
        val id = args.getString(ARG_COLLECTION_ID)
            ?: error("TransactionsListFragment missing collection id")
        val collection = repository.findById(id)
            ?: error("TransactionsListFragment collection $id is not loaded")
        TransactionsViewModel.getFactory(collection, position)
    })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_transactions_segement, container, false)
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val transactionsRecycler = view.findViewById<RecyclerView>(R.id.transactionsRecycler)

        transactionsRecycler.layoutManager = LinearLayoutManager(context)
        transactionsRecycler.adapter = transactionAdapter

        val decorator = RecyclerViewItemDividerDecorator(ContextCompat.getDrawable(requireContext(), R.drawable.divider_tx)!!)
        transactionsRecycler.addItemDecoration(decorator)
        transactionAdapter.setOnclickListener {
            val dojoConfigureBottomSheet = TransactionsDetailsBottomSheet.newInstance(it, secure = prefs.displaySecure!!)
            dojoConfigureBottomSheet.show(childFragmentManager, dojoConfigureBottomSheet.tag)
        }

        transactionViewModel.txLiveData.observe(this.viewLifecycleOwner) { txs ->
            // commit callback: ListAdapter applies the diff async. Ask for
            // another measure once rows exist so a pager that measured this
            // page at 0 (pre-#284 wrap_content) cannot freeze that height.
            transactionAdapter.submitList(txs) {
                transactionsRecycler.requestLayout()
            }
        }

        labelRepository.observeTxLabelMap().observe(this.viewLifecycleOwner) { map ->
            transactionAdapter.txLabels = map
        }
    }

    override fun onResume() {
        super.onResume()
        transactionAdapter.notifyDataSetChanged()
    }

    companion object {
        private const val ARG_POSITION = "position"
        private const val ARG_COLLECTION_ID = "collectionId"

        fun newInstance(position: Int, collection: PubKeyCollection): TransactionsListFragment =
            TransactionsListFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_POSITION, position)
                    putString(ARG_COLLECTION_ID, collection.id)
                }
            }
    }

}
