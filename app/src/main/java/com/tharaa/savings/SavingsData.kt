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

/** The calculator's raw text inputs, exactly as typed. */
@Serializable
data class CalculatorDraft(
    val start: String = "",
    val rate: String = "",
    val deposit: String = "",
    val withdrawal: String = "",
    val yearlyIncrease: String = "",
    val years: String = "",
    val months: String = "",
)

/**
 * Where the user was when the app last left the foreground, plus any calculator inputs they hadn't
 * saved as a preset. Reopening restores it only while it is fresh ([RESTORE_WINDOW_MS]); after that
 * the app starts on Home so a stale half-typed projection doesn't greet you days later.
 */
@Serializable
data class UiSession(
    val screen: String,
    val labelId: String? = null,
    val calculator: CalculatorDraft? = null,
    val savedAt: Long = 0L,
) {
    /** False once the window has passed, and also for a future timestamp (clock moved backwards). */
    fun isRestorableAt(nowTs: Long): Boolean = nowTs - savedAt in 0..RESTORE_WINDOW_MS

    companion object {
        const val RESTORE_WINDOW_MS = 30L * 60 * 1000

        const val HOME = "home"
        const val DETAIL = "detail"
        const val CALCULATOR = "calculator"
    }
}

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
    val session: UiSession? = null,
    // Legacy fields from the two-label build, read only so older files can migrate (see
    // SavingsRepository.migrateLegacy). Not written going forward.
    val seriousName: String? = null,
    val funName: String? = null,
    val seriousGoalMinor: Long = 0,
    val funGoalMinor: Long = 0,
) {
    val hasPasscode: Boolean get() = passcodeHash != null && passcodeSalt != null

    /**
     * The rate actually accruing right now. A change the user dated in the future is scheduled,
     * not current: it must not be what Settings shows, what the calculator defaults to, or what a
     * goal ETA assumes, because [Interest] rightly keeps compounding at the old rate until it lands.
     */
    val currentRateBps: Int get() = rateBpsAt(System.currentTimeMillis())

    fun rateBpsAt(nowTs: Long): Int =
        Interest.rateBpsOnDay(Interest.dayIndex(nowTs), rateChanges)

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
