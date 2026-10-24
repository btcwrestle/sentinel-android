package com.samourai.sentinel.ui.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Backup prefs omit unlock material and session tokens, and import
 * does not write those keys back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsBackupFilterTest {

    private lateinit var prefs: PrefsUtil

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = PrefsUtil(context)
        prefs.clearAll()
    }

    @Test
    fun exportOmitsUnlockMaterialAndTokens() {
        prefs.selectedCurrency = "JPY"
        prefs.pinHash = "legacy-verifier"
        prefs.pinEnabled = true
        prefs.pinKeyMaterial = """{"v":1,"wrap":"secret"}"""
        prefs.authorization = "access-token"
        prefs.refreshToken = "refresh-token"
        prefs.streetMode = true

        val exported = prefs.export()
        assertEquals("JPY", exported.getString("selectedCurrency"))
        assertTrue(exported.getBoolean("streetMode"))
        assertFalse(exported.has("pinHash"))
        assertFalse(exported.has("pinEnabled"))
        assertFalse(exported.has("pinKeyMaterial"))
        assertFalse(exported.has("authorization"))
        assertFalse(exported.has("refreshToken"))
        assertFalse(exported.toString().contains("legacy-verifier"))
        assertFalse(exported.toString().contains("access-token"))
        assertFalse(exported.toString().contains("refresh-token"))
    }

    @Test
    fun importIgnoresUnlockMaterialAndTokens() {
        prefs.pinHash = "keep-me"
        prefs.pinKeyMaterial = "keep-material"
        prefs.authorization = "keep-access"
        prefs.refreshToken = "keep-refresh"
        prefs.pinEnabled = true
        prefs.selectedCurrency = "USD"

        val incoming = JSONObject()
            .put("selectedCurrency", "GBP")
            .put("pinHash", "from-backup")
            .put("pinEnabled", false)
            .put("pinKeyMaterial", "from-backup-material")
            .put("authorization", "from-backup-access")
            .put("refreshToken", "from-backup-refresh")
            .put("streetMode", true)
        prefs.import(incoming)

        assertEquals("GBP", prefs.selectedCurrency)
        assertEquals(true, prefs.streetMode)
        assertEquals("keep-me", prefs.pinHash)
        assertEquals("keep-material", prefs.pinKeyMaterial)
        assertEquals("keep-access", prefs.authorization)
        assertEquals("keep-refresh", prefs.refreshToken)
        assertEquals(true, prefs.pinEnabled)
    }
}
