package com.samourai.sentinel.util

import timber.log.Timber.DebugTree

/**
 * Redaction at the logging boundary (#264/#96).
 *
 * The onion-host class never appears in app-authored message text -
 * the host rides inside exception messages (UnknownHostException:
 * Unable to resolve host "<onion>"), and Timber 4.7.1's private
 * Tree.prepareLog appends the throwable's full stack to the message
 * before the abstract log() runs - deliberately refusing
 * Log.getStackTraceString because it "hides UnknownHostException".
 * So this class can only be masked at a Tree seam, never at the call
 * sites and never inside Timber's plumbing.
 *
 * Over-redaction fails safe: the rules are narrow enough to keep
 * ordinary log text readable, and anything they do catch is not
 * worth printing in a debug logcat that can leave the device.
 */
object LogRedaction {

    /**
     * Onion service hostnames: version-2 hosts are 16 chars,
     * version-3 are 56, both base32 (a-z, 2-7), rendered lowercase
     * by UnknownHostException. The 16-char floor keeps ordinary
     * English words before ".onion" untouched.
     */
    private val ONION_HOST = Regex("[a-z2-7]{16,56}\\.onion")

    /**
     * 64-char lowercase hex: the txid shape. #266 closed the
     * app-authored arms; this catches the exception-message-
     * embeds-txid class those pins deliberately tolerated. Word
     * boundaries demand the full 64 - a 63- or 65-char run is
     * left alone.
     */
    private val HEX_64 = Regex("\\b[0-9a-f]{64}\\b")

    /** Applies every rule, in order, to one log line. */
    fun redact(message: String): String =
        message
            .replace(ONION_HOST, "ONION_REDACTED")
            .replace(HEX_64, "HEX_REDACTED")
}

/**
 * The debug-build Tree: DebugTree with the seam masked.
 * Timber 4.7.1's DebugTree.log(int, String?, String, Throwable?)
 * is open (not final), so this override is the one legal
 * subclassing point; tag inference is untouched because
 * prepareLog -> getTag() runs before log() - the calling-class
 * stack is exactly what it was before this class existed.
 */
class MaskingDebugTree : DebugTree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        super.log(priority, tag, LogRedaction.redact(message), t)
    }
}
