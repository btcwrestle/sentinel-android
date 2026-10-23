package com.samourai.sentinel.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.sentinel.R
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.data.repository.FeeRepository
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.home.HomeActivity
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.LockScreenDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.io.File

/**
 * sentinel-android
 *
 */

class MainActivity : AppCompatActivity() {

    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java);
    private val dojoUtil: DojoUtility by inject(DojoUtility::class.java);
    private val accessFactory: AccessFactory by inject(AccessFactory::class.java);
    private val feeRepository: FeeRepository by inject(FeeRepository::class.java);

    private var dontDeleteFile = false;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // #220: re-registered on every create. The restored pad posts the
        // pin through REQUEST_KEY_UNLOCK_PIN; the hash it is checked
        // against already lives in prefs. Digits typed before the
        // recreation are gone on purpose.
        supportFragmentManager.setFragmentResultListener(
            REQUEST_KEY_UNLOCK_PIN,
            this
        ) { _, bundle ->
            supportFragmentManager.clearFragmentResult(REQUEST_KEY_UNLOCK_PIN)
            onUnlockPin(bundle.getString(LockScreenDialog.RESULT_PIN).orEmpty())
        }
        val pinHash = prefsUtil.pinHash
        if (!pinHash.isNullOrEmpty()) {
            // Recreation restores the pad under TAG_UNLOCK_PIN. Adding
            // another one here is how a font change used to stack two.
            if (supportFragmentManager.findFragmentByTag(TAG_UNLOCK_PIN) == null) {
                val lockScreenDialog = LockScreenDialog.newInstance(
                    message = "Enter pin code",
                    requestKey = REQUEST_KEY_UNLOCK_PIN,
                    purpose = LockScreenDialog.PURPOSE_UNLOCK
                )
                val transaction = supportFragmentManager.beginTransaction()
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                transaction.add(android.R.id.content, lockScreenDialog, TAG_UNLOCK_PIN).commit()
            }
        } else {
             navigate()
        }
    }

    private fun onUnlockPin(pin: String) {
        val pinHash = prefsUtil.pinHash
        val dialog = supportFragmentManager.findFragmentByTag(TAG_UNLOCK_PIN) as? LockScreenDialog
        if (pinHash.isNullOrEmpty() || dialog == null) {
            dialog?.dismiss()
            return
        }
        if (AccessFactory.getInstance(this).validateHash(pin, pinHash)) {
            accessFactory.pin = pin
            // Mark the process as PIN-protected so payload writes can never
            // silently fall back to plaintext.
            AccessFactory.getInstance(this).setPinProtected(true)
            // Previously runBlocking{} here froze the UI thread (and could ANR)
            // right at the point the user is waiting to get in.
            lifecycleScope.launch {
                delay(100)
                dojoUtil.read()
                navigate()
            }
        } else {
            dialog.showError()
        }
    }

    private fun navigate() {

        if (!checkMigration()) {
            feeRepository.init()
            startActivity(Intent(this, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
            this.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
            finish()
        } else {
            startActivityForResult(Intent(this, MigrationActivity::class.java), 20)
            this.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        }
    }


    private fun checkMigration(): Boolean {
        if(dontDeleteFile){
            return false
        }
        val dir: File = applicationContext.getDir("wallet", Context.MODE_PRIVATE)
        val file = File(dir, "sentinel.dat")
        Timber.i(file.exists().toString());
        return file.exists()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 20) {
            dontDeleteFile = true;
            navigate()
        }
    }

    override fun onBackPressed() {
        MaterialAlertDialogBuilder(this)
                .setTitle(resources.getString(R.string.confirm_exit))
                .setMessage(resources.getString(R.string.ask_you_sure_exit))
                .setNegativeButton(resources.getString(R.string.no)) { _, _ ->
                }
                .setPositiveButton(resources.getString(R.string.yes)) { _, _ ->
                    super.onBackPressed()
                }
                .show()
    }

    companion object {
        const val TAG_UNLOCK_PIN = "lock_screen_unlock"
        const val REQUEST_KEY_UNLOCK_PIN = "lock_screen_unlock_result"
    }
}