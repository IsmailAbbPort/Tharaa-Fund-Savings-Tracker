package com.tharaa.savings.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.backupkit.BackupResult
import com.tharaa.savings.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

/** Keeps only digits and a single decimal point, so money fields accept numbers only. */
internal fun sanitizeAmount(input: String): String {
    val filtered = input.filter { it.isDigit() || it == '.' }
    val dot = filtered.indexOf('.')
    return if (dot == -1) filtered
    else filtered.substring(0, dot + 1) + filtered.substring(dot + 1).replace(".", "")
}

/** Raw (comma-free) money string for prefilling a field whose display adds its own commas. */
internal fun moneyRaw(minor: Long): String = Money.formatMinor(minor).filter { it.isDigit() || it == '.' }

/** Human ETA from the goal engine, using the recent contribution pace. */
internal fun goalEtaText(labelId: String, valueMinor: Long, goalMinor: Long, data: SavingsData, now: Long): String {
    if (valueMinor >= goalMinor) return "Goal reached. Nice."
    val pace = SavingsRepository.recentMonthlyPaceMinor(labelId, now, 6, data)
    val months = Projection.monthsToReach(valueMinor, goalMinor, data.currentRateBps, pace)
        ?: return "Not reachable at your recent pace of ${Money.formatMinor(pace)}/mo. Deposit more to get there."
    val eta = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, months) }
    return "About $months ${if (months == 1) "month" else "months"} (~${monthYearFmt.format(eta.time)}) at ${Money.formatMinor(pace)}/mo."
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

/** 1800 bps -> "18", 1850 -> "18.5". Trims trailing zeros so rates read cleanly. */
internal fun formatPercent(bps: Int): String {
    val pct = bps / 100.0
    return if (pct == pct.toLong().toDouble()) pct.toLong().toString()
    else pct.toString().trimEnd('0').trimEnd('.')
}

/** "18" or "18.5" -> basis points (1800 / 1850). Null if blank/unparseable, so a default applies. */
internal fun parsePercentToBps(text: String): Int? =
    text.trim().toDoubleOrNull()?.let { (it * 100).roundToInt() }

/** Compact human duration: 42 -> "3y 6m", 60 -> "5y", 8 -> "8m". */
internal fun durationLabel(months: Int): String {
    val y = months / 12
    val m = months % 12
    return when {
        y > 0 && m > 0 -> "${y}y ${m}m"
        y > 0 -> "${y}y"
        else -> "${m}m"
    }
}

/** Shrinks the headline money font as the amount grows so "<number> EGP" stays on one line. */
internal fun adaptiveMoneySize(minor: Long, base: Float): TextUnit {
    val egp = kotlin.math.abs(minor) / 100
    val scale = when {
        egp < 1_000_000 -> 1.0f          // up to 999,999.99
        egp < 10_000_000 -> 0.82f        // millions
        egp < 100_000_000 -> 0.66f       // tens of millions
        egp < 1_000_000_000 -> 0.54f     // hundreds of millions
        else -> 0.44f                    // billions+
    }
    return (base * scale).sp
}

/** 1_234_567_89 piastres -> "12.3M" for compact chart axis labels. */
internal fun compactEgp(minor: Long): String {
    val egp = minor / 100.0
    val abs = kotlin.math.abs(egp)
    return when {
        abs >= 1_000_000_000 -> "%.1fB".format(egp / 1_000_000_000)
        abs >= 1_000_000 -> "%.1fM".format(egp / 1_000_000)
        abs >= 1_000 -> "%.0fk".format(egp / 1_000)
        else -> "%.0f".format(egp)
    }
}

/**
 * Adds thousands separators to a numeric text field for display, while the stored value stays raw
 * digits (so parsing is unaffected). Groups only the integer part, before any decimal point.
 */
internal object ThousandsTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val dot = raw.indexOf('.')
        val intPart = if (dot == -1) raw else raw.substring(0, dot)
        val rest = if (dot == -1) "" else raw.substring(dot) // includes the dot
        val grouped = if (intPart.isEmpty()) "" else
            intPart.reversed().chunked(3).joinToString(",").reversed()
        val out = grouped + rest

        fun commasBefore(o: Int): Int {
            var c = 0
            for (j in 1..o) if (j < intPart.length && (intPart.length - j) % 3 == 0) c++
            return c
        }

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val o = offset.coerceIn(0, raw.length)
                return if (o <= intPart.length) o + commasBefore(o)
                else o + grouped.count { it == ',' }
            }
            override fun transformedToOriginal(offset: Int): Int {
                val t = offset.coerceIn(0, out.length)
                val commas = out.take(t).count { it == ',' }
                return (t - commas).coerceIn(0, raw.length)
            }
        }
        return TransformedText(AnnotatedString(out), mapping)
    }
}

// Dates are chosen/stored as UTC-midnight day buckets (see Interest.dayIndex), so format them in
// UTC too, otherwise a late-evening local time could show the previous day.
internal val dateOnlyFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

internal val monthYearFmt = SimpleDateFormat("MMM yyyy", Locale.getDefault())
internal val dateTimeFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

/** Toast text for a backup/restore result: the success message, or the error detail. */
internal fun resultText(result: BackupResult, successMessage: String): String = when (result) {
    is BackupResult.Success -> successMessage
    is BackupResult.Error -> result.message
}

internal fun startOfMonth(now: Long): Long = Calendar.getInstance().apply {
    timeInMillis = now
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

internal fun startOfYear(now: Long): Long = Calendar.getInstance().apply {
    timeInMillis = now
    set(Calendar.DAY_OF_YEAR, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

