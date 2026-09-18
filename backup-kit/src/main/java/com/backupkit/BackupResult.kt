package com.backupkit

/** Outcome of a backup or restore attempt. */
sealed class BackupResult {
    data object Success : BackupResult()

    /**
     * [permanent] marks a failure that retrying cannot fix: not signed in, no passphrase, revoked
     * access. Transient ones (no network, a 5xx from Drive) are worth another go; permanent ones
     * retried forever just wake the device up to fail again.
     */
    data class Error(val message: String, val permanent: Boolean = false) : BackupResult()
}
