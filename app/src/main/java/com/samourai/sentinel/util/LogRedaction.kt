package com.samourai.sentinel.util

import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber
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

    /**
     * Extended public keys as they ride URLs and payloads (#96's
     * acceptance condition: txids/addresses/xpubs masked). The
     * 90-char floor keeps anything shorter than a real key
     * (111-ish chars total) untouched; prefixes cover mainnet,
     * testnet, and the script-family variants.
     */
    private val XPUB = Regex("\\b(xpub|zpub|ypub|tpub|vpub)[1-9A-HJ-NP-Za-km-z]{90,}")

    /**
     * The Dojo access-token query parameter (Arc B). ApiService's
     * at-interceptor and TokenAuthenticator's retry both
     * addQueryParameter("at", ...), so every wire line carries
     * the token. The word boundary keeps longer parameter names
     * (format=, latitude=) untouched; the value runs to the
     * next & or whitespace.
     */
    private val AT_TOKEN = Regex("\\bat=[^&\\s\"']+")

    /** Applies every rule, in order, to one log line. */
    fun redact(message: String): String =
        message
            .replace(ONION_HOST, "ONION_REDACTED")
            .replace(HEX_64, "HEX_REDACTED")
            .replace(XPUB, "XPUB_REDACTED")
            .replace(AT_TOKEN, "at=REDACTED")
}

/**
 * The HTTP wire logger (Arc B). HttpLoggingInterceptor's
 * DEFAULT logger writes through android.util.Log directly - it
 * never crosses Timber, so the MaskingDebugTree seam cannot see
 * it. This logger rides Timber instead (explicit tag - wire
 * lines are not caller-attributed) and redacts explicitly as
 * well, so masking never depends on which trees happen to be
 * planted. The install sites choose Level.BASIC - request and
 * response lines only, never headers or bodies - and the
 * secrets a BASIC line can carry (onion host, at token, xpub
 * in wallet URLs) are the rules above.
 */
class MaskedHttpLogger : HttpLoggingInterceptor.Logger {
    override fun log(message: String) {
        Timber.tag("OkHttp").d(LogRedaction.redact(message))
    }
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
