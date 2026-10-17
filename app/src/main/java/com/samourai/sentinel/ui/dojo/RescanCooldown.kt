package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.ui.utils.PrefsUtil
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Per-key cooldown for "Rescan Xpub".
 *
 * A rescan is the most expensive thing Sentinel can ask a Dojo to do: Dojo's
 * `importHDAccount()` derives and scans address ranges against its indexer,
 * which is why ApiService.rescanPubKey waits up to ten minutes for an
 * answer. Nothing about that is visible to the user, who sees a balance that
 * is still wrong and a button that invites another go - so without a cooldown
 * the natural reaction to "it didn't work" is to tap Continue again, and on a
 * shared community Dojo a handful of users doing that is real load.
 *
 * This is a politeness guard, not a security control. Anyone who wants to
 * hammer `POST /xpub` can do it with curl and a pairing key; protecting a node
 * from a hostile client is the operator's job (rate limiting at the proxy).
 * What this does prevent is Sentinel being the thing that generates an
 * accidental stampede.
 *
 * The store is a JSON object of fingerprint to epoch millis, held in
 * [PrefsUtil.rescanCooldowns]. Keys are hashed rather than stored: the public
 * keys already live in the collection repository and prefs gains nothing by
 * holding them twice.
 *
 * The timestamp-free functions are pure so they can be unit tested without a
 * Context.
 */
object RescanCooldown {

    /**
     * How long to wait before the same key may be rescanned again.
     *
     * Fifteen minutes is a compromise. Shorter and it is decoration, since a
     * cold import can still be running. Much longer and someone whose import
     * genuinely needs a second attempt is stuck staring at a wrong balance.
     */
    val COOLDOWN_MILLIS: Long = TimeUnit.MINUTES.toMillis(15)

    /**
     * First 16 hex characters of the SHA-256 of [pubKey]. Long enough that a
     * collision between the keys in one wallet is not a practical concern, and
     * short enough to keep the stored object small.
     */
    fun fingerprint(pubKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(pubKey.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Milliseconds left before [pubKey] may be rescanned again, or 0 when it
     * is free to rescan now.
     *
     * A timestamp in the future - a clock that moved backwards, or a store
     * carried across devices by a backup - is treated as expired rather than
     * locking the key out until the clock catches up.
     */
    fun remainingMillis(store: JSONObject, pubKey: String, now: Long): Long {
        val last = store.optLong(fingerprint(pubKey), 0L)
        if (last <= 0L || last > now) return 0L
        val elapsed = now - last
        return if (elapsed >= COOLDOWN_MILLIS) 0L else COOLDOWN_MILLIS - elapsed
    }

    /** Whether [pubKey] may be rescanned at [now]. */
    fun isAllowed(store: JSONObject, pubKey: String, now: Long): Boolean =
        remainingMillis(store, pubKey, now) == 0L

    /**
     * Records a rescan of [pubKey] at [now] and drops entries that have
     * already expired, so the stored object cannot grow without bound as keys
     * are added and removed.
     */
    fun record(store: JSONObject, pubKey: String, now: Long): JSONObject {
        val pruned = prune(store, now)
        pruned.put(fingerprint(pubKey), now)
        return pruned
    }

    /** Returns a copy of [store] without entries whose cooldown has lapsed. */
    fun prune(store: JSONObject, now: Long): JSONObject {
        val kept = JSONObject()
        store.keys().forEach { key ->
            val last = store.optLong(key, 0L)
            if (last > 0L && last <= now && now - last < COOLDOWN_MILLIS) {
                kept.put(key, last)
            }
        }
        return kept
    }

    /**
     * Reads the store. A value that will not parse is discarded rather than
     * thrown: a corrupt cooldown record must never be the reason a user cannot
     * rescan.
     */
    fun load(prefsUtil: PrefsUtil): JSONObject {
        val raw = prefsUtil.rescanCooldowns
        if (raw.isNullOrBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            Timber.w(e, "Discarding unreadable rescan cooldown store")
            JSONObject()
        }
    }

    fun save(prefsUtil: PrefsUtil, store: JSONObject) {
        prefsUtil.rescanCooldowns = store.toString()
    }
}
