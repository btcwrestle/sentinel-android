package com.samourai.sentinel.ui.utils

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.samourai.sentinel.R
import timber.log.Timber

/**
 * Launches a Storage Access Framework picker, surviving a device that has
 * none.
 *
 * `ACTION_OPEN_DOCUMENT` and `ACTION_CREATE_DOCUMENT` are not guaranteed to
 * resolve. A stripped ROM, a managed profile with DocumentsUI disabled, or an
 * emulator image built without it leave nothing to handle the intent, and
 * `startActivityForResult` then throws [ActivityNotFoundException] - which, on
 * an unguarded call, takes the whole process down (#292).
 *
 * The throw is caught rather than pre-empted with `resolveActivity()`: under
 * the package-visibility rules that apply from API 30, `resolveActivity()` can
 * return null for an activity that would in fact have handled the intent, so
 * using it as the gate would refuse the picker on devices that have a perfectly
 * good one. Attempting the launch is the only reliable test.
 */
fun Fragment.startDocumentPickerForResult(intent: Intent, requestCode: Int) {
    try {
        startActivityForResult(intent, requestCode)
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "No activity handles ${intent.action}")
        // context is null once the fragment is detached; there is no UI left
        // to tell in that case, and the log above is the record.
        context?.let {
            Toast.makeText(it, R.string.no_file_picker_available, Toast.LENGTH_LONG).show()
        }
    }
}
