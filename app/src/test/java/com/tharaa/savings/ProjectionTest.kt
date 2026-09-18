package com.tharaa.savings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionTest {

    private fun assertNear(expected: Long, actual: Long, tol: Long = 5) =
        assertTrue("expected ~$expected but was $actual", kotlin.math.abs(actual - expected) <= tol)

    @Test fun twelveMonthsNoContributionsMatchesOneYearDailyCompound() {
        // Consistency with Interest: 1000 EGP at 18% for 12 months == the app's one-year value.
        val r = Projection.project(100_000, 1800, 0, 0, 12)
        assertNear(119_716L, r.finalValueMinor)
        assertNear(19_716L, r.interestMinor)
    }

    @Test fun zeroRateJustSumsContributions() {
        val r = Projection.project(0, 0, 10_000, 0, 12)
        assertEquals(120_000L, r.finalValueMinor)
        assertEquals(120_000L, r.totalDepositedMinor)
        assertEquals(0L, r.interestMinor)
    }

    @Test fun pointsCoverEveryMonthPlusStart() {
        val r = Projection.project(50_000, 1500, 1_000, 500, 36)
        assertEquals(37, r.points.size)        // month 0..36
        assertEquals(0, r.points.first().month)
        assertEquals(36, r.points.last().month)
        assertEquals(50_000L, r.points.first().valueMinor)
        assertEquals(r.finalValueMinor, r.points.last().valueMinor)
    }

    @Test fun contributedLineTracksMoneyInNotGrowth() {
        val r = Projection.project(100_000, 1800, 10_000, 0, 12)
        // Money in after a year = start + 12 deposits; interest is the gap above that.
        assertEquals(100_000L + 120_000L, r.points.last().contributedMinor)
        assertTrue(r.finalValueMinor > r.points.last().contributedMinor)
    }

    @Test fun yearlyIncreaseRaisesContributionsAfterEachYear() {
        val flat = Projection.project(0, 1800, 100_000, 0, 24, 0)
        val rising = Projection.project(0, 1800, 100_000, 0, 24, 1000) // +10%/yr
        // Year 2 deposits are larger, so more is put in and the final value is higher.
        assertTrue(rising.totalDepositedMinor > flat.totalDepositedMinor)
        assertTrue(rising.finalValueMinor > flat.finalValueMinor)
    }

    @Test fun withdrawalsCanDepleteBelowZero() {
        val r = Projection.project(1_000_000, 1800, 0, 200_000, 12)
        assertTrue("should run dry", r.finalValueMinor < 0)
    }

    @Test fun monthCountIsClampedToSaneMax() {
        val r = Projection.project(1000, 1800, 0, 0, 100_000)
        assertEquals(1201, r.points.size) // capped at 1200 months + the start point
    }

    @Test fun monthsToReachZeroWhenAlreadyThere() {
        assertEquals(0, Projection.monthsToReach(100_000, 50_000, 1800, 0))
    }

    @Test fun monthsToReachWithContributions() {
        // 500k -> 1M at 18% adding 10k/month lands at month 24 (verified independently).
        assertEquals(24, Projection.monthsToReach(50_000_000, 100_000_000, 1800, 1_000_000))
    }

    @Test fun monthsToReachOnInterestAlone() {
        assertEquals(47, Projection.monthsToReach(50_000_000, 100_000_000, 1800, 0))
    }

    @Test fun monthsToReachNullWhenUnreachable() {
        assertEquals(null, Projection.monthsToReach(10_000, 100_000_000, 0, 0))
    }
}
