package com.backupkit

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The one entry point apps use. Wire it up once with [init], then drive it from your UI:
 * sign in, set a passphrase, [backupNow]/[restoreLatest], and toggle [setAutoEnabled]. With auto
 * on, call [onDataChanged] after each data change and the kit debounces a background upload.
 *
 * Backups land in Drive under "Auto Backups/<projectId>/", newest kept, oldest pruned.
 */
object BackupManager {

    private const val PREFS = "backupkit"
    private const val KEY_AUTO = "auto_enabled"
    private const val KEY_LAST = "last_backup"
    private const val ROOT_FOLDER = "Auto Backups"
    private const val KEEP_VERSIONS = 10
    private const val AUTO_WORK = "backupkit_auto"
    private const val DEBOUNCE_SECONDS = 8L

    private lateinit var source: BackupSource
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context, source: BackupSource) {
        this.source = source
    }

    // ---- sign-in (delegate to GoogleAccountAuth) ----
    fun signInIntent(context: Context): Intent = GoogleAccountAuth.signInIntent(context)
    fun handleSignInResult(intent: Intent?): Boolean = GoogleAccountAuth.handleSignInResult(intent)
    fun isSignedIn(context: Context): Boolean = GoogleAccountAuth.lastAccount(context) != null
    fun signedInEmail(context: Context): String? = GoogleAccountAuth.signedInEmail(context)
    fun signOut(context: Context, onDone: () -> Unit) = GoogleAccountAuth.signOut(context, onDone)

    // ---- passphrase ----
    fun hasPassphrase(context: Context): Boolean = KeyVault.hasPassphrase(context)
    fun setPassphrase(context: Context, passphrase: String) = KeyVault.setPassphrase(context, passphrase)
    fun clearPassphrase(context: Context) = KeyVault.clear(context)

    // ---- auto-backup preference + status ----
    fun isAutoEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, false)
    fun setAutoEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()

    /** Millis of the last successful backup, or 0 if never. */
    fun lastBackup(context: Context): Long = prefs(context).getLong(KEY_LAST, 0L)

    fun isReady(context: Context): Boolean = isSignedIn(context) && hasPassphrase(context)

    // ---- manual (async, result posted on the main thread) ----
    fun backupNow(context: Context, onResult: (BackupResult) -> Unit) {
        val app = context.applicationContext
        io.execute { val r = backupNowBlocking(app); main.post { onResult(r) } }
    }

    fun restoreLatest(context: Context, onResult: (BackupResult) -> Unit) {
        val app = context.applicationContext
        io.execute { val r = restoreLatestBlocking(app); main.post { onResult(r) } }
    }

    /** Debounced auto-backup: after a data change, enqueue one upload (replacing any pending one). */
    fun onDataChanged(context: Context) {
        if (!isAutoEnabled(context) || !isReady(context)) return
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(AUTO_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    // ---- blocking cores (called on a worker/executor thread) ----
    internal fun backupNowBlocking(context: Context): BackupResult {
        if (!::source.isInitialized) return BackupResult.Error("Backup not set up")
        val account = GoogleAccountAuth.lastAccount(context) ?: return BackupResult.Error("Not signed in")
        val passphrase = KeyVault.getPassphrase(context) ?: return BackupResult.Error("No passphrase set")
        return try {
            val token = GoogleAccountAuth.fetchToken(context, account)
            val store = DriveBackupStore(token)
            val folder = store.ensureFolder(source.projectId, store.ensureFolder(ROOT_FOLDER))
            val blob = BackupCrypto.encrypt(source.export(), passphrase.toCharArray())
            store.upload(folder, "${source.projectId}-${stamp()}.tbk", blob)
            store.list(folder).drop(KEEP_VERSIONS).forEach { runCatching { store.delete(it.id) } }
            prefs(context).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            BackupResult.Success
        } catch (e: Exception) {
            BackupResult.Error(e.message ?: "Backup failed")
        }
    }

    internal fun restoreLatestBlocking(context: Context): BackupResult {
        if (!::source.isInitialized) return BackupResult.Error("Backup not set up")
        val account = GoogleAccountAuth.lastAccount(context) ?: return BackupResult.Error("Not signed in")
        val passphrase = KeyVault.getPassphrase(context) ?: return BackupResult.Error("No passphrase set")
        return try {
            val token = GoogleAccountAuth.fetchToken(context, account)
            val store = DriveBackupStore(token)
            val folder = store.ensureFolder(source.projectId, store.ensureFolder(ROOT_FOLDER))
            val latest = store.list(folder).firstOrNull()
                ?: return BackupResult.Error("No backup found in Drive")
            val plain = BackupCrypto.decrypt(store.download(latest.id), passphrase.toCharArray())
            source.restore(plain)
            BackupResult.Success
        } catch (e: Exception) {
            BackupResult.Error(e.message ?: "Restore failed")
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
