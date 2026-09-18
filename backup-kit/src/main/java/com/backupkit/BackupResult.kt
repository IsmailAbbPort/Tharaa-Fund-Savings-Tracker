package com.backupkit

/** Outcome of a backup or restore attempt. */
sealed class BackupResult {
    data object Success : BackupResult()
    data class Error(val message: String) : BackupResult()
}
