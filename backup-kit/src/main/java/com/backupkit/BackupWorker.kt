package com.backupkit

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Runs a debounced auto-backup in the background. Retries on transient failures. */
class BackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = when (BackupManager.backupNowBlocking(applicationContext)) {
        is BackupResult.Success -> Result.success()
        is BackupResult.Error -> Result.retry()
    }
}
