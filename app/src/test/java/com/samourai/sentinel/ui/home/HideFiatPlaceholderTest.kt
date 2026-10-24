package com.samourai.sentinel.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Hide-fiat must remove the line, not replace a real amount with "00.00". */
class HideFiatPlaceholderTest {

    @Test
    fun `missing rate is blank and hide clears the fiat line`() {
        val vm = source("app/src/main/java/com/samourai/sentinel/ui/home/HomeViewModel.kt")
        val fiat = vm.substringAfter("private fun getFiatBalance(balance: Long?, rate: ExchangeRateRepository.Rate?): String")
            .substringBefore("fun getBTCDisplayAmount")
        assertFalse(fiat.contains("00.00"))
        assertTrue(fiat.contains("prefsUtil.fiatDisabled == true"))

        val home = source("app/src/main/java/com/samourai/sentinel/ui/home/HomeActivity.kt")
        val paint = home.substringAfter("private fun paintFiat")
            .substringBefore("override fun onResume()")
        assertTrue(paint.contains("exchangeRateTxt.text = \"\""))
        assertTrue(paint.contains("View.GONE"))

        val collection = source("app/src/main/java/com/samourai/sentinel/ui/collectionDetails/transactions/TransactionsFragment.kt")
        val render = collection.substringAfter("private fun renderBalance")
            .substringBefore("private fun setUpToolBar")
        assertTrue(render.contains("collectionBalanceFiat.text = \"\""))
        assertTrue(render.contains("View.GONE"))
        assertFalse(collection.contains("00.00"))

        val settings = source("app/src/main/java/com/samourai/sentinel/ui/settings/MainSettingsFragment.kt")
        assertTrue(settings.contains("prefsUtil.fiatDisabled = newValue as Boolean"))
    }

    private fun source(relative: String): String {
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
