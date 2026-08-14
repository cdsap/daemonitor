package io.github.cdsap.daemonitor.ui.common

/**
 * Next/previous index for keyboard list navigation. Wraps at both ends ("cycle").
 * When nothing is selected (`currentIndex == null` or out of range), Down picks the first
 * item and Up picks the last.
 */
fun cycleIndex(size: Int, currentIndex: Int?, delta: Int): Int? {
    if (size <= 0 || delta == 0) return null
    val start = when {
        currentIndex != null && currentIndex in 0 until size -> currentIndex
        delta > 0 -> -1
        else -> size
    }
    return Math.floorMod(start + delta, size)
}
