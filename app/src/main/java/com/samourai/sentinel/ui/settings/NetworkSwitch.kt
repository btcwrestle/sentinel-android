package com.samourai.sentinel.ui.settings

/**
 * #208: the pure decision behind the Network page's Bitcoin Network row.
 *
 * The app's truth model (#42) is that the network derives from the
 * keychain: SentinelState re-derives it from the xpubs on every launch
 * and overwrites the pref when they disagree. A manual switch therefore
 * only sticks when that derivation abstains - an empty or mixed wallet.
 * On an established (unanimous) wallet the row must not offer a switch
 * that the next launch would silently revert.
 *
 * Deliberately dependency-free, the KeyRegistrationBurst precedent: the
 * decision is unit-testable without Android, and the activity owns all
 * rendering.
 */
object NetworkSwitch {

    sealed class Row {

        /**
         * The wallet casts no unanimous network vote, so a manual switch
         * sticks. [targetIsTestnet] is the network a confirmed switch
         * lands on - always the opposite of the current one.
         */
        data class Switchable(val targetIsTestnet: Boolean) : Row()

        /** The keychain votes unanimously; it, not a button, owns the network. */
        object KeyPinned : Row()
    }

    fun rowState(currentIsTestnet: Boolean, networkEstablished: Boolean): Row =
        if (networkEstablished) Row.KeyPinned
        else Row.Switchable(targetIsTestnet = !currentIsTestnet)
}
