package com.samourai.sentinel.ui.dojo

import com.google.gson.annotations.SerializedName


data class DojoPairing(
    @SerializedName("pairing")
    val pairing: Pairing? = Pairing(),
    // #119 Route A / #122: Dojo v2 payloads may declare a sibling block
    // explorer (observed: most dojobay nodes). Optional - absent means the
    // live-facts rung skips to public mempool.space over Tor. `type` is
    // display/label-only: a community payload labels the official mempool
    // onion "btc_rpc_explorer". Disclosure class is decided by
    // TxFactsResolver.classify(host), never by this string.
    @SerializedName("explorer")
    val explorer: Explorer? = null
) {
    /**
     * #258/#96: toString is the log surface; Gson is not. The generated
     * toString printed the full pairing (apikey, onion URL) - any stray
     * log of this object leaked both. Serialization is untouched: Gson
     * reads fields via reflection, so exportDojoPayload()/toJSON() still
     * emits the real payload (pinned by DojoPairingRedactionTest).
     */
    override fun toString(): String =
        "DojoPairing(pairing=$pairing, explorer=$explorer)"
}
data class Pairing(
        @SerializedName("apikey")
        val apikey: String? = "",
        @SerializedName("type")
        val type: String? = "",
        @SerializedName("url")
        val url: String? = "",
        @SerializedName("version")
        val version: String? = ""
) {
    /**
     * #258: the mask. apikey grants access to the node and url is the
     * onion endpoint - neither ever renders via toString. type/version
     * are display metadata and stay visible for debugging.
     */
    override fun toString(): String =
        "Pairing(type=$type, version=$version, apikey=REDACTED, url=REDACTED)"
}
data class Explorer(
        @SerializedName("type")
        val type: String? = "",
        @SerializedName("url")
        val url: String? = ""
) {
    /** #258: explorer URLs are onions too - masked like the pairing. */
    override fun toString(): String =
        "Explorer(type=$type, url=REDACTED)"
}
