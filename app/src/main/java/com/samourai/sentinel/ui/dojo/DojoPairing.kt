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
)
data class Pairing(
        @SerializedName("apikey")
        val apikey: String? = "",
        @SerializedName("type")
        val type: String? = "",
        @SerializedName("url")
        val url: String? = "",
        @SerializedName("version")
        val version: String? = ""
)
data class Explorer(
        @SerializedName("type")
        val type: String? = "",
        @SerializedName("url")
        val url: String? = ""
)
