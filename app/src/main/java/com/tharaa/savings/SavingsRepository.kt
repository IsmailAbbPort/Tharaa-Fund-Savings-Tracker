package com.tharaa.savings

import android.content.Context
import com.backupkit.BackupManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for the savings ledger. It is an `object` (singleton) so the whole UI
 * reads/writes the same in-memory state, persisted to one encrypted JSON file. Writes are
 * synchronized; the app runs in a single process so this is safe.
 */
object SavingsRepository {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private lateinit var file: File
    private val lock = Any()

    /** Held so background mutations can refresh the home-screen widget. Null until [init]. */
    private var appContext: Context? = null

    private val _data = MutableStateFlow(SavingsData())
    val data: StateFlow<SavingsData> = _data.asStateFlow()

    /** In-memory only: whether the user has passed the app lock this process. Resets on cold start. */
    private val _sessionUnlocked = MutableStateFlow(false)
    val sessionUnlocked: StateFlow<Boolean> = _sessionUnlocked.asStateFlow()

    fun markUnlocked() { _sessionUnlocked.value = true }

    /**
     * Also drops the pending session. Behind the lock screen nothing reports where the user is, so
     * keeping it would let each background re-stamp a long-dead draft and push its expiry out
     * forever. The copy already on disk keeps its original timestamp and ages out honestly.
     */
    fun lockSession() {
        _sessionUnlocked.value = false
        pendingSession = null
    }

    // When we deliberately send the user to a system screen (a file picker, a permission prompt),
    // we don't want the onStop re-lock to bounce them to the passcode on the way back.
    @Volatile private var skipNextLock = false
    fun suppressNextLock() { skipNextLock = true }
    fun consumeSkipLock(): Boolean { val s = skipNextLock; skipNextLock = false; return s }

    /** How long a trip to a system screen may last before it stops counting as "still in the app". */
    const val EXCURSION_GRACE_MS = 60_000L

    @Volatile private var excursionStartedAt = 0L

    fun beginSystemExcursion(nowTs: Long = System.currentTimeMillis()) { excursionStartedAt = nowTs }

    /**
     * Called when the app comes back to the foreground. Skipping the lock for a quick hop to the
     * file picker is fine; skipping it because the user walked away from the picker and came back
     * an hour later is not, and that used to leave the balances open to anyone holding the phone.
     */
    fun endSystemExcursion(nowTs: Long = System.currentTimeMillis()) {
        val startedAt = excursionStartedAt
        excursionStartedAt = 0L
        if (startedAt != 0L && nowTs - startedAt > EXCURSION_GRACE_MS) lockSession()
    }

