package com.tharaa.savings

/**
 * Forward projection for the "what will this grow into?" calculator.
 *
 * Unlike [Interest] (which values money that has actually moved), this is an estimate of the
 * future, so it steps month by month: each month the balance compounds, then that month's net
 * contribution (deposit minus withdrawal) is added. Compounding stays consistent with the rest of
 * the app - a month grows by (1 + rate/365) raised to the days in an average month - so a 12-month
 * projection with no contributions equals the app's one-year daily-compounded value.
 *
 * Being a forecast, the maths runs in Double and is rounded to piastres only for display. (Real,
 * recorded transactions still use exact integer money in [Interest]; a projection is inherently an
 * approximation, so Double is fine here and lets us use a fractional monthly exponent.)
 */
object Projection {

    private const val DAYS_PER_YEAR = 365.0
    private const val MONTHS_PER_YEAR = 12.0
    private const val MAX_MONTHS = 1200 // 100 years, a sanity cap

    /** One month on the timeline: the projected value, and the money put in so far (for the graph). */
    data class Point(val month: Int, val valueMinor: Long, val contributedMinor: Long)

    data class Result(
        val points: List<Point>,
        val startingMinor: Long,
        val finalValueMinor: Long,
        val totalDepositedMinor: Long,
        val totalWithdrawnMinor: Long,
        val interestMinor: Long,
    )

    /**
     * @param startMinor starting balance in piastres
     * @param annualRateBps annual rate in basis points (1800 = 18%)
     * @param monthlyDepositMinor recurring deposit added at the end of each month
     * @param monthlyWithdrawalMinor recurring withdrawal taken at the end of each month
     * @param months length of the timeline
     * @param yearlyContributionIncreaseBps optional escalation applied to the deposit and
     *        withdrawal after each full year (e.g. 1000 = they grow 10% a year)
     */
    fun project(
        startMinor: Long,
        annualRateBps: Int,
        monthlyDepositMinor: Long,
        monthlyWithdrawalMinor: Long,
        months: Int,
        yearlyContributionIncreaseBps: Int = 0,
    ): Result {
        val rate = annualRateBps / 10_000.0
        val monthlyFactor = Math.pow(1.0 + rate / DAYS_PER_YEAR, DAYS_PER_YEAR / MONTHS_PER_YEAR)
        val escalation = 1.0 + yearlyContributionIncreaseBps / 10_000.0
        val safeMonths = months.coerceIn(0, MAX_MONTHS)

        var balance = startMinor.toDouble()
        var deposit = monthlyDepositMinor.toDouble()
        var withdrawal = monthlyWithdrawalMinor.toDouble()
        var totalDeposited = 0.0
        var totalWithdrawn = 0.0

        val points = ArrayList<Point>(safeMonths + 1)
        points += Point(0, startMinor, startMinor)

        for (m in 1..safeMonths) {
            balance = balance * monthlyFactor + deposit - withdrawal
            totalDeposited += deposit
            totalWithdrawn += withdrawal
            val contributed = startMinor + totalDeposited - totalWithdrawn
            points += Point(m, Math.round(balance), Math.round(contributed))
            if (m % 12 == 0 && yearlyContributionIncreaseBps != 0) {
                deposit *= escalation
                withdrawal *= escalation
            }
        }

        val finalValue = Math.round(balance)
        val totDep = Math.round(totalDeposited)
        val totWd = Math.round(totalWithdrawn)
        return Result(
            points = points,
            startingMinor = startMinor,
            finalValueMinor = finalValue,
            totalDepositedMinor = totDep,
            totalWithdrawnMinor = totWd,
            interestMinor = finalValue - startMinor - totDep + totWd,
        )
    }

    /**
     * Months until [startMinor] grows to [goalMinor], adding [monthlyNetMinor] each month at
     * [annualRateBps]. Returns 0 if already there, or null if it never reaches within the cap
     * (e.g. no growth and no contributions, or net withdrawals shrinking it).
     */
    fun monthsToReach(
        startMinor: Long,
        goalMinor: Long,
        annualRateBps: Int,
        monthlyNetMinor: Long,
        maxMonths: Int = MAX_MONTHS,
    ): Int? {
        if (startMinor >= goalMinor) return 0
        val monthlyFactor = Math.pow(1.0 + (annualRateBps / 10_000.0) / DAYS_PER_YEAR, DAYS_PER_YEAR / MONTHS_PER_YEAR)
        var balance = startMinor.toDouble()
        for (m in 1..maxMonths) {
            balance = balance * monthlyFactor + monthlyNetMinor
            if (balance >= goalMinor) return m
        }
        return null
    }
}
