package com.tharaa.savings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.tharaa.savings.*
import kotlinx.coroutines.delay
import java.util.Date

/**
 * A wall clock that re-reads itself every minute while the screen is composed.
 *
 * Reading System.currentTimeMillis() straight into a composable looks equivalent but isn't: the
 * only thing that recomposed these screens was the ledger changing, so a balance sat frozen at
 * whatever it was worth at the last edit. Interest compounds at UTC midnight, so a screen left
 * open across midnight went on showing yesterday's figure indefinitely.
 */
@Composable
internal fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/** Generic single-field text dialog (used for new-label and rename). */
@Composable
internal fun TextEntryDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
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
internal fun DateField(label: String, millis: Long, futureAllowed: Boolean, onPick: (Long) -> Unit) {
    var show by rememberSaveable { mutableStateOf(false) }
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


/** One labelled money figure; used in the detail summary card and the calculator result. */
@Composable
internal fun ResultStat(label: String, minor: Long, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(Money.formatMinor(minor), fontWeight = FontWeight.SemiBold)
    }
}
