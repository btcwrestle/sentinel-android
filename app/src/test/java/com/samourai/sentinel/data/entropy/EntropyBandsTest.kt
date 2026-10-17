package com.samourai.sentinel.data.entropy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the #115 display ladder to the measured anchors: the
 * StonewallPinTest fixtures (solo 5, x2 3) and the wallet's
 * cached remix rows (1496; 7x7 = 426,833). These assertions ARE
 * the spec for the bar's color and fill - display truth, never
 * relaxed to match a rendering.
 */
class EntropyBandsTest {

    @Test
    fun redBandCoversZeroAndSingleBit() {
        assertEquals(EntropyBand.RED, EntropyBands.band(1))
        assertEquals(EntropyBand.RED, EntropyBands.band(2))
    }

    @Test
    fun amberBandIsBelowTheStonewallPin() {
        // Stonewallx2 corroboration: 3 interpretations, amber.
        assertEquals(EntropyBand.AMBER, EntropyBands.band(3))
        assertEquals(EntropyBand.AMBER, EntropyBands.band(4))
    }

    @Test
    fun greenFloorIsTheSoloStonewallPin() {
        assertEquals(EntropyBand.GREEN, EntropyBands.band(EntropyBands.GREEN_FLOOR))
    }

    @Test
    fun fillClampsAtFiveRungsForRealRemixes() {
        assertEquals(1, EntropyBands.filled(1))
        assertEquals(3, EntropyBands.filled(3))
        assertEquals(5, EntropyBands.filled(5))
        assertEquals(5, EntropyBands.filled(1496))
        assertEquals(5, EntropyBands.filled(426_833))
    }
}