    /**
     * Set when a stored file exists that we could not read. While it is true the app refuses to
     * write, so the unreadable file stays put and a backup can still rescue it. Losing the ledger
     * to a silent overwrite is far worse than refusing to run.
     */
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** True when the last write didn't reach disk (full disk, Keystore trouble). Shown in the UI. */
    private val _writeFailed = MutableStateFlow(false)
    val writeFailed: StateFlow<Boolean> = _writeFailed.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (::file.isInitialized) return
            file = File(context.applicationContext.filesDir, "savings.enc")
            val decrypted = try {
                SecureStore.readString(file)
            } catch (e: SecureStore.Unreadable) {
                _loadFailed.value = true
                return
            }
            if (decrypted == null) {
                // Genuinely the first run: seed the file.
                persist(_data.value)
                return
            }
            val parsed = runCatching { json.decodeFromString<SavingsData>(decrypted) }.getOrNull()
            if (parsed == null) {
                // It decrypted but isn't the shape we expect. Same rule: don't write over it.
                _loadFailed.value = true
                return
            }
            _data.value = migrateLegacy(parsed)
        }
    }

    /**
     * Folds the old two-label build's custom names/goals into the new [SavingsData.labels] list, so
     * upgrading doesn't lose them. Only fires when the file still carries the seeded default labels.
     */
    internal fun migrateLegacy(d: SavingsData): SavingsData {
        val hasLegacy = d.seriousName != null || d.funName != null ||
            d.seriousGoalMinor != 0L || d.funGoalMinor != 0L
        if (!hasLegacy) return d
        val labels = if (d.labels == SavingsData.DEFAULT_LABELS) {
            listOf(
                SavingsLabel(SavingsData.SERIOUS_ID, d.seriousName ?: "Serious savings", d.seriousGoalMinor),
                SavingsLabel(SavingsData.FUN_ID, d.funName ?: "Fun saving", d.funGoalMinor),
            )
        } else d.labels
        return d.copy(labels = labels, seriousName = null, funName = null,
            seriousGoalMinor = 0, funGoalMinor = 0)
    }

    // ---- reads ----------------------------------------------------------------

    fun txnsFor(labelId: String, d: SavingsData = _data.value): List<Txn> =
        d.txns.filter { it.labelId == labelId }

    fun valueMinor(labelId: String, nowTs: Long, d: SavingsData = _data.value): Long =
        Interest.valueMinor(txnsFor(labelId, d), d.rateChanges, nowTs)

    fun netPrincipalMinor(labelId: String, d: SavingsData = _data.value): Long =
        Interest.netPrincipalMinor(txnsFor(labelId, d))

    fun interestMinor(labelId: String, nowTs: Long, d: SavingsData = _data.value): Long =
        Interest.interestMinor(txnsFor(labelId, d), d.rateChanges, nowTs)

    // ---- mutations ------------------------------------------------------------

    fun addTxn(labelId: String, type: TxnType, amountMinor: Long, timestamp: Long, note: String) =
        synchronized(lock) {
            val entry = Txn(labelId = labelId, type = type, amountMinor = amountMinor,
                timestamp = timestamp, note = note)
            val next = _data.value.copy(txns = (_data.value.txns + entry).sortedBy { it.timestamp })
            _data.value = next
            persist(next)
        }

    fun updateTxn(id: String, amountMinor: Long, timestamp: Long, note: String) = synchronized(lock) {
        val next = _data.value.copy(
            txns = _data.value.txns
                .map { if (it.id == id) it.copy(amountMinor = amountMinor, timestamp = timestamp, note = note) else it }
                .sortedBy { it.timestamp }
        )
        _data.value = next
        persist(next)
    }

    fun deleteTxn(id: String) = synchronized(lock) {
        val next = _data.value.copy(txns = _data.value.txns.filterNot { it.id == id })
        _data.value = next
        persist(next)
    }

    /** Average net contribution per month over the last [months] months (deposits minus withdrawals). */
    fun recentMonthlyPaceMinor(labelId: String, nowTs: Long, months: Int = 6, d: SavingsData = _data.value): Long {
        val windowStart = nowTs - months * 30L * 24 * 60 * 60 * 1000
        val net = txnsFor(labelId, d)
            .filter { it.timestamp in windowStart..nowTs }
            .sumOf { if (it.type == TxnType.DEPOSIT) it.amountMinor else -it.amountMinor }
        return net / months
    }

    /** Record a new annual rate (bps) effective from [effectiveTimestamp]. Old interest is untouched. */
    fun setRate(annualRateBps: Int, effectiveTimestamp: Long) = synchronized(lock) {
        val change = RateChange(
            effectiveTimestamp = effectiveTimestamp,
            annualRateBps = annualRateBps.coerceAtLeast(0),
        )
        val next = _data.value.copy(
            rateChanges = (_data.value.rateChanges + change).sortedBy { it.effectiveTimestamp }
        )
        _data.value = next
        persist(next)
    }

    /**
     * Removes a scheduled rate. Refuses the earliest entry: it anchors every day before the next
     * change, so deleting it would silently recompute the whole history at a later rate.
     */
    fun deleteRateChange(id: String) = synchronized(lock) {
        val all = _data.value.rateChanges
        if (all.minByOrNull { it.effectiveTimestamp }?.id == id) return
        val remaining = all.filterNot { it.id == id }
        // Never leave the schedule empty, or interest has no rate to use.
        if (remaining.isEmpty()) return
        val next = _data.value.copy(rateChanges = remaining)
        _data.value = next
        persist(next)
    }

    // ---- app lock (passcode + biometric) --------------------------------------

    fun setPasscode(pin: String) = synchronized(lock) {
        val salt = PasscodeCrypto.newSalt()
        val next = _data.value.copy(
            passcodeSalt = salt,
            passcodeHash = PasscodeCrypto.hash(pin, salt),
        )
        _data.value = next
        persist(next)
        _sessionUnlocked.value = true // they set it while already inside; don't lock them out
    }

    fun verifyPasscode(pin: String): Boolean {
        val d = _data.value
        val hash = d.passcodeHash ?: return false
        val salt = d.passcodeSalt ?: return false
        return PasscodeCrypto.verify(pin, salt, hash)
    }

    fun clearPasscode() = synchronized(lock) {
        val next = _data.value.copy(passcodeHash = null, passcodeSalt = null, biometricEnabled = false)
        _data.value = next
        persist(next)
    }

    fun setBiometricEnabled(on: Boolean) = synchronized(lock) {
        val next = _data.value.copy(biometricEnabled = on)
        _data.value = next
        persist(next)
    }

    // ---- labels + goals -------------------------------------------------------

    /** Create a new label and return its id. */
    fun addLabel(name: String): String = synchronized(lock) {
        val label = SavingsLabel(name = name.trim().ifBlank { "New label" })
        val next = _data.value.copy(labels = _data.value.labels + label)
        _data.value = next
        persist(next)
        label.id
    }

    fun renameLabel(id: String, name: String) = synchronized(lock) {
        val clean = name.trim().ifBlank { return@synchronized }
        val next = _data.value.copy(
            labels = _data.value.labels.map { if (it.id == id) it.copy(name = clean) else it }
        )
        _data.value = next
        persist(next)
    }

    /** Remove a label and all of its transactions. Refuses to delete the last remaining label. */
    fun deleteLabel(id: String) = synchronized(lock) {
        if (_data.value.labels.size <= 1) return@synchronized
        val next = _data.value.copy(
            labels = _data.value.labels.filterNot { it.id == id },
            txns = _data.value.txns.filterNot { it.labelId == id },
        )
        _data.value = next
        persist(next)
    }

    fun setGoal(labelId: String, goalMinor: Long) = synchronized(lock) {
        val clamped = goalMinor.coerceAtLeast(0)
        val next = _data.value.copy(
            labels = _data.value.labels.map { if (it.id == labelId) it.copy(goalMinor = clamped) else it }
        )
        _data.value = next
        persist(next)
    }

    // ---- projection presets ---------------------------------------------------

    fun addPreset(preset: ProjectionPreset) = synchronized(lock) {
        val next = _data.value.copy(presets = _data.value.presets + preset)
        _data.value = next
        persist(next)
    }

    fun deletePreset(id: String) = synchronized(lock) {
        val next = _data.value.copy(presets = _data.value.presets.filterNot { it.id == id })
        _data.value = next
        persist(next)
    }

    // ---- last-open session ----------------------------------------------------

    /** The screen/draft last reported by the UI. Reaches disk only via [saveSession]. */
    @Volatile private var pendingSession: UiSession? = null

    fun noteSession(session: UiSession) { pendingSession = session }

    /**
     * Writes where the user was, so reopening lands there. Called as the app leaves the foreground
     * rather than on every keystroke, so a half-typed projection costs one file write, not hundreds.
     */
    fun saveSession(nowTs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val session = pendingSession ?: return@synchronized
        val next = _data.value.copy(session = session.copy(savedAt = nowTs))
        _data.value = next
        // A UI position is worth neither a widget refresh nor a cloud upload.
        persistBlocking(next, notifyObservers = false)
    }

    /** The stored session while it is still fresh; null once it has gone stale or never existed. */
    fun restorableSession(nowTs: Long = System.currentTimeMillis(), d: SavingsData = _data.value): UiSession? =
        d.session?.takeIf { it.isRestorableAt(nowTs) }

    // ---- backup / restore -----------------------------------------------------

    /**
     * JSON backup of the ledger. A backup carries data, not device settings: the screen the user
     * was on, and the passcode, stay behind. The passcode especially - it is four digits, so
     * shipping its hash in a blob that lands on Drive puts the PIN one offline sweep away.
     */
    fun exportJson(): String =
        json.encodeToString(_data.value.copy(session = null, passcodeHash = null, passcodeSalt = null))

    /** Replace all data from a JSON backup. Returns false if the text isn't valid. */
    fun importJson(text: String): Boolean = synchronized(lock) {
        val decoded = runCatching { json.decodeFromString<SavingsData>(text) }.getOrNull() ?: return false
        // A backup can be older than the file on disk, so it needs the same migration init does.
        val parsed = migrateLegacy(decoded)
        val current = _data.value
        val next = parsed.copy(
            // Never open on a draft from another device.
            session = null,
            // The app lock belongs to this phone, not to the blob. Without this, restoring a
            // backup (which no longer carries a passcode) would quietly switch the lock off.
            passcodeHash = current.passcodeHash,
            passcodeSalt = current.passcodeSalt,
            biometricEnabled = current.biometricEnabled,
        )
        _data.value = next
        persist(next)
        appContext?.let { ReminderScheduler.apply(it, next.reminderEnabled, next.reminderDayOfMonth) }
        true
    }

    /** Human-readable CSV of every transaction (for a spreadsheet); not used for restore. */
    fun transactionsCsv(d: SavingsData = _data.value): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val rows = StringBuilder("date,label,type,amount_egp,note\n")
        d.txns.sortedBy { it.timestamp }.forEach { t ->
            val label = d.labelById(t.labelId)?.name ?: t.labelId
            val type = if (t.type == TxnType.DEPOSIT) "deposit" else "withdrawal"
            rows.append(fmt.format(java.util.Date(t.timestamp))).append(',')
                .append(esc(label)).append(',')
                .append(type).append(',')
                .append(Money.formatMinor(t.amountMinor)).append(',')
                .append(esc(t.note)).append('\n')
        }
        return rows.toString()
    }

    // ---- reminders ------------------------------------------------------------

    fun setReminder(enabled: Boolean, dayOfMonth: Int) = synchronized(lock) {
        val next = _data.value.copy(
            reminderEnabled = enabled,
            reminderDayOfMonth = dayOfMonth.coerceIn(1, 28),
        )
        _data.value = next
        persist(next)
        appContext?.let { ReminderScheduler.apply(it, next.reminderEnabled, next.reminderDayOfMonth) }
    }

    /**
     * One thread for every write, so encrypting the ledger never happens on the main thread and
     * writes still land in the order they were made. The in-memory [data] flow is updated first
     * and synchronously, so the UI never waits on the disk.
     */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "savings-io").apply { isDaemon = true }
    }

    private fun persist(d: SavingsData, notifyObservers: Boolean = true) {
        // Never write over a file we failed to read: it may still be recoverable.
        if (_loadFailed.value) return
        io.execute { writeNow(d, notifyObservers) }
    }

    /**
     * Queues the write and waits for it. Used only from `onStop`: that is the moment the process
     * is most likely to be killed, so deferring this particular write risks losing exactly the
     * thing we were trying to save.
     */
    private fun persistBlocking(d: SavingsData, notifyObservers: Boolean = true) {
        if (_loadFailed.value) return
        runCatching { io.submit { writeNow(d, notifyObservers) }.get(2, TimeUnit.SECONDS) }
    }

    private fun writeNow(d: SavingsData, notifyObservers: Boolean) {
        val written = runCatching { SecureStore.writeString(file, json.encodeToString(d)) }.isSuccess
        _writeFailed.value = !written
        if (!notifyObservers) return
        // Keep the home-screen widget (if any) in sync with the latest numbers.
        runCatching { TharaaWidget.refresh(appContext) }
        // If cloud auto-backup is on, schedule a debounced upload of the new state.
        runCatching { appContext?.let { BackupManager.onDataChanged(it) } }
    }
}
