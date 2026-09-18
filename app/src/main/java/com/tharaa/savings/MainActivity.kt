package com.tharaa.savings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.backupkit.BackupCrypto
import com.backupkit.BackupManager
import com.backupkit.BackupResult
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mark the window secure: the OS won't snapshot it for the app switcher, and screenshots
        // of your balances are blocked.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        SavingsRepository.init(applicationContext)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Gate()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Remember the screen and any calculator inputs so reopening picks up where they left off.
        SavingsRepository.saveSession()
        // Re-lock the moment the app leaves the screen, so reopening shows the passcode first.
        // Guarded so a rotation/config change (or a fingerprint prompt we opened) doesn't lock.
        if (!isChangingConfigurations && !SavingsRepository.consumeSkipLock()) {
            SavingsRepository.lockSession()
        }
    }
}

private const val PIN_LENGTH = 4

// ---------------------------------------------------------------------------
// Gate: passcode wall in front of the app
// ---------------------------------------------------------------------------

@Composable
private fun Gate() {
    val data by SavingsRepository.data.collectAsStateWithLifecycle()
    val unlocked by SavingsRepository.sessionUnlocked.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? FragmentActivity

    if (!data.hasPasscode || unlocked) {
        AppRoot()
    } else {
        LockScreen(activity, data.biometricEnabled) { SavingsRepository.markUnlocked() }
    }
}

@Composable
private fun LockScreen(activity: FragmentActivity?, biometricEnabled: Boolean, onUnlocked: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val bioAvailable = activity != null && BiometricAuth.isAvailable(activity)

    fun launchBiometric() {
        if (bioAvailable) {
            activity?.let { BiometricAuth.prompt(it, onSuccess = onUnlocked, onFallback = {}) }
        }
    }

    fun submit() {
        if (pin.length == PIN_LENGTH) {
            if (SavingsRepository.verifyPasscode(pin)) {
                onUnlocked()
            } else {
                error = true
                pin = ""
            }
        }
    }

    LaunchedEffect(Unit) { if (biometricEnabled) launchBiometric() }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Text("Tharaa Fund", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Enter passcode to unlock", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))
        PinDots(filled = pin.length, error = error)
        Spacer(Modifier.height(12.dp))
        Text(
            if (error) "Wrong passcode" else " ",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.weight(1f))
        KeyPad(
            onDigit = { d -> if (pin.length < PIN_LENGTH) { pin += d; error = false } },
            onBackspace = { if (pin.isNotEmpty()) { pin = pin.dropLast(1); error = false } },
            onEnter = { submit() }
        )
        if (bioAvailable) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { launchBiometric() }) {
                Icon(Icons.Default.Fingerprint, null)
                Spacer(Modifier.width(6.dp))
                Text("Use fingerprint / face")
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun PinDots(filled: Int, error: Boolean) {
    val active = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f)
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        repeat(PIN_LENGTH) { i ->
            Box(
                Modifier.size(16.dp).clip(CircleShape)
                    .background(if (i < filled) active else inactive)
            )
        }
    }
}

