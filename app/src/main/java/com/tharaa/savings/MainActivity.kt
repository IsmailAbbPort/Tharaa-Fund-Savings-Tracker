package com.tharaa.savings

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.fragment.app.FragmentActivity
import com.tharaa.savings.ui.Gate

/**
 * The app's only activity. Every screen is a composable under [Gate]; see the `ui` package.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mark the window secure: the OS won't snapshot it for the app switcher, and screenshots
        // of your balances are blocked. Debug builds skip it, so the screens can be captured for
        // the README and driven by UI tests.
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        SavingsRepository.init(applicationContext)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Gate()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Close off any trip to a system screen. If it turned into leaving the app for a while,
        // this locks after all.
        SavingsRepository.endSystemExcursion()
    }

    override fun onStop() {
        super.onStop()
        // Remember the screen and any calculator inputs so reopening picks up where they left off.
        SavingsRepository.saveSession()
        // Re-lock the moment the app leaves the screen, so reopening shows the passcode first.
        // Guarded so a rotation/config change doesn't lock.
        if (isChangingConfigurations) return
        if (SavingsRepository.consumeSkipLock()) {
            // A file picker or permission prompt we opened ourselves: stay unlocked, but start
            // the clock, so a short hop doesn't turn into an open app left on a table.
            SavingsRepository.beginSystemExcursion()
        } else {
            SavingsRepository.lockSession()
        }
    }
}
