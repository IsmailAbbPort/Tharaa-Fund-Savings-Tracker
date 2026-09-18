package com.tharaa.savings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tharaa.savings.*
import java.util.Date
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Label detail: summary + deposit/withdraw + the table
// ---------------------------------------------------------------------------

private enum class TxnDialogKind { NONE, DEPOSIT, WITHDRAW }

@Composable
internal fun LabelDetailScreen(labelId: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val data by SavingsRepository.data.collectAsStateWithLifecycle()
    val now = rememberNow()
    var dialog by rememberSaveable { mutableStateOf(TxnDialogKind.NONE) }
    var editing by remember { mutableStateOf<Txn?>(null) }
    var showGoal by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteLabel by rememberSaveable { mutableStateOf(false) }

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

    // One list for the whole page. The summary used to sit in a fixed Column above a scrolling
    // list, so at large font or display sizes the cards ate the viewport and the transactions
    // below them could not be reached at all. Rows stay lazy; everything above them rides along
    // as a single leading item.
    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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

                Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Deposits & withdrawals", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (txns.isEmpty()) "Nothing yet. Add your first deposit." else "Tap a row to edit it.",
                        style = if (txns.isEmpty()) MaterialTheme.typography.bodyMedium
                        else MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        items(txns.sortedByDescending { it.timestamp }, key = { it.id }) { t ->
            TxnRow(t, onEdit = { editing = t })
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


@Composable
private fun TxnRow(t: Txn, onEdit: () -> Unit) {
    val (sign, amountColor) = if (t.type == TxnType.DEPOSIT) {
        "+" to MaterialTheme.colorScheme.primary
    } else {
        "-" to MaterialTheme.colorScheme.error
    }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
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

@Composable
private fun TxnDialog(
    type: TxnType,
    initial: Txn? = null,
    onDismiss: () -> Unit,
    onConfirm: (Long, Long, String) -> Unit,
) {
    var amount by rememberSaveable { mutableStateOf(initial?.let { moneyRaw(it.amountMinor) } ?: "") }
    var note by rememberSaveable { mutableStateOf(initial?.note ?: "") }
    var dateMillis by rememberSaveable { mutableStateOf(initial?.timestamp ?: System.currentTimeMillis()) }
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

@Composable
private fun GoalDialog(currentGoal: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var amount by rememberSaveable { mutableStateOf(if (currentGoal > 0) moneyRaw(currentGoal) else "") }
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

