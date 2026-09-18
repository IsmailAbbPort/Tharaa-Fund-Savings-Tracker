package com.tharaa.savings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary cases the original suite never reached, because every fixture in it used timestamps on
 * exact day multiples - the one alignment where day-bucketed and millisecond-compared code agree.
 * Each test here failed before its fix.
 */
class InterestWindowTest {

    private val day = 24L * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000
    private val rates = listOf(RateChange(effectiveTimestamp = 0L, annualRateBps = 1800))

    private fun dep(minor: Long, ts: Long) =
        Txn(labelId = "L", type = TxnType.DEPOSIT, amountMinor = minor, timestamp = ts)

    @Test fun interestThisMonthCountsADepositMadeLaterOnTheOpeningDayOnlyOnce() {
        // Window opens at day 100. The deposit lands 9 hours into that same day, so valueMinor
        // already includes it at both ends: it is not a contribution *within* the window.
        val windowStart = 100 * day
        val txns = listOf(dep(5_000_000, 0), dep(1_000_000, windowStart + 9 * hour))

        val earned = Interest.interestEarnedBetween(txns, rates, windowStart, windowStart + 10 * day)

        // Ten days of 18% on roughly 60,000 EGP is a few hundred EGP. The bug returned about
        // -9,697 EGP, which the UI then clamped to a flat zero.
        assertTrue("expected a small positive figure, got $earned", earned in 1..100_000)
    }

    @Test fun aDepositTrulyInsideTheWindowIsStillStrippedOut() {
        val windowStart = 100 * day
        val txns = listOf(dep(5_000_000, 0), dep(1_000_000, windowStart + 3 * day))

        val earned = Interest.interestEarnedBetween(txns, rates, windowStart, windowStart + 10 * day)

        assertTrue("principal leaked into the interest figure: $earned", earned in 1..100_000)
    }

    @Test fun twoRateChangesOnOneDayAccrueAtTheLaterOne() {
        // Typed 18%, corrected to 12% a minute later. Both land on day 10.
        val changes = listOf(
            RateChange(effectiveTimestamp = 0L, annualRateBps = 1800),
            RateChange(effectiveTimestamp = 10 * day + hour, annualRateBps = 1800),
            RateChange(effectiveTimestamp = 10 * day + hour + 60_000, annualRateBps = 1200),
        )
        assertEquals(1200, Interest.rateBpsOnDay(10, changes))
        assertEquals(1200, Interest.rateBpsOnDay(400, changes))
    }

    @Test fun theDisplayedRateIsTheRateThatAccrues() {
        val changes = listOf(
            RateChange(effectiveTimestamp = 0L, annualRateBps = 1800),
            RateChange(effectiveTimestamp = 10 * day + hour, annualRateBps = 1800),
            RateChange(effectiveTimestamp = 10 * day + hour + 60_000, annualRateBps = 1200),
        )
        val d = SavingsData(rateChanges = changes)
        assertEquals(d.rateBpsAt(400 * day), Interest.rateBpsOnDay(400, changes))
    }

    @Test fun aRateChangeDatedInTheFutureIsNotYetTheCurrentRate() {
        val now = 100 * day
        val d = SavingsData(
            rateChanges = listOf(
                RateChange(effectiveTimestamp = 0L, annualRateBps = 1800),
                RateChange(effectiveTimestamp = 500 * day, annualRateBps = 1200),
            )
        )
        // Scheduled, not current: Interest keeps compounding at 18% until it lands, so every
        // screen that quotes "the current rate" has to say 18% too.
        assertEquals(1800, d.rateBpsAt(now))
        assertEquals(1200, d.rateBpsAt(600 * day))
    }
}
