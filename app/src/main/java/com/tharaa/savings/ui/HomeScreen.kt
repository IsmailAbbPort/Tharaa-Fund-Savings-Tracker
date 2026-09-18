package com.tharaa.savings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tharaa.savings.*

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
internal fun HomeScreen(
    onOpenLabel: (String) -> Unit,
    onOpenCalculator: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val data by SavingsRepository.data.collectAsStateWithLifecycle()
    val writeFailed by SavingsRepository.writeFailed.collectAsStateWithLifecycle()
    val now = rememberNow()
    var showAddLabel by rememberSaveable { mutableStateOf(false) }

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

        if (writeFailed) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    "Couldn't save to this device. Recent changes are on screen only and will be " +
                        "lost when the app closes. Check your free storage.",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
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

