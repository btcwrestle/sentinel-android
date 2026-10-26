package com.samourai.sentinel.core.access

import com.samourai.sentinel.data.db.PayloadCipher
import com.samourai.sentinel.data.db.PayloadRecord
import com.samourai.sentinel.data.db.isLegacyOpenSslPayload
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.wallet.crypto.AesGcm
import com.samourai.wallet.crypto.Argon2Kdf
import com.samourai.wallet.crypto.AuthTagException
import org.bouncycastle.util.encoders.Base64
import org.json.JSONObject
import timber.log.Timber
import java.security.SecureRandom

/**
 * PIN material for the payload key.
 *
 * The data key is random. It is wrapped with AES-GCM under a key from
 * Argon2id(PIN, salt). When the device keystore is usable, that wrap is
 * sealed again before it is stored. Unlock succeeds only when both layers
 * that were written open. A previous install that still has [pinHash] is
 * rewritten on the next successful unlock; the verifier is removed only
 * after the payload files are in the current format.
 */
class PinVault(
    private val store: PinMaterialStore,
    private val access: AccessFactory = AccessFactory.getInstance(null),
    private val sealer: AtRestSealer = AtRestSealers.deviceDefault()
) {

    constructor(prefs: PrefsUtil) : this(PrefsPinMaterialStore(prefs))

    fun enable(pin: String) {
        val current = access.dataKey
        if (current != null && current.isNotEmpty()) {
            // Already unlocked. Wrap this key again instead of minting a
            // second one, which would leave existing payload files unreadable.
            store.pinKeyMaterial = wrapDek(pin, current)
            store.pinEnabled = true
            access.pin = pin
            access.setPinProtected(true)
            access.setIsLoggedIn(true)
            return
        }
        val existing = store.pinKeyMaterial
        if (!existing.isNullOrBlank()) {
            install(pin, openDek(pin, existing))
            store.pinEnabled = true
            return
        }
        // A previous verifier means payload files may still be in the
        // previous format. Refuse a different PIN so those files stay readable.
        if (!store.pinHash.isNullOrBlank() && !legacyMatches(pin)) {
            throw WrongPinException()
        }
        val dek = ByteArray(AesGcm.KEY_LEN)
        random.nextBytes(dek)
        try {
            store.pinKeyMaterial = wrapDek(pin, dek)
        } catch (e: Exception) {
            dek.fill(0)
            throw e
        }
        store.pinEnabled = true
        access.dataKey = dek
        access.pin = pin
        access.setPinProtected(true)
        access.setIsLoggedIn(true)
    }

    /**
     * Install a key from [pin] only when this device has none yet.
     * Used after a backup PIN has already been accepted, so a fresh
     * restore writes payloads under the current format. When a key is
     * already unlocked, [records] that are still plaintext are rewritten.
     */
    fun protectFreshImport(pin: String, records: List<PayloadRecord> = emptyList()) {
        if (access.dataKey == null && !store.isPinConfigured()) {
            enable(pin)
        }
        if (access.dataKey != null) {
            upgradeExisting(records)
        }
    }

    fun unlock(pin: String, records: List<PayloadRecord> = emptyList()): UnlockResult {
        if (!store.isPinConfigured()) {
            return UnlockResult.NotConfigured
        }
        val material = store.pinKeyMaterial
        if (!material.isNullOrBlank()) {
            val dek = try {
                openDek(pin, material)
            } catch (e: WrongPinException) {
                return UnlockResult.WrongPin
            } catch (e: Exception) {
                Timber.w(e, "Unlock material could not be opened")
                return UnlockResult.Unavailable(UNAVAILABLE)
            }
            install(pin, dek)
            // Also covers a PIN that was saved and then the process stopped
            // before payload files were rewritten, including a removal that
            // wrote plaintext and did not finish clearing the wrap.
            if (upgradeExisting(records)) {
                markPayloadsCurrent()
            }
            return UnlockResult.Success
        }
        if (!legacyMatches(pin)) {
            return UnlockResult.WrongPin
        }
        access.pin = pin
        access.setPinProtected(true)
        access.setIsLoggedIn(true)
        val dek = ByteArray(AesGcm.KEY_LEN)
        random.nextBytes(dek)
        try {
            store.pinKeyMaterial = wrapDek(pin, dek)
            store.pinEnabled = true
            access.dataKey = dek
        } catch (e: Exception) {
            access.pin = null
            access.setPinProtected(false)
            Timber.w(e, "Could not install a data key")
            return UnlockResult.Unavailable(UNAVAILABLE)
        }
        if (upgradeExisting(records)) {
            markPayloadsCurrent()
        }
        return UnlockResult.Success
    }

    /** Drops the previous verifier once payload files are in the current format. */
    fun markPayloadsCurrent() {
        if (!store.pinHash.isNullOrBlank()) {
            store.pinHash = ""
        }
    }

    /**
     * Rewrite payload files that are still plaintext or in the previous
     * format. Returns false when a previous-format file could not be read,
     * leaving it in place.
     */
    fun upgradeExisting(records: List<PayloadRecord>): Boolean {
        var complete = true
        for (record in records) {
            val target = record.file
            if (!target.exists() || target.length() == 0L) {
                continue
            }
            val raw = try {
                target.readText()
            } catch (e: Exception) {
                Timber.w(e, "Could not read %s", record.name)
                complete = false
                continue
            }
            if (PayloadCipher.isModern(raw)) {
                try {
                    record.decrypt(raw)
                } catch (e: Exception) {
                    Timber.w(e, "Current payload %s did not open", record.name)
                    complete = false
                }
                continue
            }
            val plain = try {
                if (isLegacyOpenSslPayload(raw)) record.decrypt(raw) else raw
            } catch (e: Exception) {
                Timber.w(e, "Previous payload %s left unchanged", record.name)
                complete = false
                continue
            }
            try {
                record.writeToFile(plain)
                val written = record.decrypt(record.file.readText())
                if (written != plain) {
                    complete = false
                }
            } catch (e: Exception) {
                Timber.w(e, "Could not rewrite %s", record.name)
                complete = false
            }
        }
        return complete
    }

    fun disable(pin: String, records: List<PayloadRecord>): Boolean {
        if (unlock(pin, records) != UnlockResult.Success) {
            return false
        }
        val plains = ArrayList<Pair<PayloadRecord, String>>()
        for (record in records) {
            if (!record.file.exists() || record.file.length() == 0L) {
                continue
            }
            val raw = record.file.readText()
            val plain = if (PayloadCipher.isModern(raw) || isLegacyOpenSslPayload(raw)) {
                record.decrypt(raw)
            } else {
                raw
            }
            plains.add(record to plain)
        }
        val keep = access.dataKey?.copyOf()
        val savedPin = access.pin
        access.clearDataKey()
        access.pin = null
        access.setPinProtected(false)
        return try {
            for ((record, plain) in plains) {
                record.writeToFile(plain, keepBackup = false)
            }
            store.pinHash = ""
            store.pinKeyMaterial = ""
            store.pinEnabled = false
            access.setIsLoggedIn(false)
            keep?.fill(0)
            true
        } catch (e: Exception) {
            Timber.w(e, "Could not remove PIN protection")
            access.dataKey = keep
            access.pin = savedPin
            access.setPinProtected(savedPin?.isNotEmpty() == true)
            upgradeExisting(records)
            false
        }
    }

    private fun install(pin: String, dek: ByteArray) {
        access.dataKey = dek
        access.pin = pin
        access.setPinProtected(true)
        access.setIsLoggedIn(true)
    }

    private fun legacyMatches(pin: String): Boolean {
        val hash = store.pinHash
        if (hash.isNullOrBlank()) {
            return false
        }
        return try {
            access.validateHash(pin, hash)
        } catch (e: Exception) {
            false
        }
    }

    private fun wrapDek(pin: String, dek: ByteArray): String {
        val salt = ByteArray(SALT_LEN)
        random.nextBytes(salt)
        val pinChars = pin.toCharArray()
        val kek = Argon2Kdf.derive(
            pinChars,
            salt,
            Argon2Kdf.PIN_MEMORY_KIB,
            Argon2Kdf.PIN_ITERATIONS,
            Argon2Kdf.PIN_PARALLELISM,
            AesGcm.KEY_LEN
        )
        try {
            val inner = AesGcm.encrypt(kek, dek)
            val sealed = sealer.seals
            val stored = if (sealed) sealer.seal(inner) else inner
            if (sealed) {
                val opened = sealer.open(stored)
                check(opened.contentEquals(inner)) { "seal did not round-trip" }
            }
            val back = AesGcm.decrypt(kek, inner)
            check(back.contentEquals(dek)) { "wrap did not round-trip" }
            back.fill(0)
            return JSONObject()
                .put("v", 1)
                .put("kdf", "argon2id")
                .put("salt", Base64.toBase64String(salt))
                .put("memoryKiB", Argon2Kdf.PIN_MEMORY_KIB)
                .put("iterations", Argon2Kdf.PIN_ITERATIONS)
                .put("parallelism", Argon2Kdf.PIN_PARALLELISM)
                .put("sealed", sealed)
                .put("wrap", Base64.toBase64String(stored))
                .toString()
        } finally {
            pinChars.fill('\u0000')
            kek.fill(0)
        }
    }

    private fun openDek(pin: String, material: String): ByteArray {
        val json = try {
            JSONObject(material)
        } catch (e: Exception) {
            throw SealUnavailableException(e)
        }
        val memoryKiB = json.optInt("memoryKiB", -1)
        val iterations = json.optInt("iterations", -1)
        val parallelism = json.optInt("parallelism", -1)
        if (json.optString("kdf") != "argon2id" ||
            memoryKiB < 8 * 1024 || memoryKiB > 256 * 1024 ||
            iterations < 1 || iterations > 8 ||
            parallelism != 1
        ) {
            throw SealUnavailableException()
        }
        val salt = decode(json.optString("salt"))
        if (salt.size < 16 || salt.size > 64) {
            throw SealUnavailableException()
        }
        val wrap = decode(json.optString("wrap"))
        val inner = if (json.optBoolean("sealed", false)) {
            if (!sealer.seals) {
                throw SealUnavailableException()
            }
            try {
                sealer.open(wrap)
            } catch (e: Exception) {
                throw SealUnavailableException(e)
            }
        } else {
            wrap
        }
        val pinChars = pin.toCharArray()
        val kek = Argon2Kdf.derive(
            pinChars,
            salt,
            memoryKiB,
            iterations,
            parallelism,
            AesGcm.KEY_LEN
        )
        try {
            return AesGcm.decrypt(kek, inner)
        } catch (e: AuthTagException) {
            throw WrongPinException()
        } finally {
            pinChars.fill('\u0000')
            kek.fill(0)
        }
    }

    private fun decode(value: String): ByteArray {
        if (value.isEmpty()) {
            throw SealUnavailableException()
        }
        return try {
            Base64.decode(value)
        } catch (e: Exception) {
            throw SealUnavailableException(e)
        }
    }

    companion object {
        private const val SALT_LEN = 16
        private const val UNAVAILABLE = "Could not open saved unlock data. Try again."
        private val random = SecureRandom()
    }
}

interface PinMaterialStore {
    var pinHash: String?
    var pinEnabled: Boolean?
    var pinKeyMaterial: String?

    fun isPinConfigured(): Boolean {
        return !pinKeyMaterial.isNullOrBlank() || !pinHash.isNullOrBlank()
    }
}

class PrefsPinMaterialStore(private val prefs: PrefsUtil) : PinMaterialStore {
    override var pinHash: String?
        get() = prefs.pinHash
        set(value) {
            prefs.pinHash = value ?: ""
        }
    override var pinEnabled: Boolean?
        get() = prefs.pinEnabled
        set(value) {
            prefs.pinEnabled = value ?: false
        }
    override var pinKeyMaterial: String?
        get() = prefs.pinKeyMaterial
        set(value) {
            prefs.pinKeyMaterial = value ?: ""
        }
}

class MemoryPinMaterialStore : PinMaterialStore {
    override var pinHash: String? = ""
    override var pinEnabled: Boolean? = false
    override var pinKeyMaterial: String? = ""
}

sealed class UnlockResult {
    object Success : UnlockResult()
    object WrongPin : UnlockResult()
    object NotConfigured : UnlockResult()
    data class Unavailable(val message: String) : UnlockResult()
}

private class WrongPinException : Exception()
