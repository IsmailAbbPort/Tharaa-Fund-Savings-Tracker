package com.tharaa.savings

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Schedules the monthly deposit reminder. Months vary in length, so instead of a fixed repeat we
 * set a one-shot alarm for the next occurrence and re-arm it when it fires (and on reboot).
 *
 * Uses an inexact allow-while-idle alarm, which needs no special permission - a reminder that lands
 * within a window of the chosen morning is perfectly fine.
 */
object ReminderScheduler {
    const val ACTION_REMIND = "com.tharaa.savings.action.REMIND"
    private const val REQUEST = 7001
    private const val HOUR_OF_DAY = 10

    fun apply(context: Context, enabled: Boolean, dayOfMonth: Int) {
        if (enabled) schedule(context, dayOfMonth) else cancel(context)
    }

    fun scheduleFromData(context: Context) {
        val d = SavingsRepository.data.value
        apply(context, d.reminderEnabled, d.reminderDayOfMonth)
    }

    fun schedule(context: Context, dayOfMonth: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger(dayOfMonth), pendingIntent(context))
    }

    fun cancel(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND)
        return PendingIntent.getBroadcast(
            context, REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Next time (>= [from]) the [dayOfMonth] falls, at 10:00 local. Pure, so it is unit-tested. */
    fun nextTrigger(dayOfMonth: Int, from: Long = System.currentTimeMillis()): Long {
        fun atDay(cal: Calendar) {
            val clamped = dayOfMonth.coerceIn(1, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
            cal.set(Calendar.DAY_OF_MONTH, clamped)
            cal.set(Calendar.HOUR_OF_DAY, HOUR_OF_DAY)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
        }
        val cal = Calendar.getInstance().apply { timeInMillis = from }
        atDay(cal)
        if (cal.timeInMillis <= from) {
            cal.add(Calendar.MONTH, 1)
            atDay(cal)
        }
        return cal.timeInMillis
    }
}
