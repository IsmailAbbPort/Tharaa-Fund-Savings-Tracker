package com.tharaa.savings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires the monthly reminder notification and re-arms next month's alarm. Also re-schedules after
 * a reboot (alarms don't survive a restart on their own).
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SavingsRepository.init(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderScheduler.scheduleFromData(context)
            return
        }
        Notifications.showDepositReminder(context)
        val d = SavingsRepository.data.value
        if (d.reminderEnabled) ReminderScheduler.schedule(context, d.reminderDayOfMonth)
    }
}
