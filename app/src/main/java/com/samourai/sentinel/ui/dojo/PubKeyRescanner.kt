package com.samourai.sentinel.ui.dojo

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.TransactionsRepository
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

/**
 * "Rescan Xpub" - asks the connected Dojo to re-run its restore import for a
 * set of public keys.
 *
 * Shared by every entry point (Settings, the collection overflow menu, and the
 * per-key options dialog) so the privacy warning and the wording are identical
 * wherever the user starts from.
 *
 * The underlying call is [ApiService.rescanPubKey], which both registers a key
 * the Dojo has never tracked and rescans one it already knows.
 */
object PubKeyRescanner {

    private val apiService: ApiService by inject(ApiService::class.java)
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private val transactionsRepository: TransactionsRepository by inject(TransactionsRepository::class.java)

    /**
     * Shows the privacy warning, then rescans [keys] if the user continues.
     *
     * [collectionIds] are refreshed from the server afterwards so a recovered
     * balance appears without the user having to pull to refresh.
     */
    fun confirmAndRescan(
        activity: AppCompatActivity,
        keys: List<PubKeyModel>,
        collectionIds: List<String> = emptyList()
    ) {
        if (!prefsUtil.isAPIEndpointEnabled()) {
            info(activity, activity.getString(R.string.rescan_xpub_no_dojo))
            return
        }
        if (keys.isEmpty()) {
            info(activity, activity.getString(R.string.rescan_xpub_no_keys))
            return
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.rescan_xpub_title))
            .setMessage(activity.getString(R.string.rescan_xpub_warning_message))
            .setPositiveButton(activity.getString(R.string.rescan_xpub_continue)) { _, _ ->
                rescan(activity, keys, collectionIds)
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun rescan(
        activity: AppCompatActivity,
        keys: List<PubKeyModel>,
        collectionIds: List<String>
    ) {
        val progress = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.rescan_xpub_title))
            .setMessage(progressText(activity, 1, keys.size))
            .setCancelable(false)
            .show()

        // apiScope, not the activity scope: the Dojo keeps scanning regardless,
        // so cancelling on rotation would only discard the result.
        apiScope.launch {
            var succeeded = 0
            var stillRunning = 0
            val failures = mutableListOf<String>()

            keys.forEachIndexed { index, key ->
                setProgress(activity, progress, progressText(activity, index + 1, keys.size))
                try {
                    val response = apiService.rescanPubKey(key.pubKey, key.type)
                    val body = response.body?.string()
                    if (response.isSuccessful && body != null &&
                        JSONObject(body).optString("status") == "ok"
                    ) {
                        succeeded++
                    } else {
                        failures += "${key.label}: ${dojoErrorOf(response.code, body)}"
                    }
                } catch (e: java.io.InterruptedIOException) {
                    // Covers OkHttp's callTimeout and socket timeouts alike. The
                    // Dojo carries on after we stop waiting, so this is "check
                    // back later", not a failure.
                    stillRunning++
                } catch (e: Exception) {
                    Timber.e(e, "Rescan failed for ${key.label}")
                    failures += "${key.label}: ${e.message ?: e.toString()}"
                }
            }

            if (succeeded > 0) {
                collectionIds.forEach { id ->
                    try {
                        transactionsRepository.fetchFromServer(id)
                    } catch (e: Exception) {
                        Timber.e(e, "Could not refresh collection $id after rescan")
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                progress.dismiss()
                MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(R.string.rescan_xpub_title))
                    .setMessage(summary(activity, keys.size, succeeded, stillRunning, failures))
                    .setPositiveButton(activity.getString(R.string.ok)) { d, _ -> d.dismiss() }
                    .show()
            }
        }
    }

    private suspend fun setProgress(
        activity: AppCompatActivity,
        dialog: AlertDialog,
        text: String
    ) = withContext(Dispatchers.Main) {
        if (!activity.isFinishing && !activity.isDestroyed) {
            dialog.setMessage(text)
        }
    }

    private fun progressText(activity: AppCompatActivity, current: Int, total: Int): String =
        if (total == 1)
            activity.getString(R.string.rescan_xpub_progress)
        else
            activity.getString(R.string.rescan_xpub_progress_multi, current, total)

    private fun summary(
        activity: AppCompatActivity,
        total: Int,
        succeeded: Int,
        stillRunning: Int,
        failures: List<String>
    ): String {
        val lines = mutableListOf<String>()
        if (succeeded > 0) {
            lines += if (succeeded == total)
                activity.getString(R.string.rescan_xpub_done)
            else
                activity.getString(R.string.rescan_xpub_done_partial, succeeded, total)
        }
        if (stillRunning > 0) {
            lines += activity.getString(R.string.rescan_xpub_still_running)
        }
        if (failures.isNotEmpty()) {
            lines += activity.getString(R.string.rescan_xpub_failed, failures.joinToString("\n"))
        }
        return lines.joinToString("\n\n")
    }

    /** Dojo reports failures as `{"status":"error","error":"..."}`. */
    private fun dojoErrorOf(code: Int, body: String?): String {
        if (body.isNullOrBlank()) return "HTTP $code"
        return try {
            JSONObject(body).optString("error").ifBlank { "HTTP $code" }
        } catch (e: Exception) {
            "HTTP $code"
        }
    }

    private fun info(activity: AppCompatActivity, message: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.rescan_xpub_title))
            .setMessage(message)
            .setPositiveButton(activity.getString(R.string.ok)) { d, _ -> d.dismiss() }
            .show()
    }
}
