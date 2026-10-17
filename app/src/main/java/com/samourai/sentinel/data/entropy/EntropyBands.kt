package com.samourai.sentinel.data.entropy

/**
 * #115 band ladder, pinned fixture-first by StonewallPinTest
 * (solo Stonewall = 5 = the green floor; x2 = 3 = amber with
 * deterministic changes). The ladder is the display contract:
 *
 *  - GREY  no verdict (declined / unparseable) - caller-chosen,
 *          never derived from nbCmbn
 *  - RED   nbCmbn <= 2  (0-1 bit)
 *  - AMBER nbCmbn 3-4   (honest, below Stonewall grade)
 *  - GREEN nbCmbn >= 5  (the Stonewall pin, ~2.32 bits)
 *
 * Engine truth only: Samourai's "2 interpretations" marketing
 * number is displayed nowhere - this engine counts merged-entity
 * stories (the 1496-vs-120 convention), and so does this ladder.
 */
enum class EntropyBand { GREY, RED, AMBER, GREEN }

object EntropyBands {

    /** Stonewall solo pin (measured): the green floor. */
    const val GREEN_FLOOR = 5

    /** Rungs on the bar; filled = min(nbCmbn, RUNGS). */
    const val RUNGS = 5

    fun band(nbCmbn: Int): EntropyBand = when {
        nbCmbn <= 2 -> EntropyBand.RED
        nbCmbn <= GREEN_FLOOR - 1 -> EntropyBand.AMBER
        else -> EntropyBand.GREEN
    }

    fun filled(nbCmbn: Int): Int = nbCmbn.coerceIn(0, RUNGS)
}
