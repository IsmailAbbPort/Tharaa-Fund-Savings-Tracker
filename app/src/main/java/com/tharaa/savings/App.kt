package com.tharaa.savings

import android.app.Application
import com.backupkit.BackupManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SavingsRepository.init(this)
        BackupManager.init(this, TharaaBackupSource)
        Notifications.ensureChannel(this)
        // Re-arm the reminder in case this is a cold start (e.g. after the process was killed).
        ReminderScheduler.scheduleFromData(this)
    }
}
