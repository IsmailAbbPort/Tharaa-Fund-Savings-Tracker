package com.tharaa.savings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Regressions from the app-wide audit. Each of these failed before its fix. */
class AuditFixesTest {

    private val day = 24L * 60 * 60 * 1000
    private val rates = listOf(RateChange(effectiveTimestamp = 0L, annualRateBps = 1800))

    private fun dep(minor: Long, ts: Long) =
        Txn(labelId = "L", type = TxnType.DEPOSIT, amountMinor = minor, timestamp = ts)
    private fun wd(minor: Long, ts: Long) =
        Txn(labelId = "L", type = TxnType.WITHDRAWAL, amountMinor = minor, timestamp = ts)

    @Before fun resetRepository() {
        SavingsRepository.importJson("""{"txns":[]}""")
        SavingsRepository.clearPasscode()
        SavingsRepository.lockSession()
    }

    // ---- money parsing --------------------------------------------------------

    @Test fun anAmountTooBigForLongIsRejectedRatherThanWrapped() {
        // BigDecimal.toLong() silently returned the low 64 bits: this one came back as about
        // 18.6 quadrillion EGP, and a slightly different one came back negative.
        assertNull(Money.parseToMinor("99999999999999999999"))
        assertNull(Money.parseToMinor("12345678901234567890"))
    }

    @Test fun ordinaryAmountsStillParse() {
        assertEquals(123450L, Money.parseToMinor("1,234.50"))
        assertEquals(100_000_000_00L, Money.parseToMinor("100000000"))
    }

    @Test fun formattingTheMostNegativeLongDoesNotProduceGarbage() {
        // abs(Long.MIN_VALUE) is still negative, which used to make pounds and piastres negative.
        assertTrue(Money.formatMinor(Long.MIN_VALUE).startsWith("-"))
    }

    // ---- interest -------------------------------------------------------------

    @Test fun overWithdrawingFloorsAtZeroOnEveryDayNotJustTheFirst() {
        val txns = listOf(dep(100_000, 0), wd(200_000, 10 * day))
        // Day 10 was already clamped by the first-day rule; day 11 onwards used to compound the
        // overdraft further negative, "earning" negative interest forever.
        assertEquals(0L, Interest.valueMinor(txns, rates, 10 * day))
        assertEquals(0L, Interest.valueMinor(txns, rates, 11 * day))
        assertEquals(0L, Interest.valueMinor(txns, rates, 365 * day))
    }

    @Test fun interestIgnoresATransactionDatedInTheFuture() {
        // valueMinor already ignored it; netPrincipalMinor counted it, so the difference between
        // them reported a large negative "interest".
        val txns = listOf(dep(100_000, 0), dep(100_000, 1000 * day))
        val interest = Interest.interestMinor(txns, rates, 365 * day)
        assertTrue("interest should be positive, was $interest", interest > 0)
    }

    @Test fun netPrincipalStillCountsEverythingByDefault() {
        val txns = listOf(dep(100_000, 0), dep(100_000, 1000 * day))
        assertEquals(200_000L, Interest.netPrincipalMinor(txns))
    }

    // ---- rate schedule --------------------------------------------------------

    @Test fun theEarliestRateChangeCannotBeDeleted() {
        val anchor = RateChange(effectiveTimestamp = 0L, annualRateBps = 1800)
        val later = RateChange(effectiveTimestamp = 500 * day, annualRateBps = 2500)
        SavingsRepository.importJson(
            """{"txns":[],"rateChanges":[
                {"id":"${anchor.id}","effectiveTimestamp":0,"annualRateBps":1800},
                {"id":"${later.id}","effectiveTimestamp":${500 * day},"annualRateBps":2500}]}"""
        )

        // Deleting the anchor would recompute every day of history at 25%.
        SavingsRepository.deleteRateChange(anchor.id)
        assertEquals(2, SavingsRepository.data.value.rateChanges.size)

        // A later one is still fair game.
        SavingsRepository.deleteRateChange(later.id)
        assertEquals(1, SavingsRepository.data.value.rateChanges.size)
    }

    // ---- app lock across a trip to a system screen ----------------------------

    @Test fun aQuickHopToTheFilePickerKeepsTheAppUnlocked() {
        SavingsRepository.markUnlocked()
        SavingsRepository.suppressNextLock()
        assertTrue(SavingsRepository.consumeSkipLock())

        val leftAt = 1_000_000L
        SavingsRepository.beginSystemExcursion(leftAt)
        SavingsRepository.endSystemExcursion(leftAt + 5_000)

        assertTrue(SavingsRepository.sessionUnlocked.value)
    }

    @Test fun walkingAwayFromTheFilePickerLocksAfterAll() {
        SavingsRepository.markUnlocked()
        SavingsRepository.suppressNextLock()
        SavingsRepository.consumeSkipLock()

        val leftAt = 1_000_000L
        SavingsRepository.beginSystemExcursion(leftAt)
        // Back an hour later: the excursion is over, and so is the grace.
        SavingsRepository.endSystemExcursion(leftAt + 60 * 60_000)

        assertFalse(SavingsRepository.sessionUnlocked.value)
    }

    @Test fun anOrdinaryResumeWithNoExcursionChangesNothing() {
        SavingsRepository.markUnlocked()
        SavingsRepository.endSystemExcursion(1_000_000L)
        assertTrue(SavingsRepository.sessionUnlocked.value)
    }

    // ---- upgrade path ---------------------------------------------------------

    @Test fun upgradingFromTheTwoLabelBuildKeepsNamesAndGoals() {
        // migrateLegacy had no test at all: a regression here silently drops the custom names and
        // goals of everyone upgrading.
        val legacy = """
            {"txns":[],"seriousName":"Emergency","funName":"Travel",
             "seriousGoalMinor":5000000,"funGoalMinor":250000}
        """.trimIndent()
        assertTrue(SavingsRepository.importJson(legacy))

        val d = SavingsRepository.data.value
        assertEquals("Emergency", d.labelById(SavingsData.SERIOUS_ID)?.name)
        assertEquals("Travel", d.labelById(SavingsData.FUN_ID)?.name)
        assertEquals(5_000_000L, d.labelById(SavingsData.SERIOUS_ID)?.goalMinor)
        assertEquals(250_000L, d.labelById(SavingsData.FUN_ID)?.goalMinor)
        // The legacy fields are cleared once folded in, so the migration doesn't re-run.
        assertNull(d.seriousName)
        assertNull(d.funName)
    }
}
