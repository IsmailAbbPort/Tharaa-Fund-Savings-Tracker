package com.tharaa.savings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tharaa.savings.*

// ---------------------------------------------------------------------------
// Gate: passcode wall in front of the app
// ---------------------------------------------------------------------------

@Composable
internal fun Gate() {
    val data by SavingsRepository.data.collectAsStateWithLifecycle()
    val unlocked by SavingsRepository.sessionUnlocked.collectAsStateWithLifecycle()
    val loadFailed by SavingsRepository.loadFailed.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? FragmentActivity

    if (loadFailed) {
        UnreadableDataScreen()
    } else if (!data.hasPasscode || unlocked) {
        AppRoot()
    } else {
        LockScreen(activity, data.biometricEnabled) { SavingsRepository.markUnlocked() }
    }
}

/**
 * Shown instead of the app when the savings file is present but won't decrypt, which normally means
 * it was restored from another device. The app writes nothing in this state, so the file is left
 * exactly as it is and a backup can still recover it.
 */
@Composable
private fun UnreadableDataScreen() {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.ReportProblem,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("Saved data can't be read", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "The savings file on this device is encrypted with a key this app can no longer use. " +
                "That usually means it arrived from another phone, where the key stays behind.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Nothing has been changed or deleted. Restore your encrypted backup to recover, or " +
                "reinstall the app to start over.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

