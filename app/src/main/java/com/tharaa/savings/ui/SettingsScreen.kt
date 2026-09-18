package com.tharaa.savings.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.backupkit.BackupCrypto
import com.backupkit.BackupManager
import com.tharaa.savings.*
import java.util.Date

// ---------------------------------------------------------------------------
// Settings: interest rate + app lock
// ---------------------------------------------------------------------------

@Composable
internal fun SettingsScreen(onBack: () -> Unit) {
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
    // Share the calculator's parser: a local (it * 100).toInt() truncated 19.99 to 19.98, because
    // 19.99 * 100 is 1998.9999999999998 in binary floating point.
    val bps = parsePercentToBps(pct)

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

