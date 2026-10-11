package com.flownotes.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Google sign-in + Drive permission via Google Play services' AuthorizationClient.
 *
 * Only the `drive.file` scope is requested: the app can see and edit the files it creates
 * (the "Flow Notes" folder and its docs) and nothing else in the user's Drive.
 */
object GoogleAuth {
    private val scopes = listOf(
        Scope("https://www.googleapis.com/auth/drive.file"),
        Scope("email"),
    )

    sealed interface Result {
        data class Authorized(val accessToken: String, val email: String?) : Result
        /** The user has to pick an account / grant consent: launch this from an Activity. */
        data class NeedsConsent(val pendingIntent: PendingIntent) : Result
    }

    suspend fun authorize(context: Context): Result {
        val request = AuthorizationRequest.builder().setRequestedScopes(scopes).build()
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        return result.toResult()
    }

    /** Reads the outcome of the consent screen launched for [Result.NeedsConsent]. */
    fun fromConsentIntent(context: Context, data: Intent?): Result =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).toResult()

    private fun AuthorizationResult.toResult(): Result {
        val pending = pendingIntent
        if (hasResolution() && pending != null) return Result.NeedsConsent(pending)
        val token = accessToken ?: error("Google didn't return an access token")
        return Result.Authorized(token, toGoogleSignInAccount()?.email)
    }
}
