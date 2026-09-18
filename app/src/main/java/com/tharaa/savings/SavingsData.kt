package com.tharaa.savings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

enum class TxnType { DEPOSIT, WITHDRAWAL }

/**
 * A user-defined bucket of money inside the same Tharaa fund. The bank can't separate them, so we
 * do it here: every transaction is tagged with the id of the label it belongs to. The two seeded
 * labels use stable ids ("SERIOUS"/"FUN") so data written by earlier builds still maps cleanly.
 */
@Serializable
data class SavingsLabel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val goalMinor: Long = 0, // 0 = no goal
)

/**
 * One movement of money under a label.
 * - DEPOSIT: money added on [timestamp]; it starts earning interest from that day.
 * - WITHDRAWAL: money taken out; it stops compounding from that day.
 * [amountMinor] is always the positive size of the move, in piastres.
 *
 * The JSON key stays "label" (via @SerialName) so files from the enum-based version still load.
 */
@Serializable
data class Txn(
    val id: String = UUID.randomUUID().toString(),
    @SerialName("label") val labelId: String,
    val type: TxnType,
    val amountMinor: Long,
    val timestamp: Long,
    val note: String = "",
)

/** A point in time from which a new annual interest rate applies (basis points; 1800 = 18.00%). */
@Serializable
data class RateChange(
    val id: String = UUID.randomUUID().toString(),
    val effectiveTimestamp: Long,
    val annualRateBps: Int,
)

/** A saved "what-if" scenario for the calculator. */
@Serializable
data class ProjectionPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val startMinor: Long,
    val annualRateBps: Int,
    val monthlyDepositMinor: Long,
    val monthlyWithdrawalMinor: Long,
    val months: Int,
    val yearlyIncreaseBps: Int = 0,
)

/** Everything we persist, in one JSON file. */
@Serializable
data class SavingsData(
    val txns: List<Txn> = emptyList(),
    val labels: List<SavingsLabel> = DEFAULT_LABELS,
    val rateChanges: List<RateChange> = listOf(
        RateChange(effectiveTimestamp = 0L, annualRateBps = DEFAULT_RATE_BPS)
    ),
    val presets: List<ProjectionPreset> = emptyList(),
    val passcodeHash: String? = null,
    val passcodeSalt: String? = null,
    val biometricEnabled: Boolean = false,
    val reminderEnabled: Boolean = false,
    val reminderDayOfMonth: Int = 1,
    // Legacy fields from the two-label build, read only so older files can migrate (see
    // SavingsRepository.migrateLegacy). Not written going forward.
    val seriousName: String? = null,
    val funName: String? = null,
    val seriousGoalMinor: Long = 0,
    val funGoalMinor: Long = 0,
) {
    val hasPasscode: Boolean get() = passcodeHash != null && passcodeSalt != null

    val currentRateBps: Int
        get() = rateChanges.maxByOrNull { it.effectiveTimestamp }?.annualRateBps ?: DEFAULT_RATE_BPS

    fun labelById(id: String): SavingsLabel? = labels.find { it.id == id }

    companion object {
        const val DEFAULT_RATE_BPS = 1800 // 18.00%
        const val SERIOUS_ID = "SERIOUS"
        const val FUN_ID = "FUN"
        val DEFAULT_LABELS = listOf(
            SavingsLabel(id = SERIOUS_ID, name = "Serious savings"),
            SavingsLabel(id = FUN_ID, name = "Fun saving"),
        )
    }
}
