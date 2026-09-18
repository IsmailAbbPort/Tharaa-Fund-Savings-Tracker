package com.tharaa.savings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository's read helpers are pure (they take the data in), so we can verify the core
 * promise of the app - labels are tracked completely separately - without touching the encrypted
 * file or Android at all.
 */
class SavingsRepositoryLogicTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 365 * day

    private fun dep(labelId: String, minor: Long, ts: Long) =
        Txn(labelId = labelId, type = TxnType.DEPOSIT, amountMinor = minor, timestamp = ts)
    private fun wd(labelId: String, minor: Long, ts: Long) =
        Txn(labelId = labelId, type = TxnType.WITHDRAWAL, amountMinor = minor, timestamp = ts)

    private fun assertNear(expected: Long, actual: Long, tol: Long = 2) =
        assertTrue("expected ~$expected but was $actual", kotlin.math.abs(actual - expected) <= tol)

    private val data = SavingsData(
        txns = listOf(
            dep("SERIOUS", 200_000, 0),   // 2000 EGP serious
            dep("FUN", 100_000, 0),        // 1000 EGP fun
            wd("FUN", 30_000, 0),          // pull 300 back out of fun (same day)
        ),
        rateChanges = listOf(RateChange(effectiveTimestamp = 0L, annualRateBps = 1800)),
    )

    @Test fun txnsForReturnsOnlyThatLabel() {
        assertEquals(1, SavingsRepository.txnsFor("SERIOUS", data).size)
        assertEquals(2, SavingsRepository.txnsFor("FUN", data).size)
    }

    @Test fun netPrincipalIsPerLabel() {
        assertEquals(200_000L, SavingsRepository.netPrincipalMinor("SERIOUS", data))
        assertEquals(70_000L, SavingsRepository.netPrincipalMinor("FUN", data)) // 1000 - 300
    }

    @Test fun valueIsPerLabelAndDoesNotBleedAcross() {
        // Serious: 2000 EGP for a year -> ~2394.32; Fun: 700 EGP for a year -> ~838.01.
        assertNear(239_432L, SavingsRepository.valueMinor("SERIOUS", now, data))
        assertNear(83_801L, SavingsRepository.valueMinor("FUN", now, data))
    }

    @Test fun interestIsValueMinusPrincipalPerLabel() {
        val v = SavingsRepository.valueMinor("FUN", now, data)
        val p = SavingsRepository.netPrincipalMinor("FUN", data)
        assertEquals(v - p, SavingsRepository.interestMinor("FUN", now, data))
    }

    @Test fun emptyLabelIsZeroEvenWhenTheOtherHasMoney() {
        val onlyFun = SavingsData(txns = listOf(dep("FUN", 100_000, 0)))
        assertEquals(0L, SavingsRepository.valueMinor("SERIOUS", now, onlyFun))
        assertEquals(0L, SavingsRepository.netPrincipalMinor("SERIOUS", onlyFun))
    }

    @Test fun customLabelIdIsIsolatedToo() {
        val d = data.copy(txns = data.txns + dep("custom-id", 500_000, 0))
        assertEquals(1, SavingsRepository.txnsFor("custom-id", d).size)
        assertEquals(500_000L, SavingsRepository.netPrincipalMinor("custom-id", d))
        // The built-in labels are untouched by the new one.
        assertEquals(200_000L, SavingsRepository.netPrincipalMinor("SERIOUS", d))
    }
}
