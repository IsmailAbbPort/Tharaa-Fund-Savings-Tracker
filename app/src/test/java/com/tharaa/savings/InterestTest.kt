package com.tharaa.savings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterestTest {

    private val day = 24L * 60 * 60 * 1000

    private fun rate(ts: Long, bps: Int) = RateChange(effectiveTimestamp = ts, annualRateBps = bps)
    private fun dep(minor: Long, ts: Long) =
        Txn(labelId = "FUN", type = TxnType.DEPOSIT, amountMinor = minor, timestamp = ts)
    private fun wd(minor: Long, ts: Long) =
        Txn(labelId = "FUN", type = TxnType.WITHDRAWAL, amountMinor = minor, timestamp = ts)

    /** 18% from the start of time, so it applies to every deposit. */
    private val seed = listOf(rate(0, 1800))

    /** BigDecimal daily compounding vs a reference calc can differ by a piastre; allow a tiny slack. */
    private fun assertNear(expected: Long, actual: Long, tol: Long = 2) =
        assertTrue("expected ~$expected but was $actual", kotlin.math.abs(actual - expected) <= tol)

    @Test fun emptyLabelIsZero() {
        assertEquals(0L, Interest.valueMinor(emptyList(), seed, 730 * day))
    }

    @Test fun sameDayEarnsNothingYet() {
        // Deposited today: value is exactly the principal, no interest accrued.
        assertEquals(100_000L, Interest.valueMinor(listOf(dep(100_000, 0)), seed, 0))
    }

    @Test fun oneYearCompoundsDaily() {
        // 1000 EGP at 18%/yr daily compounded for 365 days -> ~1197.16 EGP.
        assertNear(119_716L, Interest.valueMinor(listOf(dep(100_000, 0)), seed, 365 * day))
    }

    @Test fun ninetyDays() {
        assertNear(104_537L, Interest.valueMinor(listOf(dep(100_000, 0)), seed, 90 * day))
    }

    @Test fun twoDepositsEachCompoundFromItsOwnDay() {
        // Second 1000 deposited on day 180; value read at day 365.
        assertNear(
            229_266L,
            Interest.valueMinor(listOf(dep(100_000, 0), dep(100_000, 180 * day)), seed, 365 * day)
        )
    }

    @Test fun withdrawalStopsCompoundingOnRemovedMoney() {
        // 1000 at day 0, take out 500 at day 365, read at day 730.
        assertNear(
            83_462L,
            Interest.valueMinor(listOf(dep(100_000, 0), wd(50_000, 365 * day)), seed, 730 * day)
        )
    }

    @Test fun rateChangeOnlyAffectsFutureDays() {
        // 18% for the first year, then 0% from day 365. Second year adds nothing, so the value
        // stays at the year-1 figure. This is the "future interest calculated correctly" guarantee.
        val schedule = listOf(rate(0, 1800), rate(365 * day, 0))
        assertNear(119_716L, Interest.valueMinor(listOf(dep(100_000, 0)), schedule, 730 * day))
    }

    @Test fun netPrincipalIsDepositsMinusWithdrawals() {
        assertEquals(50_000L, Interest.netPrincipalMinor(listOf(dep(100_000, 0), wd(50_000, 10 * day))))
    }

    @Test fun interestIsValueMinusPrincipal() {
        val txns = listOf(dep(100_000, 0))
        val value = Interest.valueMinor(txns, seed, 365 * day)
        val interest = Interest.interestMinor(txns, seed, 365 * day)
        assertEquals(value - 100_000L, interest)
    }

    @Test fun rateBpsOnDayPicksLatestOnOrBefore() {
        val schedule = listOf(rate(0, 1800), rate(100 * day, 1500), rate(200 * day, 2000))
        assertEquals(1800, Interest.rateBpsOnDay(50, schedule))
        assertEquals(1500, Interest.rateBpsOnDay(100, schedule))   // effective same day
        assertEquals(1500, Interest.rateBpsOnDay(150, schedule))
        assertEquals(2000, Interest.rateBpsOnDay(250, schedule))
    }

    @Test fun compoundsAcrossMultipleRatePeriods() {
        // 18% for days 0-100, 15% for 100-200, 20% for 200-300.
        val schedule = listOf(rate(0, 1800), rate(100 * day, 1500), rate(200 * day, 2000))
        assertNear(115_624L, Interest.valueMinor(listOf(dep(100_000, 0)), schedule, 300 * day))
    }

    @Test fun backdatedRateRecomputesThatPeriod() {
        // User later records that the rate was actually 0% from day 100 onward. Interest before
        // day 100 stays at 18%; from day 100 nothing more accrues.
        val schedule = listOf(rate(0, 1800), rate(100 * day, 0))
        assertNear(105_054L, Interest.valueMinor(listOf(dep(100_000, 0)), schedule, 365 * day))
    }

    @Test fun futureDatedDepositIsIgnoredUntilItsDay() {
        // A deposit dated after "now" must not contribute value yet (it hasn't happened).
        val txns = listOf(dep(100_000, 0), dep(100_000, 1000 * day))
        assertNear(119_716L, Interest.valueMinor(txns, seed, 365 * day))
    }

    @Test fun multipleTxnsOnTheSameDayAreNetted() {
        // +1000 and -300 on day 0 behaves like a single 700 deposit.
        val txns = listOf(dep(100_000, 0), wd(30_000, 0))
        assertNear(83_801L, Interest.valueMinor(txns, seed, 365 * day))
    }

    @Test fun withdrawingTheWholeValueLeavesRoughlyZero() {
        // Deposit 1000, let it grow a year to ~1197.16, withdraw that, then a further year leaves ~0.
        val txns = listOf(dep(100_000, 0), wd(119_716, 365 * day))
        assertNear(0L, Interest.valueMinor(txns, seed, 730 * day))
    }

    @Test fun interestBetweenOverWholeLifeEqualsLifetimeInterest() {
        // From before the first deposit to one year later == the lifetime interest figure.
        val txns = listOf(dep(100_000, 0))
        assertNear(19_716L, Interest.interestEarnedBetween(txns, seed, 0, 365 * day))
    }

    @Test fun interestBetweenExcludesContributionsInTheWindow() {
        // A deposit lands inside the window; it must not be counted as "interest".
        val txns = listOf(dep(100_000, 0), dep(100_000, 400 * day))
        assertNear(3_393L, Interest.interestEarnedBetween(txns, seed, 380 * day, 420 * day))
    }

    @Test fun valueBeforeTheFirstDepositIsZero() {
        // Regression: asking for the value at a time earlier than any deposit must be 0, not the
        // total of deposits made later. (This bug made "this month/year" interest go negative.)
        val txns = listOf(dep(100_000, 30 * day))
        assertEquals(0L, Interest.valueMinor(txns, seed, 10 * day))
    }

    @Test fun periodInterestBeforeAnyDepositIsZeroNotNegative() {
        // Deposited today (day 30); a period that starts before that shows 0 earned, never negative.
        val txns = listOf(dep(100_000, 30 * day))
        assertEquals(0L, Interest.interestEarnedBetween(txns, seed, 0, 30 * day))
    }

    @Test fun interestBetweenIsZeroForEmptyOrInvertedWindow() {
        val txns = listOf(dep(100_000, 0))
        assertEquals(0L, Interest.interestEarnedBetween(txns, seed, 400 * day, 400 * day))
        assertEquals(0L, Interest.interestEarnedBetween(txns, seed, 500 * day, 400 * day))
    }
}
