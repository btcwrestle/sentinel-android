package com.samourai.sentinel.ui.settings

import com.samourai.sentinel.core.DerivedNetwork
import com.samourai.sentinel.core.NetworkClassifier
import com.samourai.sentinel.ui.dojo.PairingNetworkDecision
import com.samourai.sentinel.ui.dojo.pairingNetworkDecision

/**
 * #272: the backup door's network plan, made pure. The
 * PairingNetworkDecision precedent (#250/#273): the decision
 * lives in its own file, unit-testable without Android, and the
 * import screen owns all rendering.
 *
 * Three legs, one plan, all decided post-decrypt pre-write:
 *
 *  1. The keychain gate. A backup whose keys vote unanimously for
 *     the other network, into a wallet whose keychain is
 *     established, without the clear-existing tick, is REFUSED -
 *     AddNewPubKeyBottomSheet.validate()'s cross-network refusal,
 *     pointed at the backup door. With the tick the old keychain
 *     dies inside startImportCollections and the backup's vote
 *     re-establishes - fine by design. A backup with no verdict
 *     (empty, mixed, addresses-only) has nothing to contradict.
 *
 *  2. The flip forecast. The network the app will hold once the
 *     import lands, so the flip can be ANNOUNCED in the completion
 *     snackbar instead of happening silently (the #272 device leg:
 *     a mixed-keychain backup flipping the flag through the prefs
 *     blob with nobody told). Mirrors deriveNetworkFromKeychain's
 *     rule - a unanimous post-import keychain wins, else the pref
 *     that stands (the backup's when prefs are ticked). Predicted,
 *     not measured at completion: the derivation's pref write rides
 *     a LiveData emission that can land after the snackbar runs.
 *
 *  3. The backup-borne Dojo rides #273's decision table, whisper
 *     only: NetworkClassifier.fromUrl over the dojo payload's own
 *     URL. Declared evidence (the directory label) deliberately
 *     stays on the pairing door - the backup's dojoDisplayNetwork
 *     pref is a snapshot that can outlive its pairing, and the
 *     dojo payload's own URL is the self-consistent evidence here.
 */
data class BackupImportNetworkPlan(
    /** Non-null: refuse the whole import, this is the message. */
    val refusal: String?,
    /** The network the app will hold if the import lands as planned. */
    val postImportTestnet: Boolean,
    /** False: skip the dojo leg - #273's mismatch verdicts, no retry. */
    val importDojo: Boolean,
    /**
     * True: the dojo leg must write the flag after a successful
     * pairing - #273's PROCEED_AND_SET_NETWORK arm (empty or mixed
     * keychain: the backup's Dojo is the first network-bearing
     * input, the import tap was the consent).
     */
    val dojoSetsNetwork: Boolean,
    /** The snackbar note when the dojo leg is skipped. */
    val dojoObjection: String?,
    /** The announcement when the flag flip comes from the dojo leg. */
    val dojoSetNote: String?,
    /** The announcement for a keychain- or prefs-driven flip. */
    val flipNote: String?
)

fun backupImportNetworkPlan(
    currentTestnet: Boolean,
    existingVotes: List<DerivedNetwork>,
    backupVotes: List<DerivedNetwork>,
    importCollections: Boolean,
    clearExisting: Boolean,
    importPrefs: Boolean,
    backupPrefsTestnet: Boolean?,
    dojoWhisper: DerivedNetwork?
): BackupImportNetworkPlan {
    val existing = NetworkClassifier.aggregate(existingVotes)
    val backup = NetworkClassifier.aggregate(backupVotes)
    val postImport = NetworkClassifier.aggregate(
        (if (clearExisting) emptyList() else existingVotes) +
            (if (importCollections) backupVotes else emptyList())
    )

    // Leg 1: the keychain gate.
    val refusal = when {
        backup == null -> null
        existing == null -> null
        clearExisting -> null
        backup == existing -> null
        else ->
            "Import stopped: this backup holds ${backup.name()} keys, but this " +
                "wallet already holds ${existing.name()} keys. Tick 'Clear " +
                "existing collections' to replace them, or import a " +
                "${existing.name()} backup."
    }

    // Leg 2: the flip forecast, before the dojo has its say.
    val basePostImportTestnet = when {
        postImport != null -> postImport == DerivedNetwork.TESTNET
        importPrefs -> backupPrefsTestnet ?: currentTestnet
        else -> currentTestnet
    }

    // Leg 3: the backup-borne Dojo through #273's table. The app side
    // is the POST-import world: a unanimous post-import keychain pins
    // the network, and the comparison network is the forecast.
    val dojoDecision = dojoWhisper?.let { whisper ->
        pairingNetworkDecision(
            appEstablished = postImport != null,
            appIsTestnet = basePostImportTestnet,
            whisper = whisper,
            declared = null
        )
    }
    val importDojo = dojoDecision !is PairingNetworkDecision.ConfirmMismatch &&
        dojoDecision !is PairingNetworkDecision.Refuse
    val dojoSetsNetwork =
        dojoDecision is PairingNetworkDecision.ProceedAndSetNetwork
    val postImportTestnet = when (dojoDecision) {
        is PairingNetworkDecision.ProceedAndSetNetwork ->
            dojoDecision.targetIsTestnet
        else -> basePostImportTestnet
    }
    var dojoObjection: String? = null
    if (!importDojo && dojoWhisper != null) {
        dojoObjection =
            " Dojo not imported: the backup's Dojo answers on a " +
                "${dojoWhisper.name()} mount, but this wallet is on " +
                "${basePostImportTestnet.testnetName()}. If that looks wrong, " +
                "a proxy may be confusing the mount check - re-pair it from " +
                "the Network screen."
    }

    val flipNote = if (basePostImportTestnet != currentTestnet) {
        val setter =
            if (postImport != null) "your imported keys" else "the backup's settings"
        " Network switched to ${basePostImportTestnet.testnetName()} - set by $setter."
    } else null

    val dojoSetNote = if (dojoSetsNetwork)
        " Network switched to ${postImportTestnet.testnetName()} - set by the backup's Dojo."
    else null

    return BackupImportNetworkPlan(
        refusal = refusal,
        postImportTestnet = postImportTestnet,
        importDojo = importDojo,
        dojoSetsNetwork = dojoSetsNetwork,
        dojoObjection = dojoObjection,
        dojoSetNote = dojoSetNote,
        flipNote = flipNote
    )
}

private fun DerivedNetwork.name(): String =
    if (this == DerivedNetwork.TESTNET) "Testnet" else "Mainnet"

private fun Boolean.testnetName(): String = if (this) "Testnet" else "Mainnet"
