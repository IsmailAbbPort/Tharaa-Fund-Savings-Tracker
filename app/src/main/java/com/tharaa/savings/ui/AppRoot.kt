package com.tharaa.savings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.tharaa.savings.*

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
internal fun AppRoot() {
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

