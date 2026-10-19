package com.samourai.sentinel.ui.views

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.samourai.sentinel.ui.dojo.CommunityDojoListBottomSheet
import com.samourai.sentinel.ui.dojo.DojoConfigureBottomSheet
import com.samourai.sentinel.ui.dojo.DojoCredentialsBottomSheet
import com.samourai.sentinel.ui.fragments.AddNewPubKeyBottomSheet
import com.samourai.sentinel.ui.fragments.QRBottomSheetDialog
import com.samourai.sentinel.ui.fragments.TransactionsDetailsBottomSheet
import com.samourai.sentinel.ui.home.HomeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #210 recreation safety, JVM side.
 *
 * Leg 1 - constructor census: Android rebuilds a showing dialog fragment
 * through its public no-arg constructor (Fragment.instantiate). A class
 * without one crashes with Fragment$InstantiationException the moment the
 * activity is recreated behind it. Every member of the bottom-sheet family
 * must be instantiable with zero arguments; each name is echoed so the
 * census output doubles as a family manifest.
 *
 * Leg 2 - save/restore round-trip for the first-run "Choose network"
 * sheet: the host is torn down and rebuilt from saved instance state
 * with the sheet showing - the recreation of #210. The FragmentManager
 * restore path (FragmentState.instantiate) must rebuild the sheet
 * through its public no-arg constructor - pre-#210 this is exactly
 * where Fragment$InstantiationException fired - and every
 * answer-delivery input must survive in arguments.
 *
 * Two environment facts shape leg 2:
 * - the hosts are driven through Robolectric's ActivityController
 *   because AGP does not merge a test-only manifest for unit tests
 *   (robolectric#4726), so there is no launchable host for
 *   ActivityScenario to resolve
 * - the restore checks run at the CREATED level: the sheet's layouts
 *   inflate fine under the app's real dialog theme on device, but
 *   trip Robolectric's style-item theme-attribute resolution gap
 *   ("Failed to resolve attribute at index 6", observed while
 *   developing this test), so view inflation and the physical button
 *   tap run in the device-script leg, where the app's real themes
 *   apply. The mailbox-delivery half drives the rebuilt host to
 *   STARTED after the restored sheet is removed - Fragment Result
 *   delivery is lifecycle-gated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BottomSheetRecreationTest {

    /**
     * The whole dialog family: the classes this PR rebuilt with explicit
     * no-arg primaries (stages 1-6a), plus the siblings that already
     * restore cleanly because every constructor parameter defaults
     * (AddNewPubKey, DojoCredentials) or they never took parameters
     * (DojoConfigure).
     */
    private val family: List<Class<out Fragment>> = listOf(
        GenericBottomSheet::class.java,
        InputBottomSheet::class.java,
        ConfirmBottomSheet::class.java,
        SuccessfulBottomSheet::class.java,
        LockScreenDialog::class.java,
        QRBottomSheetDialog::class.java,
        TransactionsDetailsBottomSheet::class.java,
        CommunityDojoListBottomSheet::class.java,
        DojoConfigureBottomSheet::class.java,
        DojoCredentialsBottomSheet::class.java,
        AddNewPubKeyBottomSheet::class.java
    )

    @Test
    fun `every dialog family class is instantiable with zero arguments`() {
        family.forEach { clazz ->
            try {
                clazz.getDeclaredConstructor().newInstance()
                println("recreation-safe: ${clazz.simpleName}")
            } catch (t: Throwable) {
                fail(
                    "${clazz.name} has no usable no-arg constructor - " +
                        "Android cannot rebuild it after a recreation: $t"
                )
            }
        }
    }

    @Test
    fun `first-run network sheet round-trips save-restore with its contract intact`() {
        // First host: created but not started. At the CREATED level the
        // sheet is added and moves through onCreate - where the #210
        // arguments contract lives - without any view inflation.
        val first = Robolectric.buildActivity(FragmentActivity::class.java).create()

        ConfirmBottomSheet.newInstance(
            label = "Choose network",
            positiveText = "Mainnet",
            negativeText = "Testnet",
            isCancelable = false,
            requestKey = HomeActivity.REQUEST_KEY_FIRST_RUN_NETWORK
        ).show(first.get().supportFragmentManager, HomeActivity.TAG_FIRST_RUN_NETWORK_SHEET)
        first.get().supportFragmentManager.executePendingTransactions()

        val shown = first.get().supportFragmentManager
            .findFragmentByTag(HomeActivity.TAG_FIRST_RUN_NETWORK_SHEET) as? ConfirmBottomSheet
        assertNotNull("sheet must be added before save", shown)
        assertFalse(
            "non-cancelable contract must hold before save",
            shown?.isCancelable == true
        )

        // The recreation of #210: save instance state, tear the host
        // down, rebuild from that state - the lifecycle a ui-mode change
        // or process death drives on device.
        val outState = Bundle()
        first.saveInstanceState(outState)
        first.destroy()

        // FragmentActivity.onCreate rehydrates the FragmentManager from
        // outState: FragmentState.instantiate rebuilds the sheet through
        // its public no-arg constructor here. Pre-#210 this is where
        // Fragment$InstantiationException fired.
        val second = Robolectric.buildActivity(FragmentActivity::class.java).create(outState)
        val restoredFragment = second.get().supportFragmentManager
            .findFragmentByTag(HomeActivity.TAG_FIRST_RUN_NETWORK_SHEET)
        assertNotNull("sheet must be restored from saved state", restoredFragment)
        val restored = restoredFragment as? ConfirmBottomSheet
        assertTrue("restored sheet must be a ConfirmBottomSheet", restored != null)

        // Everything the rebuilt buttons and the durable listener need
        // must have survived in arguments.
        val args = restored!!.requireArguments()
        assertEquals("Choose network", args.getString(ConfirmBottomSheet.ARG_LABEL))
        assertEquals("Mainnet", args.getString(ConfirmBottomSheet.ARG_POSITIVE_TEXT))
        assertEquals("Testnet", args.getString(ConfirmBottomSheet.ARG_NEGATIVE_TEXT))
        assertEquals(
            HomeActivity.REQUEST_KEY_FIRST_RUN_NETWORK,
            args.getString(ConfirmBottomSheet.ARG_REQUEST_KEY)
        )
        assertFalse(
            "non-cancelable contract must survive restore",
            restored.isCancelable
        )

        // The Tier A mailbox half: the durable listener HomeActivity
        // registers in onCreate, re-registered here on the rebuilt
        // instance in the same order, must receive an answer delivered
        // through the stable request key. The physical button tap that
        // produces it runs in the device-script leg, where the app's
        // real themes apply.
        var confirmed: Boolean? = null
        second.get().supportFragmentManager.setFragmentResultListener(
            HomeActivity.REQUEST_KEY_FIRST_RUN_NETWORK,
            second.get()
        ) { _, bundle ->
            confirmed = bundle.getBoolean(ConfirmBottomSheet.RESULT_CONFIRMED)
        }

        // The restored sheet's role ends here: driving the host past
        // CREATED inflates its views - the Robolectric theme-attribute
        // gap again - so the sheet is removed before the host advances.
        // The mailbox half needs only the rebuilt host's
        // FragmentManager and the stable request key.
        second.get().supportFragmentManager.beginTransaction().remove(restored!!).commitNow()

        // Fragment Result delivery is lifecycle-gated: the listener
        // fires once the host reaches STARTED. Production delivers at
        // RESUMED, when the user actually taps Mainnet - the device leg
        // covers the tap itself.
        second.start()

        // The answer the restored sheet's positive button would post:
        second.get().supportFragmentManager.setFragmentResult(
            HomeActivity.REQUEST_KEY_FIRST_RUN_NETWORK,
            Bundle().apply { putBoolean(ConfirmBottomSheet.RESULT_CONFIRMED, true) }
        )
        assertEquals("durable listener on the rebuilt host must receive the answer", true, confirmed)

        second.destroy()
    }
}
