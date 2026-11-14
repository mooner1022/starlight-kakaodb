package dev.mooner.kakaodb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectRetryTest {

    @Test
    fun theWaitDoublesFromFiveSecondsUpToFiveMinutes() {
        assertEquals(
            listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L),
            (1..8).map(ConnectRetry::delayMillis),
        )
    }

    @Test
    fun itStaysAtTheLongestHoweverLongItFails() {
        for (failures in listOf(9, 64, 65, 1_000, Int.MAX_VALUE))
            assertEquals(ConnectRetry.MAX_DELAY_MILLIS, ConnectRetry.delayMillis(failures), "$failures")
    }

    @Test
    fun aCountThatMakesNoSenseStillWaits() {
        for (failures in listOf(0, -1, Int.MIN_VALUE))
            assertTrue(ConnectRetry.delayMillis(failures) >= ConnectRetry.FIRST_DELAY_MILLIS, "$failures")
    }
}
