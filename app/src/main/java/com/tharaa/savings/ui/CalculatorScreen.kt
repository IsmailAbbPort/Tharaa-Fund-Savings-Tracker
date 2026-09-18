package com.tharaa.savings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tharaa.savings.*

// ---------------------------------------------------------------------------
// Calculator: project the balance forward
// ---------------------------------------------------------------------------

@Composable
internal fun CalculatorScreen(restored: CalculatorDraft?, onBack: () -> Unit) {
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
                                "${formatPercent(preset.annualRateBps)}% â€¢ ${durationLabel(preset.months)} â€¢ +${Money.formatMinor(preset.monthlyDepositMinor)}/mo",
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

