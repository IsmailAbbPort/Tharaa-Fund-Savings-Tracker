package com.backupkit

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

/**
 * Google sign-in scoped to Drive's `drive.file` permission - the app can only see files it
 * creates, so backups live in a visible "Auto Backups" folder without exposing the rest of Drive.
 */
internal object GoogleAccountAuth {

    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    private fun options(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_FILE_SCOPE))
            .build()

    fun signInIntent(context: Context): Intent =
        GoogleSignIn.getClient(context, options()).signInIntent

    /** True if the returned intent held a successfully signed-in account. */
    fun handleSignInResult(intent: Intent?): Boolean = try {
        GoogleSignIn.getSignedInAccountFromIntent(intent).getResult(ApiException::class.java)
        true
    } catch (e: Exception) {
        false
    }

    fun lastAccount(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    fun signedInEmail(context: Context): String? = lastAccount(context)?.email

    fun signOut(context: Context, onDone: () -> Unit) {
        GoogleSignIn.getClient(context, options()).signOut().addOnCompleteListener { onDone() }
    }

    /** Blocking OAuth token fetch for the Drive scope. Call off the main thread. */
    fun fetchToken(context: Context, account: GoogleSignInAccount): String {
        val androidAccount = account.account
            ?: throw BackupException("Signed-in account has no Drive access")
        return GoogleAuthUtil.getToken(context, androidAccount, "oauth2:$DRIVE_FILE_SCOPE")
    }
}
