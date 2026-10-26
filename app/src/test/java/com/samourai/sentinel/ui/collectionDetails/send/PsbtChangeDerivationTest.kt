package com.samourai.sentinel.ui.collectionDetails.send

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.hd.HD_Account
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.send.FeeUtil
import com.samourai.sentinel.send.SuggestedFee
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.wallet.psbt.PSBT
import com.samourai.wallet.segwit.SegwitAddress
import com.samourai.wallet.segwit.bech32.Bech32UtilGeneric
import com.samourai.wallet.util.XPUB
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDKeyDerivation
import org.bouncycastle.util.encoders.Hex
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.math.BigInteger

/**
 * #257: the PSBT change derivation must sit on the output whose script is
 * the wallet change path. [com.samourai.sentinel.send.SendFactory] sorts
 * outputs with the sweep BIP69 comparator, so change can land at index 0
 * or 1. These tests build the PSBT through [TransactionComposer.compose]
 * and read the serialized output maps the way a signer does.
 *
 * A plain [Application] is used so this class does not boot Tor or the
 * production Koin graph. Koin is started only when nothing else has, and
 * stopped afterwards so later Robolectric tests can start their own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PsbtChangeDerivationTest {

    private lateinit var wallet: Wallet

    @Before
    fun setUp() {
        ensureRuntime()
        val suggested = SuggestedFee()
        suggested.defaultPerKB = BigInteger.valueOf(1000L)
        FeeUtil.getInstance().suggestedFee = suggested
        wallet = Wallet(SentinelState.getNetworkParam()!!)
    }

    @Test
    fun changeSortsToIndex0_onlyThatOutputCarriesChangeDerivation() {
        // Payment is much larger than change, so BIP69 puts change first.
        val psbt = compose(
            destination = wallet.externalAddress,
            amountSats = 9_900_000L,
            utxos = listOf(wallet.utxo(value = 10_000_000L, index = 0, txid = txid(1)))
        )
        val decoded = decode(psbt)
        assertEquals(2, decoded.tx.outputs.size)
        assertChangeOnlyAt(decoded, expectedIndex = 0)
    }

    @Test
    fun changeSortsToIndex1_paymentOutputHasNoDerivation() {
        // Payment is the smaller output, so BIP69 puts it at index 0.
        // This is the reported failure: the destination was labeled change.
        val psbt = compose(
            destination = wallet.externalAddress,
            amountSats = 20_000L,
            utxos = listOf(wallet.utxo(value = 10_000_000L, index = 0, txid = txid(2)))
        )
        val decoded = decode(psbt)
        assertEquals(2, decoded.tx.outputs.size)
        assertChangeOnlyAt(decoded, expectedIndex = 1)
    }

    @Test
    fun spendAllHasNoChangeDerivationAndOneOutputMap() {
        val utxo = wallet.utxo(value = 10_000_000L, index = 0, txid = txid(3))
        val psbt = compose(
            destination = wallet.externalAddress,
            amountSats = utxo.value!!,
            utxos = listOf(utxo)
        )
        val decoded = decode(psbt)
        assertEquals(1, decoded.tx.outputs.size)
        assertEquals(1, decoded.outputMaps.size)
        assertFalse(decoded.tx.getOutput(0).scriptBytes.contentEquals(wallet.changeScript))
        assertArrayEquals(
            scriptFor(wallet.externalAddress),
            decoded.tx.getOutput(0).scriptBytes
        )
        assertTrue(
            "spend-all payment must not carry change derivation",
            decoded.outputMaps[0].isEmpty()
        )
        assertNoChangeDerivation(decoded)
    }

    @Test
    fun sweepToReceiveAddressHasNoChangeDerivation() {
        // Sweep spends every selected coin to one address and has no change
        // output. The destination here is the wallet's own receive path,
        // which a signer must still not display as change.
        val destination = wallet.receiveAddress(2)
        val psbt = compose(
            destination = destination,
            amountSats = 10_000_000L,
            utxos = listOf(
                wallet.utxo(value = 6_000_000L, index = 0, txid = txid(4)),
                wallet.utxo(value = 4_000_000L, index = 1, txid = txid(5))
            )
        )
        val decoded = decode(psbt)
        assertEquals(1, decoded.tx.outputs.size)
        assertEquals(1, decoded.outputMaps.size)
        assertArrayEquals(scriptFor(destination), decoded.tx.getOutput(0).scriptBytes)
        assertFalse(decoded.tx.getOutput(0).scriptBytes.contentEquals(wallet.changeScript))
        assertTrue(
            "sweep payment must not carry change derivation",
            decoded.outputMaps[0].isEmpty()
        )
        assertNoChangeDerivation(decoded)
    }

    private fun assertChangeOnlyAt(decoded: DecodedPsbt, expectedIndex: Int) {
        val changeAt = decoded.tx.outputs.indexOfFirst {
            it.scriptBytes.contentEquals(wallet.changeScript)
        }
        assertEquals(
            "change script was not at BIP69 index $expectedIndex (values=" +
                decoded.tx.outputs.joinToString { it.value.toString() } + ")",
            expectedIndex,
            changeAt
        )
        assertEquals(decoded.tx.outputs.size, decoded.outputMaps.size)
        decoded.outputMaps.forEachIndexed { index, entries ->
            if (index == changeAt) {
                assertEquals("change output should have one derivation record", 1, entries.size)
                val entry = entries[0]
                assertEquals(
                    PSBT.PSBT_OUT_BIP32_DERIVATION.toInt() and 0xff,
                    entry.keyType
                )
                assertArrayEquals(wallet.changePubKey, entry.keyData)
                assertArrayEquals(Hex.decode(FINGERPRINT), entry.value.copyOfRange(0, 4))
                assertEquals(CHANGE_PATH, PSBT.readBIP32Derivation(entry.value))
            } else {
                assertTrue(
                    "payment output $index must not carry derivation metadata",
                    entries.isEmpty()
                )
            }
        }
    }

    private fun assertNoChangeDerivation(decoded: DecodedPsbt) {
        val paths = decoded.outputMaps.flatten().map { PSBT.readBIP32Derivation(it.value) }
        assertTrue("unexpected output derivation $paths", paths.isEmpty())
    }

    private fun compose(destination: String, amountSats: Long, utxos: List<Utxo>): ByteArray {
        val composer = TransactionComposer()
        composer.setPubKey(listOf(wallet.pub))
        return runBlocking {
            withTimeout(20_000) {
                val fee = async { composer.getMinerFee().receive() }
                val ok = composer.compose(
                    inputUtxos = ArrayList(utxos),
                    inputAddress = destination,
                    inputFee = 1000L,
                    inputAmount = amountSats.toDouble()
                )
                assertTrue(ok)
                fee.await()
                composer.getPSBT()?.toBytes() ?: error("composer returned no PSBT bytes")
            }
        }
    }

    private fun scriptFor(address: String): ByteArray {
        return Bech32UtilGeneric.getInstance()
            .computeScriptPubKey(address, SentinelState.getNetworkParam())
    }

    private fun decode(psbt: ByteArray): DecodedPsbt {
        assertTrue("psbt shorter than a header", psbt.size > 5)
        assertArrayEquals(
            "psbt magic",
            byteArrayOf(0x70, 0x73, 0x62, 0x74),
            psbt.copyOfRange(0, 4)
        )
        val cursor = Cursor(psbt)
        cursor.i = 4
        assertEquals(0xff, cursor.u8())
        val global = cursor.readMap()
        val rawTx = global.first { it.keyType == PSBT.PSBT_GLOBAL_UNSIGNED_TX.toInt() and 0xff }.value
        val tx = Transaction(SentinelState.getNetworkParam(), rawTx)
        repeat(tx.inputs.size) { cursor.readMap() }
        val outputMaps = List(tx.outputs.size) { cursor.readMap() }
        assertEquals(
            "PSBT output-map count does not match transaction outputs " +
                "(${psbt.size - cursor.i} trailing bytes)",
            psbt.size,
            cursor.i
        )
        return DecodedPsbt(tx, outputMaps)
    }

    private class Wallet(val params: NetworkParameters) {
        val zpub: String
        val changePubKey: ByteArray
        val changeScript: ByteArray
        val externalAddress: String
        val pub: PubKeyModel

        init {
            val seed = ByteArray(32) { (it + 1).toByte() }
            val master = HDKeyDerivation.createMasterPrivateKey(seed)
            val accountKey = HDKeyDerivation.deriveChildKey(
                HDKeyDerivation.deriveChildKey(
                    HDKeyDerivation.deriveChildKey(master, ChildNumber(84, true)),
                    ChildNumber(0, true)
                ),
                ChildNumber(0, true)
            )
            zpub = XPUB.makeXPUB(
                XPUB.MAGIC_ZPUB,
                accountKey.depth.toByte(),
                accountKey.parentFingerprint,
                accountKey.childNumber.i(),
                accountKey.chainCode,
                accountKey.pubKey
            )
            val account = HD_Account(params, zpub, "", 0)
            val changeKey = account.getChain(1).getAddressAt(CHANGE_INDEX).ecKey
            changePubKey = changeKey.pubKey
            val changeAddress = SegwitAddress(changePubKey, params).bech32AsString
            changeScript = Bech32UtilGeneric.getInstance().computeScriptPubKey(changeAddress, params)
            externalAddress = SegwitAddress(
                ECKey.fromPrivate(ByteArray(32) { 0x42 }).pubKey,
                params
            ).bech32AsString
            pub = PubKeyModel(
                pubKey = zpub,
                label = "Deposit",
                type = AddressTypes.BIP84,
                change_index = CHANGE_INDEX,
                fingerPrint = FINGERPRINT
            )
        }

        fun receiveAddress(index: Int): String {
            val account = HD_Account(params, zpub, "", 0)
            val key = account.getChain(0).getAddressAt(index).ecKey
            return SegwitAddress(key.pubKey, params).bech32AsString
        }

        fun utxo(value: Long, index: Int, txid: String): Utxo {
            val account = HD_Account(params, zpub, "", 0)
            val key = account.getChain(0).getAddressAt(index).ecKey
            val address = SegwitAddress(key.pubKey, params).bech32AsString
            val script = Bech32UtilGeneric.getInstance().computeScriptPubKey(address, params)
            return Utxo(
                idx = txid,
                addr = address,
                confirmations = 1,
                script = Hex.toHexString(script),
                txHash = txid,
                txOutputN = 0,
                value = value,
                path = "m/0/$index",
                pubKey = zpub
            )
        }
    }

    private class DecodedPsbt(
        val tx: Transaction,
        val outputMaps: List<List<Kv>>
    )

    private class Kv(val keyType: Int, val keyData: ByteArray, val value: ByteArray)

    private class Cursor(val bytes: ByteArray) {
        var i: Int = 0

        fun u8(): Int {
            if (i >= bytes.size) error("truncated PSBT at $i")
            return bytes[i++].toInt() and 0xff
        }

        fun readCompact(): Int {
            val first = u8()
            return when (first) {
                0xfd -> u8() or (u8() shl 8)
                0xfe -> u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)
                0xff -> error("compact size too large")
                else -> first
            }
        }

        fun readBytes(n: Int): ByteArray {
            if (n < 0 || i + n > bytes.size) error("truncated PSBT reading $n at $i")
            val out = bytes.copyOfRange(i, i + n)
            i += n
            return out
        }

        fun readMap(): List<Kv> {
            val entries = mutableListOf<Kv>()
            while (true) {
                val keyLen = readCompact()
                if (keyLen == 0) break
                val key = readBytes(keyLen)
                val value = readBytes(readCompact())
                entries.add(
                    Kv(
                        keyType = key[0].toInt() and 0xff,
                        keyData = if (key.size > 1) key.copyOfRange(1, key.size) else ByteArray(0),
                        value = value
                    )
                )
            }
            return entries
        }
    }

    companion object {
        private const val CHANGE_INDEX = 4
        private const val FINGERPRINT = "a1b2c3d4"
        private const val CHANGE_PATH = "m/84'/0'/0'/1/4"

        @Volatile
        private var startedKoin = false

        private fun txid(n: Int): String = "%064x".format(n)

        private fun ensureRuntime() {
            if (GlobalContext.getOrNull() == null) {
                val app = ApplicationProvider.getApplicationContext<Application>()
                val prefs = PrefsUtil(app)
                prefs.testnet = false
                startKoin {
                    androidContext(app)
                    modules(
                        module {
                            single { prefs }
                            single { CollectionRepository() }
                        }
                    )
                }
                startedKoin = true
            }
            check(!SentinelState.isTestNet()) { "PSBT fixture is a mainnet zpub" }
            // Drain the main-thread work SentinelState posts on first use.
            shadowOf(Looper.getMainLooper()).idle()
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            if (startedKoin) {
                stopKoin()
                startedKoin = false
            }
        }
    }
}
