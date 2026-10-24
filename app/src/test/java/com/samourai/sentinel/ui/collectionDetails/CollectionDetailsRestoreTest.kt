package com.samourai.sentinel.ui.collectionDetails

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #217. FragmentManager instantiates a restored fragment with the
 * zero-argument constructor. TransactionsListFragment used to require
 * (position, collection), so Collection Details died inside
 * Activity.onCreate. The pager's field instances are not the restored
 * fragments; the activity has to look them up by the ViewPager2 tag.
 */
class CollectionDetailsRestoreTest {

    @Test
    fun `the tx list fragment can be restored and the pager looks up live pages`() {
        val list = source(
            "app/src/main/java/com/samourai/sentinel/ui/collectionDetails/transactions/TransactionsListFragment.kt",
        )
        assertTrue(list.contains("class TransactionsListFragment : Fragment()"))
        assertFalse(list.contains("class TransactionsListFragment("))
        assertTrue(list.contains("fun newInstance(position: Int, collection: PubKeyCollection)"))
        val parent = source(
            "app/src/main/java/com/samourai/sentinel/ui/collectionDetails/transactions/TransactionsFragment.kt",
        )
        assertTrue(parent.contains("TransactionsListFragment.newInstance(position, collection)"))
        assertFalse(parent.contains("TransactionsListFragment(position, collection)"))
        val activity = source(
            "app/src/main/java/com/samourai/sentinel/ui/collectionDetails/CollectionDetailsActivity.kt",
        )
        assertTrue(activity.contains("findFragmentByTag(\"f0\")"))
        assertTrue(activity.contains("findFragmentByTag(\"f1\")"))
        assertTrue(activity.contains("findFragmentByTag(\"f2\")"))
        val receive = source(
            "app/src/main/java/com/samourai/sentinel/ui/collectionDetails/receive/ReceiveFragment.kt",
        )
        assertTrue(receive.contains("if (view != null)"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
