package com.tharaa.savings

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Daily-compounding interest for a Tharaa label.
 *
 * The value of a label grows every day by (annualRate / 365), and yesterday's interest earns
 * interest today, which is how a money-market fund like Tharaa actually behaves. Deposits start
 * compounding from their day; withdrawals stop compounding from theirs. The rate is read from a
 * schedule, so a rate change only affects the days on or after it took effect.
 *
 * Everything runs on whole-day boundaries (UTC), which is plenty precise for tracking personal
 * savings and keeps the maths deterministic. Money stays in integer piastres; only the growth
 * factor uses BigDecimal, rounded back to piastres at the end (never Double, which would drift).
 */
object Interest {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val DAYS_PER_YEAR = 365
    private val MC = MathContext(24)

    /** Whole-day index for a timestamp (UTC midnight buckets). */
    fun dayIndex(timestamp: Long): Long = Math.floorDiv(timestamp, DAY_MS)

    /** Net principal currently placed under a label: deposits minus withdrawals, in piastres. */
    fun netPrincipalMinor(txns: List<Txn>): Long =
        txns.sumOf { if (it.type == TxnType.DEPOSIT) it.amountMinor else -it.amountMinor }

    /** The rate (bps) in effect on [day], i.e. the latest change effective on or before it. */
    fun rateBpsOnDay(day: Long, rateChanges: List<RateChange>): Int {
        if (rateChanges.isEmpty()) return SavingsData.DEFAULT_RATE_BPS
        val onOrBefore = rateChanges.filter { dayIndex(it.effectiveTimestamp) <= day }
        val chosen = onOrBefore.maxByOrNull { dayIndex(it.effectiveTimestamp) }
        // Before the earliest change, fall back to that earliest rate.
            ?: rateChanges.minByOrNull { dayIndex(it.effectiveTimestamp) }
        return chosen?.annualRateBps ?: SavingsData.DEFAULT_RATE_BPS
    }

    /**
     * Current value (net principal + all compounded interest) of a label at [nowTs], in piastres.
     * Returns 0 for an empty label.
     */
    fun valueMinor(txns: List<Txn>, rateChanges: List<RateChange>, nowTs: Long): Long {
        if (txns.isEmpty()) return 0L
        val nowDay = dayIndex(nowTs)

        // Collapse transactions to a net signed amount per day.
        val netByDay = HashMap<Long, Long>()
        for (t in txns) {
            val day = dayIndex(t.timestamp)
            val signed = if (t.type == TxnType.DEPOSIT) t.amountMinor else -t.amountMinor
            netByDay[day] = (netByDay[day] ?: 0L) + signed
        }

        val firstDay = netByDay.keys.min()
        // Before the first deposit ever, there is no money yet.
        if (nowDay < firstDay) return 0L
        // On the very first day, nothing has compounded yet: just that day's net.
        if (nowDay == firstDay) return (netByDay[firstDay] ?: 0L).coerceAtLeast(0L)

        // Segment boundaries: any day after the first where the balance or the rate changes,
        // plus today. Between two boundaries the rate is constant, so we compound in one step.
        val boundaries = sortedSetOf<Long>()
        boundaries += netByDay.keys.filter { it in (firstDay + 1)..nowDay }
        boundaries += rateChanges.map { dayIndex(it.effectiveTimestamp) }.filter { it in (firstDay + 1)..nowDay }
        boundaries += nowDay

        var balance = BigDecimal(netByDay[firstDay] ?: 0L)
        var prevDay = firstDay
        for (day in boundaries) {
            val elapsed = (day - prevDay).toInt()
            if (elapsed > 0) {
                val rateBps = rateBpsOnDay(prevDay, rateChanges)
                val dailyFactor = BigDecimal.ONE.add(
                    BigDecimal(rateBps)
                        .divide(BigDecimal(10_000), MC)
                        .divide(BigDecimal(DAYS_PER_YEAR), MC)
                )
                balance = balance.multiply(dailyFactor.pow(elapsed, MC), MC)
            }
            balance = balance.add(BigDecimal(netByDay[day] ?: 0L))
            prevDay = day
        }
        return balance.setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /** Interest earned on a label so far: current value minus net principal, in piastres. */
    fun interestMinor(txns: List<Txn>, rateChanges: List<RateChange>, nowTs: Long): Long =
        valueMinor(txns, rateChanges, nowTs) - netPrincipalMinor(txns)

    /**
     * Interest earned strictly between [fromTs] and [toTs]. The value grew by
     * value(to) - value(from); stripping out any deposits/withdrawals made in that window leaves
     * just the interest. Used for "earned this month / this year".
     */
    fun interestEarnedBetween(
        txns: List<Txn>,
        rateChanges: List<RateChange>,
        fromTs: Long,
        toTs: Long,
    ): Long {
        if (toTs <= fromTs) return 0
        val grew = valueMinor(txns, rateChanges, toTs) - valueMinor(txns, rateChanges, fromTs)
        val contributedInWindow = txns
            .filter { it.timestamp > fromTs && it.timestamp <= toTs }
            .sumOf { if (it.type == TxnType.DEPOSIT) it.amountMinor else -it.amountMinor }
        return grew - contributedInWindow
    }
}
