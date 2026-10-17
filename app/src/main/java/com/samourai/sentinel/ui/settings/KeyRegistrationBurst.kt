package com.samourai.sentinel.ui.settings

/**
 * #158: burst state + vocabulary for the dojo derivation-path
 * registration burst (NetworkActivity.importAllXpubs).
 *
 * The holder outlives the screen: the burst runs on apiScope and survives
 * the activity (#85 containment semantics, untouched), so a re-entered
 * NetworkActivity renders from here instead of a dead activity's last view
 * write.
 *
 * Single-flight (#158 leg 3 / #159): one live burst at a time. The pairing
 * sheet can fire its onDismiss listener more than once per pairing; a
 * second launch while one is live is skipped, never interleaved onto the
 * same row.
 *
 * Every round reports (#158 leg 2 + the maintainer's every-time condition):
 * completions land in a finally, and the gate re-arms on completion - each
 * registration round, retries included, states its own delta so "did it
 * finish, was a derivation path left behind?" is never left open. A
 * partial round's residue stays on the row until a full pass or a newer
 * round displaces it.
 *
 * Pure Kotlin - no Android imports: the unit seam for the vocabulary
 * (#158 leg 1's test).
 */
object KeyRegistrationBurst {

    @Volatile
    var inFlight: Boolean = false
        private set

    @Volatile
    var done: Int = 0
        private set

    @Volatile
    var total: Int = 0
        private set

    @Volatile
    private var registered: Int = 0

    @Volatile
    private var failed: Int = 0

    @Volatile
    private var hasResidue: Boolean = false

    /**
     * Single-flight gate. True = the caller owns the burst and MUST run it
     * to a completion call (complete/markFinished); false = a burst is
     * already live, skip the launch entirely.
     */
    @Synchronized
    fun markStarted(): Boolean {
        if (inFlight) {
            return false
        }
        inFlight = true
        return true
    }

    /** Items known; zeroes the counters for the round about to run. */
    fun begin(totalCount: Int) {
        total = totalCount
        done = 0
        registered = 0
        failed = 0
    }

    /** The attempt counter advances before each item's call (option B). */
    fun onItemStarted(attemptsDone: Int) {
        done = attemptsDone
    }

    /**
     * Round finished: latch the outcome - partial rounds keep their
     * residue, full passes clear it - and re-arm the gate so the NEXT
     * retry round can run and report again. Every round fires; none is
     * swallowed.
     */
    @Synchronized
    fun complete(registeredCount: Int, failedCount: Int, totalCount: Int) {
        registered = registeredCount
        failed = failedCount
        total = totalCount
        hasResidue = failedCount > 0 || registeredCount < totalCount
        inFlight = false
    }

    /** Empty-wallet launch: silent reset, no residue (the #98 silence leg). */
    @Synchronized
    fun markFinished() {
        inFlight = false
        done = 0
        total = 0
        registered = 0
        failed = 0
        hasResidue = false
    }

    /**
     * The row's text from burst state: in-flight renders the re-entry
     * marker, a partial last round renders its residue, otherwise the
     * caller's rest-state label.
     */
    fun statusText(restLabel: String): String = when {
        inFlight -> progressText(done, total)
        hasResidue -> residueText(registered, total)
        else -> restLabel
    }

    /** Attempt-counted progress - failures advance it too (option B). */
    fun progressText(attemptsDone: Int, totalCount: Int): String =
        "Registering derivation paths with Dojo\u2026 $attemptsDone of $totalCount"

    /**
     * Completion vocabulary, delta stated out loud (option B): the partial
     * note names the failed count when there is one; the erase-skip shape
     * (registered < total, failed == 0) gets the retry note without a
     * "0 failed" wart.
     */
    fun resultText(registeredCount: Int, failedCount: Int, totalCount: Int): String {
        val partial = failedCount > 0 || registeredCount < totalCount
        return if (partial) {
            val failedNote = if (failedCount > 0) ", $failedCount failed" else ""
            "Dojo connected ($registeredCount of $totalCount derivation paths " +
                "registered$failedNote - retry pairing to re-register)"
        } else {
            "Dojo connected ($registeredCount derivation paths registered)"
        }
    }

    fun residueText(registeredCount: Int, totalCount: Int): String =
        "Enabled ($registeredCount of $totalCount registered)"
}
