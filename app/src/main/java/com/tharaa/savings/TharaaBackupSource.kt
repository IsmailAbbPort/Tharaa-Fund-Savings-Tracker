package com.tharaa.savings

import com.backupkit.BackupSource

/** Tharaa's plug into backup-kit: its data is the ledger JSON the repository already exports. */
object TharaaBackupSource : BackupSource {
    override val projectId: String = "tharaa"

    override fun export(): ByteArray =
        SavingsRepository.exportJson().toByteArray(Charsets.UTF_8)

    override fun restore(bytes: ByteArray) {
        SavingsRepository.importJson(String(bytes, Charsets.UTF_8))
    }
}
