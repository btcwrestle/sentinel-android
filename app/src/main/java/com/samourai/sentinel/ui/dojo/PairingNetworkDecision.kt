package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.core.DerivedNetwork

/**
 * #250: the pairing-path network decision, made pure. The
 * NetworkSwitch precedent (#208): the decision lives in its
 * own file, unit-testable without Android, and the sheet owns
 * all rendering.
 *
 * Evidence precedence: a directory declaration (contract
 * data, CommunityDojoNode.network, riding the #256 display
 * snapshot) beats the URL whisper (convention,
 * NetworkClassifier.fromUrl). Neither present means NO
 * evidence: the pairing payload carries no network field
 * (read-verified, #250 census; both observed fixtures agree
 * - testnet answers at .../test/v2, mainnet at .../v2/).
 *
 * The table (#250/#272 agreement):
 *   no evidence                -> PROCEED (the reverse-
 *                                direction gap is
 *                                documented, not guessed)
 *   evidence matches the app    -> PROCEED
 *   empty keychain + mismatch   -> PROCEED_AND_SET_NETWORK
 *   (not established)             - the first input
 *                                establishes the network
 *                                (#42's own rule), loudly:
 *                                the Pair tap is the consent
 *   established + declared      -> REFUSE - contract data
 *     mismatch                    cannot be overridden by a
 *                                tap; mirrors the pubkey
 *                                door's guard
 *   established + whispered     -> CONFIRM_MISMATCH -
 *     mismatch                    convention is not
 *                                contract: a weird proxy
 *                                could false-positive, so
 *                                the warning is loud but
 *                                Pair stays available
 */
sealed class PairingNetworkDecision {
    object Proceed : PairingNetworkDecision()
    data class ProceedAndSetNetwork(val targetIsTestnet: Boolean) :
        PairingNetworkDecision()
    object ConfirmMismatch : PairingNetworkDecision()
    object Refuse : PairingNetworkDecision()
}

fun pairingNetworkDecision(
    appEstablished: Boolean,
    appIsTestnet: Boolean,
    whisper: DerivedNetwork?,
    declared: DerivedNetwork?,
): PairingNetworkDecision {
    val evidence = declared ?: whisper ?: return PairingNetworkDecision.Proceed
    val evidenceIsTestnet = evidence == DerivedNetwork.TESTNET
    return when {
        !appEstablished ->
            if (evidenceIsTestnet == appIsTestnet) PairingNetworkDecision.Proceed
            else PairingNetworkDecision.ProceedAndSetNetwork(
                targetIsTestnet = evidenceIsTestnet
            )
        evidenceIsTestnet == appIsTestnet -> PairingNetworkDecision.Proceed
        declared != null -> PairingNetworkDecision.Refuse
        else -> PairingNetworkDecision.ConfirmMismatch
    }
}

/**
 * #250: the directory's network label, normalized. "mainnet" /
 * "testnet" (observed fixture values, case-insensitive)
 * declare; anything else - signet, regtest, garbage, blank,
 * null - is NO declaration, never a guess. Fail-closed, the
 * fromXpub UNKNOWN discipline: a wrong verdict rejects
 * correct pairings.
 */
fun normalizeDeclaredNetwork(raw: String?): DerivedNetwork? {
    val value = raw?.trim()?.lowercase() ?: return null
    return when (value) {
        "mainnet" -> DerivedNetwork.MAINNET
        "testnet" -> DerivedNetwork.TESTNET
        else -> null
    }
}
