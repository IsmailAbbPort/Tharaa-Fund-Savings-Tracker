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
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            SavingsRepository.init(context)
            ReminderScheduler.scheduleFromData(context)
            return
        }
        // The receiver has to be exported for BOOT_COMPLETED, which means any app on the device can
        // send it an explicit intent. Only our own alarm action gets to raise a notification.
        if (intent.action != ReminderScheduler.ACTION_REMIND) return
        SavingsRepository.init(context)
        Notifications.showDepositReminder(context)
        val d = SavingsRepository.data.value
        if (d.reminderEnabled) ReminderScheduler.schedule(context, d.reminderDayOfMonth)
    }
}
