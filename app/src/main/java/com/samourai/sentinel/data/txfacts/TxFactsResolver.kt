package com.samourai.sentinel.data.txfacts

import java.net.URI

/**
 * #119 Route A: destination cascade for the live tx-facts fetch.
 * Pure, JVM-testable - no Android, no network.
 *
 * Rungs, resolved at tap time:
 *  1. SELF - the paired Dojo's pairing payload declared a sibling
 *     explorer (prefsUtil.dojoExplorerUrl, captured at pairing). Same
 *     trust class as the Dojo itself: no advisory. Capability is NOT
 *     assumed - a non-esplora-shaped response surfaces as the
 *     user-directed retry/fallback dialog (unreachable vs incompatible
 *     stated honestly). No silent auto-fallback.
 *  2. PUBLIC - mempool.space clearnet base over the app's Tor SOCKS
 *     circuit. The official onion serves the PAGE, not the API (#119
 *     pinned) - the facts URL is always clearnet-base. Advisory-gated
 *     in #114 wording before any request.
 *
 * Disclosure class is host-derived, NEVER type-string-derived: a
 * community payload labels the official mempool onion
 * "btc_rpc_explorer". A declared URL that IS a public instance is
 * treated as public - the advisory is owed regardless of who declared
 * it, because the destination sees the txid either way. A garbage URL
 * also classifies PUBLIC (safe default: advisory + facts still work).
 *
 * Testnet: PUBLIC remaps to the /testnet API base. SELF is used as
 * declared - a testnet Dojo declares a testnet explorer.
 */
object TxFactsResolver {

    const val MEMPOOL_CLEARNET_BASE = "https://mempool.space"
    const val MEMPOOL_TESTNET_BASE = "https://mempool.space/testnet"

    // Official onion, pinned in #119 (page-only; kept here so a Dojo
    // declaring it classifies PUBLIC and gets the advisory).
    const val MEMPOOL_ONION_HOST =
        "mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion"

    enum class Destination { SELF, PUBLIC }

    data class Resolved(val destination: Destination, val url: String)

    fun classify(explorerUrl: String?): Destination =
        if (explorerUrl.isNullOrBlank() ||
            isPublicInstance(explorerUrl) ||
            !hasUsableHost(explorerUrl)
        ) {
            Destination.PUBLIC
        } else {
            Destination.SELF
        }

    fun isPublicInstance(url: String): Boolean = try {
        val host = URI(url.trim()).host
        host == "mempool.space" || host == MEMPOOL_ONION_HOST
    } catch (e: Exception) {
        false
    }

    /**
     * Gate defect fix (first gate run of this branch, red at
     * TxFactsResolverTest "garbage declared url is public not crash"):
     * classify() treated every non-public URL as SELF, so garbage
     * ("::::") reached the SELF rung - java.net.URI never surfaced
     * the problem to isPublicInstance, because a throw lands in its
     * catch (returns false) and a lenient parse yields a null host
     * that equals nothing. A declared URL with no usable host is
     * unusable as a destination: PUBLIC per the documented safe
     * default. Also pins schemeless declarations ("host.onion"
     * parses as a path, no host) to PUBLIC.
     */
    private fun hasUsableHost(url: String): Boolean = try {
        !URI(url.trim()).host.isNullOrEmpty()
    } catch (e: Exception) {
        false
    }

    /** /api/tx/{txid} against the resolved base. Self bases are used
     *  as-is (directory payloads carry bare onion roots). */
    fun factsUrl(txid: String, selfExplorerUrl: String?, testnet: Boolean): Resolved {
        return when (classify(selfExplorerUrl)) {
            Destination.SELF -> Resolved(
                Destination.SELF,
                selfExplorerUrl!!.trim().trimEnd('/') + "/api/tx/" + txid,
            )
            Destination.PUBLIC -> Resolved(
                Destination.PUBLIC,
                (if (testnet) MEMPOOL_TESTNET_BASE else MEMPOOL_CLEARNET_BASE) +
                    "/api/tx/" + txid,
            )
        }
    }
}
