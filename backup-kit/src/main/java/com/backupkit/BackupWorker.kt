package com.backupkit

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Runs a debounced auto-backup in the background. Retries on transient failures only. */
class BackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result = when (val result = BackupManager.backupNowBlocking(applicationContext)) {
        is BackupResult.Success -> Result.success()
        // Retrying a revoked token or a missing passphrase just wakes the device to fail again,
        // forever and invisibly. Give up on those, and give up on a transient one that has
        // already had its chances.
        is BackupResult.Error ->
            if (result.permanent || runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
