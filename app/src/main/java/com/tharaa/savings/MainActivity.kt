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
