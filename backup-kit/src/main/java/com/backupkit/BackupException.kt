package com.backupkit

/** Thrown when a blob can't be decrypted: wrong passphrase, tampering, or an unrecognised format. */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)
