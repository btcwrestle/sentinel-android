package com.samourai.sentinel.core

import java.net.URI

/**
 * #42: derivation primitive for network state.
 *
 * Version-byte prefixes are unambiguous for the xpub family:
 * tpub/upub/vpub are testnet keys, xpub/ypub/zpub are mainnet.
 * Everything else is UNKNOWN — never MAINNET, because defaulting
 * unrecognized input to mainnet is exactly the shape of the bug
 * this fixes (a wrong verdict that rejects correct keys).
 *
 * Deliberately dependency-free: core must not reach into ui/util,
 * and this needs no bitcoinj. Validation of the xpub itself stays
 * with FormatsUtil.isValidXpub; this only answers "which network".
 */
enum class DerivedNetwork { MAINNET, TESTNET, UNKNOWN }

object NetworkClassifier {

    /**
     * Case-insensitive to match the legacy isPublicKeyTesnet
     * behavior (payload.lowercase().startsWith). Addresses are not
     * classified here — this primitive is for xpub-family keys only.
     */
    fun fromXpub(xpub: String): DerivedNetwork {
        val key = xpub.lowercase()
        return when {
            key.startsWith("tpub") || key.startsWith("upub") ||
                key.startsWith("vpub") -> DerivedNetwork.TESTNET
            key.startsWith("xpub") || key.startsWith("ypub") ||
                key.startsWith("zpub") -> DerivedNetwork.MAINNET
            else -> DerivedNetwork.UNKNOWN
        }
    }

    /**
     * #42: derivation rule over a wallet's keys. UNKNOWN votes
     * (addresses, unrecognized prefixes) abstain. Empty, mixed, or
     * all-abstaining input yields null — meaning "derive nothing,
     * keep current state". Null-on-mixed is load-bearing: a
     * deliberately mixed wallet must not be silently re-flagged.
     */
    fun aggregate(votes: List<DerivedNetwork>): DerivedNetwork? {
        val cast = votes.filter { it != DerivedNetwork.UNKNOWN }
        if (cast.isEmpty()) return null
        val allTestnet = cast.all { it == DerivedNetwork.TESTNET }
        val allMainnet = cast.all { it == DerivedNetwork.MAINNET }
        return when {
            allTestnet -> DerivedNetwork.TESTNET
            allMainnet -> DerivedNetwork.MAINNET
            else -> null
        }
    }

    /**
     * #250: the pairing payload's URL whisper.
     *
     * Testnet Dojos mount their API under a /test path segment
     * (observed: a testnet node answers at http://…onion/test/v2);
     * mainnet mounts at the root (observed: http://…onion/v2/,
     * trailing slash). The payload carries no network field, so the
     * mount segment is the only in-payload evidence — a whisper,
     * not a vote.
     *
     * Asymmetric on purpose: TESTNET when a path segment equals
     * "test" (case-insensitive, segment equality — never substring:
     * /attest/v2 and /contest/v2 stay silent), null otherwise.
     * Null means "no evidence", never MAINNET — the absence of /test
     * is "probably mainnet", and "probably" is not a verdict. The
     * sheet-side decision table owns what a null whisper means;
     * this reports evidence only.
     *
     * java.net.URI, like the rest of the object: no okhttp, no
     * Android. The parser excludes query and fragment, so ?a=/test
     * and #/test cannot whisper; unparseable or null input is no
     * evidence at all.
     */
    fun fromUrl(url: String?): DerivedNetwork? {
        val path = url?.let { runCatching { URI(it.trim()).path }.getOrNull() } ?: return null
        val segments = path.split('/').filter { it.isNotBlank() }
        return if (segments.any { it.equals("test", ignoreCase = true) })
            DerivedNetwork.TESTNET
        else null
    }
}
