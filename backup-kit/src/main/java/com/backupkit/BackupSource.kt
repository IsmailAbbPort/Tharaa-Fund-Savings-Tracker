package com.backupkit

/**
 * The one thing each app implements to plug into backup-kit. The kit handles encryption, cloud
 * storage, versioning and scheduling; the app only says what its data is and how to put it back.
 *
 * Keep [export]/[restore] symmetric: whatever bytes [export] returns must be exactly what
 * [restore] accepts. The format is entirely the app's business (JSON, protobuf, a zip - anything).
 */
interface BackupSource {
    /** Stable id namespacing this app's backups in storage, e.g. "tharaa" or "instabalance". */
    val projectId: String

    /** The current data to back up, as raw bytes. */
    fun export(): ByteArray

    /** Replace the app's data with a restored backup's bytes. */
    fun restore(bytes: ByteArray)
}