@Composable
private fun KeyPad(onDigit: (String) -> Unit, onBackspace: () -> Unit, onEnter: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("back", "0", "enter"),
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { key ->
                    when (key) {
                        "back" -> KeyButton(onClick = onBackspace) {
                            Icon(Icons.AutoMirrored.Filled.Backspace, "Delete")
                        }
                        "enter" -> KeyButton(onClick = onEnter) {
                            Icon(Icons.Default.Check, "Enter", tint = MaterialTheme.colorScheme.primary)
                        }
                        else -> KeyButton(onClick = { onDigit(key) }) {
                            Text(key, fontSize = 26.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

// ---------------------------------------------------------------------------
// Navigation
// ---------------------------------------------------------------------------

private sealed interface Screen {
    data object Home : Screen
    data class Detail(val labelId: String) : Screen
    data object Calculator : Screen
    data object Settings : Screen
}

/**
 * Maps a stored session back onto a screen. Anything that no longer makes sense - a deleted label,
 * a screen we don't restore - falls back to Home.
 */
private fun UiSession?.toScreen(): Screen {
    val session = this ?: return Screen.Home
    return when (session.screen) {
        UiSession.CALCULATOR -> Screen.Calculator
        UiSession.DETAIL -> session.labelId
            ?.takeIf { SavingsRepository.data.value.labelById(it) != null }
            ?.let { Screen.Detail(it) } ?: Screen.Home
        else -> Screen.Home
    }
}

@Composable
private fun AppRoot() {
    // A recent enough session reopens where the user left off; see SavingsRepository.saveSession.
    val restored = remember { SavingsRepository.restorableSession() }
    var screen by remember { mutableStateOf(restored.toScreen()) }
    var restoredDraft by remember { mutableStateOf(restored?.calculator) }

    // Keep the repository posted on where we are. The calculator reports itself, draft included,
    // so leave it alone here.
    LaunchedEffect(screen) {
        when (val s = screen) {
            Screen.Home, Screen.Settings -> SavingsRepository.noteSession(UiSession(UiSession.HOME))
            is Screen.Detail -> SavingsRepository.noteSession(UiSession(UiSession.DETAIL, labelId = s.labelId))
            Screen.Calculator -> Unit
        }
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            onOpenLabel = { screen = Screen.Detail(it) },
            onOpenCalculator = { screen = Screen.Calculator },
            onOpenSettings = { screen = Screen.Settings },
        )
        is Screen.Detail -> LabelDetailScreen(s.labelId, onBack = { screen = Screen.Home })
        Screen.Calculator -> CalculatorScreen(
            restored = restoredDraft,
            // Leaving the calculator discards the draft: coming back later starts fresh.
            onBack = { restoredDraft = null; screen = Screen.Home },
        )
        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
    }
}

/** A small palette cycled across the label cards so each label reads distinctly. */
@Composable
private fun labelContainerColor(index: Int): Color {
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.surfaceVariant,
    )
    return palette[index % palette.size]
}

// ---------------------------------------------------------------------------
// Home: the two labels
// ---------------------------------------------------------------------------

@Composable
private fun HomeScreen(
    onOpenLabel: (String) -> Unit,
    onOpenCalculator: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val data by SavingsRepository.data.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    var showAddLabel by remember { mutableStateOf(false) }

    val total = data.labels.sumOf { SavingsRepository.valueMinor(it.id, now, data) }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tharaa Fund", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                Text("Total ${Money.formatMinor(total)} EGP",
                    style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onOpenCalculator) {
                Icon(Icons.Default.Calculate, contentDescription = "Projection calculator")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }

        data.labels.forEachIndexed { index, label ->
            LabelCard(
                title = label.name,
                valueMinor = SavingsRepository.valueMinor(label.id, now, data),
                principalMinor = SavingsRepository.netPrincipalMinor(label.id, data),
                interestMinor = SavingsRepository.interestMinor(label.id, now, data),
                container = labelContainerColor(index),
                onClick = { onOpenLabel(label.id) },
            )
        }

        OutlinedButton(onClick = { showAddLabel = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("New label")
        }

        Text(
            "Interest rate ${formatPercent(data.currentRateBps)}% / year, compounded daily. Tap a label to see its deposits.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (showAddLabel) {
        TextEntryDialog(
            title = "New label",
            label = "Label name",
            initial = "",
            onDismiss = { showAddLabel = false },
            onConfirm = { name -> SavingsRepository.addLabel(name); showAddLabel = false }
        )
    }
}

@Composable
private fun LabelCard(
    title: String,
    valueMinor: Long,
    principalMinor: Long,
    interestMinor: Long,
    container: Color,
    onClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "${Money.formatMinor(valueMinor)} EGP",
                fontSize = adaptiveMoneySize(valueMinor, 34f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    Text("Deposited", style = MaterialTheme.typography.labelSmall)
                    Text(Money.formatMinor(principalMinor), fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f)) {
                    Text("Interest", style = MaterialTheme.typography.labelSmall)
                    Text("+ ${Money.formatMinor(interestMinor)}", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Generic single-field text dialog (used for new-label and rename). */
@Composable
private fun TextEntryDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------------------
// Label detail: summary + deposit/withdraw + the table
// ---------------------------------------------------------------------------

private enum class TxnDialogKind { NONE, DEPOSIT, WITHDRAW }

@Composable
private fun LabelDetailScreen(labelId: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val data by SavingsRepository.data.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    var dialog by remember { mutableStateOf(TxnDialogKind.NONE) }
    var editing by remember { mutableStateOf<Txn?>(null) }
    var showGoal by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var confirmDeleteLabel by remember { mutableStateOf(false) }

    val label = data.labelById(labelId)
    // The label can vanish (deleted from elsewhere); fall back to Home.
    if (label == null) {
        LaunchedEffect(labelId) { onBack() }
        return
    }
    val canDelete = data.labels.size > 1

    val txns = SavingsRepository.txnsFor(labelId, data)
    val value = SavingsRepository.valueMinor(labelId, now, data)
    val principal = SavingsRepository.netPrincipalMinor(labelId, data)
    val interest = SavingsRepository.interestMinor(labelId, now, data)
    val monthInterest = Interest.interestEarnedBetween(txns, data.rateChanges, startOfMonth(now), now)
        .coerceAtLeast(0L)
    val yearInterest = Interest.interestEarnedBetween(txns, data.rateChanges, startOfYear(now), now)
        .coerceAtLeast(0L)
    val goal = label.goalMinor

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(label.name, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            IconButton(onClick = { showRename = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Rename label")
            }
            if (canDelete) {
                IconButton(onClick = { confirmDeleteLabel = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete label")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Current value (EGP)", style = MaterialTheme.typography.titleMedium)
                Text(
                    Money.formatMinor(value),
                    fontSize = adaptiveMoneySize(value, 44f),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    ResultStat("Deposited", principal, Modifier.weight(1f))
                    ResultStat("Interest", interest, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    ResultStat("This month", monthInterest, Modifier.weight(1f))
                    ResultStat("This year", yearInterest, Modifier.weight(1f))
                }
            }
        }

        GoalSection(labelId, value, goal, data, now, onEdit = { showGoal = true })

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { dialog = TxnDialogKind.DEPOSIT }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Deposit")
            }
            OutlinedButton(onClick = { dialog = TxnDialogKind.WITHDRAW }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Remove, null); Spacer(Modifier.width(4.dp)); Text("Withdraw")
            }
        }

        Text("Deposits & withdrawals", style = MaterialTheme.typography.titleMedium)
        if (txns.isEmpty()) {
            Text("Nothing yet. Add your first deposit.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("Tap a row to edit it.", style = MaterialTheme.typography.bodySmall)
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(txns.sortedByDescending { it.timestamp }, key = { it.id }) { t ->
                    TxnRow(t, onEdit = { editing = t })
                }
            }
        }
    }

    if (dialog != TxnDialogKind.NONE) {
        val type = if (dialog == TxnDialogKind.DEPOSIT) TxnType.DEPOSIT else TxnType.WITHDRAWAL
        TxnDialog(
            type = type,
            onDismiss = { dialog = TxnDialogKind.NONE },
            onConfirm = { minor, ts, note ->
                SavingsRepository.addTxn(labelId, type, minor, ts, note)
                dialog = TxnDialogKind.NONE
            }
        )
    }

    editing?.let { t ->
        TxnDialog(
            type = t.type,
            initial = t,
            onDismiss = { editing = null },
            onConfirm = { minor, ts, note ->
                SavingsRepository.updateTxn(t.id, minor, ts, note)
                editing = null
            }
        )
    }

    if (showGoal) {
        GoalDialog(
            currentGoal = goal,
            onDismiss = { showGoal = false },
            onConfirm = { minor ->
                SavingsRepository.setGoal(labelId, minor)
                showGoal = false
            }
        )
    }

    if (showRename) {
        TextEntryDialog(
            title = "Rename label",
            label = "Label name",
            initial = label.name,
            onDismiss = { showRename = false },
            onConfirm = { name -> SavingsRepository.renameLabel(labelId, name); showRename = false }
        )
    }

    if (confirmDeleteLabel) {
        AlertDialog(
            onDismissRequest = { confirmDeleteLabel = false },
            title = { Text("Delete \"${label.name}\"?") },
            text = { Text("This removes the label and its ${txns.size} transaction(s). This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    SavingsRepository.deleteLabel(labelId)
                    confirmDeleteLabel = false
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteLabel = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun GoalSection(
    labelId: String,
    valueMinor: Long,
    goalMinor: Long,
    data: SavingsData,
    now: Long,
    onEdit: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Goal", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onEdit) { Text(if (goalMinor > 0) "Edit" else "Set goal") }
            }
            if (goalMinor <= 0) {
                Text("No goal yet. Set a target to track progress and an ETA.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                val progress = (valueMinor.toDouble() / goalMinor).coerceIn(0.0, 1.0).toFloat()
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${Money.formatMinor(valueMinor)} of ${Money.formatMinor(goalMinor)} EGP (${(progress * 100).roundToInt()}%)",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(goalEtaText(labelId, valueMinor, goalMinor, data, now),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Human ETA from the goal engine, using the recent contribution pace. */
private fun goalEtaText(labelId: String, valueMinor: Long, goalMinor: Long, data: SavingsData, now: Long): String {
    if (valueMinor >= goalMinor) return "Goal reached. Nice."
    val pace = SavingsRepository.recentMonthlyPaceMinor(labelId, now, 6, data)
    val months = Projection.monthsToReach(valueMinor, goalMinor, data.currentRateBps, pace)
        ?: return "Not reachable at your recent pace of ${Money.formatMinor(pace)}/mo. Deposit more to get there."
    val eta = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, months) }
    return "About $months ${if (months == 1) "month" else "months"} (~${monthYearFmt.format(eta.time)}) at ${Money.formatMinor(pace)}/mo."
}

@Composable
private fun TxnRow(t: Txn, onEdit: () -> Unit) {
    val (sign, amountColor) = if (t.type == TxnType.DEPOSIT) {
        "+" to MaterialTheme.colorScheme.primary
    } else {
        "-" to MaterialTheme.colorScheme.error
    }
    var confirmDelete by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (t.type == TxnType.DEPOSIT) "Deposit" else "Withdrawal",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(dateOnlyFmt.format(Date(t.timestamp)), style = MaterialTheme.typography.bodySmall)
                if (t.note.isNotBlank()) Text(t.note, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "$sign ${Money.formatMinor(t.amountMinor)}",
                fontWeight = FontWeight.SemiBold,
                color = amountColor
            )
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }

    if (confirmDelete) {
        val kind = if (t.type == TxnType.DEPOSIT) "deposit" else "withdrawal"
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this $kind?") },
            text = {
                Text("${Money.formatMinor(t.amountMinor)} EGP on ${dateOnlyFmt.format(Date(t.timestamp))} will be removed. This can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    SavingsRepository.deleteTxn(t.id)
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

/** Keeps only digits and a single decimal point, so money fields accept numbers only. */
private fun sanitizeAmount(input: String): String {
    val filtered = input.filter { it.isDigit() || it == '.' }
    val dot = filtered.indexOf('.')
    return if (dot == -1) filtered
    else filtered.substring(0, dot + 1) + filtered.substring(dot + 1).replace(".", "")
}

/** Raw (comma-free) money string for prefilling a field whose display adds its own commas. */
private fun moneyRaw(minor: Long): String = Money.formatMinor(minor).filter { it.isDigit() || it == '.' }

@Composable
private fun TxnDialog(
    type: TxnType,
    initial: Txn? = null,
    onDismiss: () -> Unit,
    onConfirm: (Long, Long, String) -> Unit,
) {
    var amount by remember { mutableStateOf(initial?.let { moneyRaw(it.amountMinor) } ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var dateMillis by remember { mutableStateOf(initial?.timestamp ?: System.currentTimeMillis()) }
    val minor = Money.parseToMinor(amount)
    val verb = if (initial != null) "Edit" else if (type == TxnType.DEPOSIT) "Add" else "Record"
    val noun = if (type == TxnType.DEPOSIT) "deposit" else "withdrawal"
    val title = "$verb $noun"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = sanitizeAmount(it) },
                    label = { Text("Amount (EGP)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = ThousandsTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                DateField("Date", dateMillis, futureAllowed = false) { dateMillis = it }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = minor != null && minor > 0,
                onClick = { minor?.let { onConfirm(it, dateMillis, note.trim()) } }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Disallows picking a day in the future (deposits/withdrawals can only have happened by now). */
@OptIn(ExperimentalMaterial3Api::class)
private object PastOrPresentDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        utcTimeMillis <= System.currentTimeMillis()
}

/**
 * Button that shows the chosen date and opens a Material date picker. [futureAllowed] is false for
 * transaction dates (they can't be in the future) but true for a rate's effective date, since the
 * bank may announce a change that starts on an upcoming day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, millis: Long, futureAllowed: Boolean, onPick: (Long) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
        Text("$label: ${dateOnlyFmt.format(Date(millis))}")
    }
    if (show) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = millis,
            selectableDates = if (futureAllowed) DatePickerDefaults.AllDates else PastOrPresentDates,
        )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let(onPick)
                    show = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun GoalDialog(currentGoal: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var amount by remember { mutableStateOf(if (currentGoal > 0) moneyRaw(currentGoal) else "") }
    val minor = Money.parseToMinor(amount) ?: 0L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Target value for this label. Set 0 to clear the goal.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = sanitizeAmount(it) },
                    label = { Text("Goal amount (EGP)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = ThousandsTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(minor) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PasscodeSetupDialog(onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.length == PIN_LENGTH && pin.all { it.isDigit() } && pin == confirm

    fun digits(s: String) = s.filter { it.isDigit() }.take(PIN_LENGTH)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a $PIN_LENGTH-digit passcode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = digits(it) },
                    label = { Text("New passcode") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = digits(it) },
                    label = { Text("Confirm passcode") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                if (confirm.length == PIN_LENGTH && pin != confirm) {
                    Text("Passcodes don't match", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { SavingsRepository.setPasscode(pin); onDismiss() }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RateDialog(currentBps: Int, onDismiss: () -> Unit, onConfirm: (Int, Long) -> Unit) {
    var pct by remember { mutableStateOf(formatPercent(currentBps)) }
    var dateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val bps = pct.toDoubleOrNull()?.let { (it * 100).toInt() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change interest rate") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The new rate applies from its effective date forward. Interest earned before that date keeps the old rate.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = pct,
                    onValueChange = { pct = sanitizeAmount(it) },
                    label = { Text("Annual rate (%)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                DateField("Effective from", dateMillis, futureAllowed = true) { dateMillis = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = bps != null && bps >= 0,
                onClick = { bps?.let { onConfirm(it, dateMillis) } }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------------------
// Settings: interest rate + app lock
// ---------------------------------------------------------------------------

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val data by SavingsRepository.data.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var showRate by remember { mutableStateOf(false) }
    var showPasscodeSetup by remember { mutableStateOf(false) }

    // ---- Cloud backup (Google Drive) state ----
    var cloudEmail by remember { mutableStateOf(BackupManager.signedInEmail(context)) }
    var cloudHasPass by remember { mutableStateOf(BackupManager.hasPassphrase(context)) }
    var cloudAuto by remember { mutableStateOf(BackupManager.isAutoEnabled(context)) }
    var cloudLast by remember { mutableStateOf(BackupManager.lastBackup(context)) }
    var cloudBusy by remember { mutableStateOf(false) }
    var showCloudPassphrase by remember { mutableStateOf(false) }
    val signIn = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (BackupManager.handleSignInResult(result.data)) {
            cloudEmail = BackupManager.signedInEmail(context)
        } else {
            Toast.makeText(context, "Google sign-in didn't complete", Toast.LENGTH_SHORT).show()
        }
    }

    // Encrypted backup/restore via the system file picker (works with Drive, Files, etc.). The
    // passphrase never leaves the device; only the encrypted blob is written out.
    var exportPassphrase by remember { mutableStateOf<String?>(null) }
    var importedBlob by remember { mutableStateOf<ByteArray?>(null) }
    var showExportPassphrase by remember { mutableStateOf(false) }
    var showImportPassphrase by remember { mutableStateOf(false) }

    val exportEnc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val pass = exportPassphrase
        exportPassphrase = null
        if (uri != null && pass != null) {
            val ok = runCatching {
                val blob = BackupCrypto.encrypt(TharaaBackupSource.export(), pass.toCharArray())
                context.contentResolver.openOutputStream(uri)?.use { os -> os.write(blob) }
            }.isSuccess
            Toast.makeText(context, if (ok) "Encrypted backup saved" else "Couldn't save", Toast.LENGTH_SHORT).show()
        }
    }
    val exportCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            val ok = runCatching {
                context.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(SavingsRepository.transactionsCsv().toByteArray())
                }
            }.isSuccess
            Toast.makeText(context, if (ok) "CSV saved" else "Couldn't save", Toast.LENGTH_SHORT).show()
        }
    }
    val importEnc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val bytes = uri?.let {
            runCatching {
                context.contentResolver.openInputStream(it)?.use { input -> input.readBytes() }
            }.getOrNull()
        }
        if (bytes != null) {
            importedBlob = bytes
            showImportPassphrase = true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        // ---- Interest rate ----
        Text("Interest rate", style = MaterialTheme.typography.titleSmall)
        Text("Current: ${formatPercent(data.currentRateBps)}% per year, compounded daily.",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { showRate = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Change rate")
        }

        val history = data.rateChanges.sortedByDescending { it.effectiveTimestamp }
        if (history.size > 1) {
            Text("Rate history", style = MaterialTheme.typography.bodyMedium)
            history.forEach { rc ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${formatPercent(rc.annualRateBps)}% / year",
                                fontWeight = FontWeight.SemiBold)
                            Text("From ${dateOnlyFmt.format(Date(rc.effectiveTimestamp))}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        // The seeded starting rate (epoch) is the floor of the schedule; keep it.
                        if (rc.effectiveTimestamp > 0L) {
                            IconButton(onClick = { SavingsRepository.deleteRateChange(rc.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete rate change")
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        // ---- App lock ----
        Text("App lock", style = MaterialTheme.typography.titleSmall)
        Text(
            if (data.hasPasscode) "Passcode is set. Required every time you reopen the app."
            else "No passcode. Anyone who opens the app sees your balances.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = { showPasscodeSetup = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (data.hasPasscode) "Change passcode" else "Set passcode")
        }
        if (data.hasPasscode) {
            val bioAvailable = activity != null && BiometricAuth.isAvailable(activity)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Unlock with fingerprint / face", style = MaterialTheme.typography.bodyMedium)
                    if (!bioAvailable) {
                        Text("No fingerprint/face enrolled on this device yet. Enroll one in the phone's settings and this will start working.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Switch(
                    checked = data.biometricEnabled,
                    onCheckedChange = { SavingsRepository.setBiometricEnabled(it) }
                )
            }
            TextButton(onClick = { SavingsRepository.clearPasscode() }) { Text("Remove passcode") }
        }

        HorizontalDivider()

        // ---- Reminders ----
        Text("Reminders", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Monthly deposit reminder", style = MaterialTheme.typography.bodyMedium)
                Text("A notification on your chosen day each month.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = data.reminderEnabled,
                onCheckedChange = { on ->
                    if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        activity?.let {
                            ActivityCompat.requestPermissions(
                                it, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001
                            )
                        }
                    }
                    SavingsRepository.setReminder(on, data.reminderDayOfMonth)
                }
            )
        }
        if (data.reminderEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Remind on day ${data.reminderDayOfMonth} of each month", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                IconButton(
                    onClick = { SavingsRepository.setReminder(true, data.reminderDayOfMonth - 1) },
                    enabled = data.reminderDayOfMonth > 1
                ) { Icon(Icons.Default.Remove, contentDescription = "Earlier day") }
                IconButton(
                    onClick = { SavingsRepository.setReminder(true, data.reminderDayOfMonth + 1) },
                    enabled = data.reminderDayOfMonth < 28
                ) { Icon(Icons.Default.Add, contentDescription = "Later day") }
            }
            Text("Days 1-28, so every month has that day.", style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()

        // ---- Backup & restore ----
        Text("Backup & restore", style = MaterialTheme.typography.titleSmall)
        Text("Your data lives only on this phone. Export a passphrase-encrypted backup to keep it safe or move it to a new device. Keep the passphrase; without it the backup can't be opened.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { showExportPassphrase = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Export encrypted backup") }
        OutlinedButton(
            onClick = { SavingsRepository.suppressNextLock(); exportCsv.launch("tharaa-transactions.csv") },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Export transactions (CSV)") }
        OutlinedButton(
            onClick = { SavingsRepository.suppressNextLock(); importEnc.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Restore encrypted backup") }
        Text("Restoring replaces everything currently in the app.",
            style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()

        // ---- Cloud backup (Google Drive) ----
        Text("Cloud backup (Google Drive)", style = MaterialTheme.typography.titleSmall)
        val email = cloudEmail
        if (email == null) {
            Text("Sign in to store encrypted backups in an \"Auto Backups\" folder in your Drive.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { SavingsRepository.suppressNextLock(); signIn.launch(BackupManager.signInIntent(context)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Sign in with Google") }
        } else {
            Text("Signed in as $email", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (cloudHasPass) "Backup passphrase is set on this device."
                else "Set a passphrase to encrypt your cloud backups.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = { showCloudPassphrase = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (cloudHasPass) "Change backup passphrase" else "Set backup passphrase")
            }
            OutlinedButton(
                enabled = cloudHasPass && !cloudBusy,
                onClick = {
                    cloudBusy = true
                    BackupManager.backupNow(context) { r ->
                        cloudBusy = false
                        cloudLast = BackupManager.lastBackup(context)
                        Toast.makeText(context, resultText(r, "Backed up to Drive"), Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (cloudBusy) "Working..." else "Back up now") }
            OutlinedButton(
                enabled = cloudHasPass && !cloudBusy,
                onClick = {
                    cloudBusy = true
                    BackupManager.restoreLatest(context) { r ->
                        cloudBusy = false
                        Toast.makeText(context, resultText(r, "Restored from Drive"), Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restore latest from Drive") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-backup", style = MaterialTheme.typography.bodyMedium)
                    Text("Uploads a few seconds after each change.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = cloudAuto,
                    enabled = cloudHasPass,
                    onCheckedChange = { on -> BackupManager.setAutoEnabled(context, on); cloudAuto = on }
                )
            }
            Text(
                if (cloudLast > 0L) "Last cloud backup: ${dateTimeFmt.format(Date(cloudLast))}"
                else "No cloud backup yet.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = {
                BackupManager.signOut(context) {
                    cloudEmail = null; cloudAuto = false
                    BackupManager.setAutoEnabled(context, false)
                }
            }) { Text("Sign out") }
        }
    }

    if (showRate) {
        RateDialog(
            currentBps = data.currentRateBps,
            onDismiss = { showRate = false },
            onConfirm = { bps, ts ->
                SavingsRepository.setRate(bps, ts)
                showRate = false
            }
        )
    }
    if (showPasscodeSetup) {
        PasscodeSetupDialog(onDismiss = { showPasscodeSetup = false })
    }
    if (showExportPassphrase) {
        PassphraseDialog(
            title = "Encrypt backup",
            confirmLabel = "Export",
            onDismiss = { showExportPassphrase = false },
            onConfirm = { pass ->
                showExportPassphrase = false
                exportPassphrase = pass
                SavingsRepository.suppressNextLock()
                exportEnc.launch("tharaa-backup.tbk")
            }
        )
    }
    if (showCloudPassphrase) {
        PassphraseDialog(
            title = "Backup passphrase",
            confirmLabel = "Save",
            onDismiss = { showCloudPassphrase = false },
            onConfirm = { pass ->
                BackupManager.setPassphrase(context, pass)
                cloudHasPass = true
                showCloudPassphrase = false
                Toast.makeText(context, "Passphrase saved on this device", Toast.LENGTH_SHORT).show()
            }
        )
    }
    if (showImportPassphrase) {
        PassphraseDialog(
            title = "Open backup",
            confirmLabel = "Restore",
            onDismiss = { showImportPassphrase = false; importedBlob = null },
            onConfirm = { pass ->
                val blob = importedBlob
                showImportPassphrase = false
                importedBlob = null
                val restored = blob != null && runCatching {
                    TharaaBackupSource.restore(BackupCrypto.decrypt(blob, pass.toCharArray()))
                }.isSuccess
                Toast.makeText(
                    context,
                    if (restored) "Restored from backup" else "Wrong passphrase or not a Tharaa backup",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

@Composable
private fun PassphraseDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The passphrase encrypts the backup file. You'll need the same one to restore it.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = pass.length >= 4, onClick = { onConfirm(pass) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------------------
// Calculator: project the balance forward
// ---------------------------------------------------------------------------

@Composable
private fun CalculatorScreen(restored: CalculatorDraft?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val data by SavingsRepository.data.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()

    val total = data.labels.sumOf { SavingsRepository.valueMinor(it.id, now, data) }

    // A [restored] draft is what the user was typing before they left the app. Failing that,
    // defaults are pulled from the live app; every field can be overridden.
    var startText by remember { mutableStateOf(restored?.start ?: moneyRaw(total)) }
    var rateText by remember { mutableStateOf(restored?.rate ?: formatPercent(data.currentRateBps)) }
    var depositText by remember { mutableStateOf(restored?.deposit ?: "") }
    var withdrawText by remember { mutableStateOf(restored?.withdrawal ?: "") }
    var increaseText by remember { mutableStateOf(restored?.yearlyIncrease ?: "") }
    var yearsText by remember { mutableStateOf(restored?.years ?: "10") }
    var monthsInput by remember { mutableStateOf(restored?.months ?: "0") }
    var showSavePreset by remember { mutableStateOf(false) }

    val startMinor = Money.parseToMinor(startText) ?: 0L
    val rateBps = parsePercentToBps(rateText) ?: data.currentRateBps
    val depositMinor = Money.parseToMinor(depositText) ?: 0L
    val withdrawMinor = Money.parseToMinor(withdrawText) ?: 0L
    val increaseBps = parsePercentToBps(increaseText) ?: 0
    val years = yearsText.toIntOrNull() ?: 0
    val extraMonths = monthsInput.toIntOrNull() ?: 0
    val months = (years * 12 + extraMonths).coerceIn(1, 1200)

    // Hand the raw text to the repository so backgrounding the app can persist it as typed.
    val draft = CalculatorDraft(
        start = startText, rate = rateText, deposit = depositText, withdrawal = withdrawText,
        yearlyIncrease = increaseText, years = yearsText, months = monthsInput,
    )
    LaunchedEffect(draft) {
        SavingsRepository.noteSession(UiSession(UiSession.CALCULATOR, calculator = draft))
    }

    val result = Projection.project(startMinor, rateBps, depositMinor, withdrawMinor, months, increaseBps)

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Calculator", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Text(
            "Project your Tharaa balance forward. Starting amount and rate default to your current numbers. Change anything to explore.",
            style = MaterialTheme.typography.bodySmall
        )

        // Quick-fill the starting amount from the live balances.
        Text("Start from", style = MaterialTheme.typography.labelMedium)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { startText = moneyRaw(total) }) { Text("Total", maxLines = 1) }
            data.labels.forEach { label ->
                OutlinedButton(onClick = {
                    startText = moneyRaw(SavingsRepository.valueMinor(label.id, now, data))
                }) { Text(label.name, maxLines = 1) }
            }
        }

        CalcField(startText, "Starting amount (EGP)") { startText = sanitizeAmount(it) }
        CalcField(rateText, "Annual rate (%)") { rateText = sanitizeAmount(it) }
        CalcField(depositText, "Monthly deposit (EGP)") { depositText = sanitizeAmount(it) }
        CalcField(withdrawText, "Monthly withdrawal (EGP)") { withdrawText = sanitizeAmount(it) }
        CalcField(increaseText, "Yearly deposit increase (%, optional)") { increaseText = sanitizeAmount(it) }

        Text("Timeline ($months months total)", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = yearsText,
                onValueChange = { yearsText = it.filter(Char::isDigit).take(3) },
                label = { Text("Years") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = monthsInput,
                onValueChange = { monthsInput = it.filter(Char::isDigit).take(3) },
                label = { Text("Months") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        // ---- Results ----
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Projected value", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${Money.formatMinor(result.finalValueMinor)} EGP",
                    fontSize = adaptiveMoneySize(result.finalValueMinor, 40f),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    ResultStat("Starting", result.startingMinor, Modifier.weight(1f))
                    ResultStat("Interest", result.interestMinor, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    ResultStat("Deposited", result.totalDepositedMinor, Modifier.weight(1f))
                    ResultStat("Withdrawn", result.totalWithdrawnMinor, Modifier.weight(1f))
                }
            }
        }

        // ---- Graph ----
        Text("Growth over time", style = MaterialTheme.typography.titleMedium)
        ProjectionChart(result, months, Modifier.fillMaxWidth().height(220.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot(MaterialTheme.colorScheme.primary, "Projected value")
            LegendDot(MaterialTheme.colorScheme.tertiary, "Money in")
        }
        Text(
            "Estimate only: assumes the rate holds and deposits/withdrawals repeat every month.",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()

        // ---- Saved scenarios (presets) ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Saved scenarios", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { showSavePreset = true }) { Text("Save current") }
        }
        if (data.presets.isEmpty()) {
            Text("Save the current inputs to compare scenarios later.",
                style = MaterialTheme.typography.bodySmall)
        } else {
            data.presets.forEach { preset ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier.weight(1f).clickable {
                                startText = moneyRaw(preset.startMinor)
                                rateText = formatPercent(preset.annualRateBps)
                                depositText = if (preset.monthlyDepositMinor > 0) moneyRaw(preset.monthlyDepositMinor) else ""
                                withdrawText = if (preset.monthlyWithdrawalMinor > 0) moneyRaw(preset.monthlyWithdrawalMinor) else ""
                                increaseText = if (preset.yearlyIncreaseBps > 0) formatPercent(preset.yearlyIncreaseBps) else ""
                                yearsText = (preset.months / 12).toString()
                                monthsInput = (preset.months % 12).toString()
                            }
                        ) {
                            Text(preset.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${formatPercent(preset.annualRateBps)}% • ${durationLabel(preset.months)} • +${Money.formatMinor(preset.monthlyDepositMinor)}/mo",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(onClick = { SavingsRepository.deletePreset(preset.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete scenario")
                        }
                    }
                }
            }
            Text("Tap a scenario to load it.", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showSavePreset) {
        TextEntryDialog(
            title = "Save scenario",
            label = "Name",
            initial = "",
            onDismiss = { showSavePreset = false },
            onConfirm = { name ->
                SavingsRepository.addPreset(
                    ProjectionPreset(
                        name = name,
                        startMinor = startMinor,
                        annualRateBps = rateBps,
                        monthlyDepositMinor = depositMinor,
                        monthlyWithdrawalMinor = withdrawMinor,
                        months = months,
                        yearlyIncreaseBps = increaseBps,
                    )
                )
                showSavePreset = false
            }
        )
    }
}

@Composable
private fun CalcField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = ThousandsTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

@Composable
private fun ResultStat(label: String, minor: Long, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(Money.formatMinor(minor), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Line chart of projected value (thick) vs money-put-in (thin), with a Y axis in EGP and an X axis
 * in time. Gridlines + labels are drawn with a TextMeasurer.
 */
@Composable
private fun ProjectionChart(result: Projection.Result, totalMonths: Int, modifier: Modifier = Modifier) {
    val valueColor = MaterialTheme.colorScheme.primary
    val contribColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
    val axisColor = MaterialTheme.colorScheme.outline
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
    val points = result.points

    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val lastMonth = points.last().month.toFloat().coerceAtLeast(1f)
        var maxV = Long.MIN_VALUE
        var minV = 0L
        for (p in points) {
            maxV = maxOf(maxV, p.valueMinor, p.contributedMinor)
            minV = minOf(minV, p.valueMinor)
        }
        if (maxV <= minV) maxV = minV + 1
        val range = (maxV - minV).toFloat()

        val leftPad = 92f
        val bottomPad = 30f
        val plotLeft = leftPad
        val plotTop = 8f
        val plotRight = size.width - 10f
        val plotBottom = size.height - bottomPad
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop

        fun xAt(month: Int) = plotLeft + plotW * (month / lastMonth)
        fun yAt(v: Long) = plotBottom - plotH * ((v - minV) / range)

        // Y axis: gridlines + EGP labels.
        val yTicks = 4
        for (i in 0..yTicks) {
            val v = minV + (range * i / yTicks).toLong()
            val y = yAt(v)
            drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
            val layout = measurer.measure(AnnotatedString(compactEgp(v)), labelStyle)
            drawText(layout, topLeft = Offset(plotLeft - 6f - layout.size.width, y - layout.size.height / 2f))
        }

        // Axes.
        drawLine(axisColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), strokeWidth = 2f)
        drawLine(axisColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), strokeWidth = 2f)
        if (minV < 0) {
            val zeroY = yAt(0)
            drawLine(axisColor.copy(alpha = 0.5f), Offset(plotLeft, zeroY), Offset(plotRight, zeroY), strokeWidth = 1.5f)
        }

        // X axis: time ticks.
        val xTicks = 4
        for (i in 0..xTicks) {
            val month = totalMonths * i / xTicks
            val x = plotLeft + plotW * (month.toFloat() / lastMonth)
            if (i > 0) drawLine(gridColor, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 0.5f)
            val label = if (month == 0) "now" else durationLabel(month)
            val layout = measurer.measure(AnnotatedString(label), labelStyle)
            val tx = (x - layout.size.width / 2f).coerceIn(plotLeft, plotRight - layout.size.width)
            drawText(layout, topLeft = Offset(tx, plotBottom + 6f))
        }

        // Data lines.
        val contributed = Path().apply {
            moveTo(xAt(points.first().month), yAt(points.first().contributedMinor))
            for (p in points.drop(1)) lineTo(xAt(p.month), yAt(p.contributedMinor))
        }
        drawPath(contributed, contribColor, style = Stroke(width = 3f, cap = StrokeCap.Round))

        val value = Path().apply {
            moveTo(xAt(points.first().month), yAt(points.first().valueMinor))
            for (p in points.drop(1)) lineTo(xAt(p.month), yAt(p.valueMinor))
        }
        drawPath(value, valueColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
    }
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

/** 1800 bps -> "18", 1850 -> "18.5". Trims trailing zeros so rates read cleanly. */
private fun formatPercent(bps: Int): String {
    val pct = bps / 100.0
    return if (pct == pct.toLong().toDouble()) pct.toLong().toString()
    else pct.toString().trimEnd('0').trimEnd('.')
}

/** "18" or "18.5" -> basis points (1800 / 1850). Null if blank/unparseable, so a default applies. */
private fun parsePercentToBps(text: String): Int? =
    text.trim().toDoubleOrNull()?.let { (it * 100).roundToInt() }

/** Compact human duration: 42 -> "3y 6m", 60 -> "5y", 8 -> "8m". */
private fun durationLabel(months: Int): String {
    val y = months / 12
    val m = months % 12
    return when {
        y > 0 && m > 0 -> "${y}y ${m}m"
        y > 0 -> "${y}y"
        else -> "${m}m"
    }
}

/** Shrinks the headline money font as the amount grows so "<number> EGP" stays on one line. */
private fun adaptiveMoneySize(minor: Long, base: Float): TextUnit {
    val egp = kotlin.math.abs(minor) / 100
    val scale = when {
        egp < 1_000_000 -> 1.0f          // up to 999,999.99
        egp < 10_000_000 -> 0.82f        // millions
        egp < 100_000_000 -> 0.66f       // tens of millions
        egp < 1_000_000_000 -> 0.54f     // hundreds of millions
        else -> 0.44f                    // billions+
    }
    return (base * scale).sp
}

/** 1_234_567_89 piastres -> "12.3M" for compact chart axis labels. */
private fun compactEgp(minor: Long): String {
    val egp = minor / 100.0
    val abs = kotlin.math.abs(egp)
    return when {
        abs >= 1_000_000_000 -> "%.1fB".format(egp / 1_000_000_000)
        abs >= 1_000_000 -> "%.1fM".format(egp / 1_000_000)
        abs >= 1_000 -> "%.0fk".format(egp / 1_000)
        else -> "%.0f".format(egp)
    }
}

/**
 * Adds thousands separators to a numeric text field for display, while the stored value stays raw
 * digits (so parsing is unaffected). Groups only the integer part, before any decimal point.
 */
private object ThousandsTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val dot = raw.indexOf('.')
        val intPart = if (dot == -1) raw else raw.substring(0, dot)
        val rest = if (dot == -1) "" else raw.substring(dot) // includes the dot
        val grouped = if (intPart.isEmpty()) "" else
            intPart.reversed().chunked(3).joinToString(",").reversed()
        val out = grouped + rest

        fun commasBefore(o: Int): Int {
            var c = 0
            for (j in 1..o) if (j < intPart.length && (intPart.length - j) % 3 == 0) c++
            return c
        }

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val o = offset.coerceIn(0, raw.length)
                return if (o <= intPart.length) o + commasBefore(o)
                else o + grouped.count { it == ',' }
            }
            override fun transformedToOriginal(offset: Int): Int {
                val t = offset.coerceIn(0, out.length)
                val commas = out.take(t).count { it == ',' }
                return (t - commas).coerceIn(0, raw.length)
            }
        }
        return TransformedText(AnnotatedString(out), mapping)
    }
}

// Dates are chosen/stored as UTC-midnight day buckets (see Interest.dayIndex), so format them in
// UTC too, otherwise a late-evening local time could show the previous day.
private val dateOnlyFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

private val monthYearFmt = SimpleDateFormat("MMM yyyy", Locale.getDefault())
private val dateTimeFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

/** Toast text for a backup/restore result: the success message, or the error detail. */
private fun resultText(result: BackupResult, successMessage: String): String = when (result) {
    is BackupResult.Success -> successMessage
    is BackupResult.Error -> result.message
}

private fun startOfMonth(now: Long): Long = Calendar.getInstance().apply {
    timeInMillis = now
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun startOfYear(now: Long): Long = Calendar.getInstance().apply {
    timeInMillis = now
    set(Calendar.DAY_OF_YEAR, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
