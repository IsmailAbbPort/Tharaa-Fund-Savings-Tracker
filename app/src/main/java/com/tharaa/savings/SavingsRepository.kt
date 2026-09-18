package com.tharaa.savings

import android.content.Context
import com.backupkit.BackupManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

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
    fun lockSession() { _sessionUnlocked.value = false }

    // When we deliberately send the user to a system screen (e.g. a fingerprint prompt), we don't
    // want the onStop re-lock to bounce them to the passcode. This one-shot flag skips it.
    @Volatile private var skipNextLock = false
    fun suppressNextLock() { skipNextLock = true }
    fun consumeSkipLock(): Boolean { val s = skipNextLock; skipNextLock = false; return s }

    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (::file.isInitialized) return
            file = File(context.applicationContext.filesDir, "savings.enc")
            val decrypted = SecureStore.readString(file)
            if (decrypted != null) {
                runCatching { json.decodeFromString<SavingsData>(decrypted) }
                    .onSuccess { _data.value = migrateLegacy(it) }
            } else {
                persist(_data.value)
            }
        }
    }

    /**
     * Folds the old two-label build's custom names/goals into the new [SavingsData.labels] list, so
     * upgrading doesn't lose them. Only fires when the file still carries the seeded default labels.
     */
    private fun migrateLegacy(d: SavingsData): SavingsData {
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

    fun deleteRateChange(id: String) = synchronized(lock) {
        val remaining = _data.value.rateChanges.filterNot { it.id == id }
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

    // ---- backup / restore -----------------------------------------------------

    /** Full-fidelity JSON backup (everything, restorable). */
    fun exportJson(): String = json.encodeToString(_data.value)

    /** Replace all data from a JSON backup. Returns false if the text isn't valid. */
    fun importJson(text: String): Boolean = synchronized(lock) {
        val parsed = runCatching { json.decodeFromString<SavingsData>(text) }.getOrNull() ?: return false
        _data.value = parsed
        persist(parsed)
        appContext?.let { ReminderScheduler.apply(it, parsed.reminderEnabled, parsed.reminderDayOfMonth) }
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

    private fun persist(d: SavingsData) {
        runCatching { SecureStore.writeString(file, json.encodeToString(d)) }
        // Keep the home-screen widget (if any) in sync with the latest numbers.
        runCatching { TharaaWidget.refresh(appContext) }
        // If cloud auto-backup is on, schedule a debounced upload of the new state.
        runCatching { appContext?.let { BackupManager.onDataChanged(it) } }
    }
}
