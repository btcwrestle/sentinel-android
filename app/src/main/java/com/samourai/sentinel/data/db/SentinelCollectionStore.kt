package com.samourai.sentinel.data.db

import android.content.Context
import org.koin.java.KoinJavaComponent.inject
import java.io.File


class SentinelCollectionStore {

    private val context: Context by inject(Context::class.java)

    private val storeLocation = "${this.context.filesDir.path}/app_wallet"

    fun getCollectionStore(): PayloadRecord {
        return PayloadRecord(storeLocation, "collections.payload")
    }

    fun getDojoStore(): PayloadRecord {
        return PayloadRecord(storeLocation, "dojo.payload")
    }

    fun getUtxoMetaData(): PayloadRecord {
        return PayloadRecord(storeLocation, "utxo.meta.payload")
    }

    fun getFee(): PayloadRecord {
        return PayloadRecord(storeLocation, "fee.payload")
    }

}

/** Every payload file whose bytes follow the PIN / data-key format. */
fun appWalletPayloads(filesDir: File): List<PayloadRecord> {
    val dir = "${filesDir.path}/app_wallet"
    return listOf(
        PayloadRecord(dir, "collections.payload"),
        PayloadRecord(dir, "dojo.payload"),
        PayloadRecord(dir, "utxo.meta.payload"),
        PayloadRecord(dir, "fee.payload")
    )
}