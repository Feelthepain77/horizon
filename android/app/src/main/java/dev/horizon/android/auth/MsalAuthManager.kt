package dev.horizon.android.auth

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import dev.horizon.android.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * MSAL-backed sign-in. Created with the app context; throws from [init] if
 * `res/raw/auth_config.json` is still carrying placeholders (HorizonApp
 * falls back to [StubAuthManager] in that case).
 *
 * Scope notes: the Minecraft launcher chain starts with the Xbox Live
 * `sign-in` scope; `offline_access` keeps the session across restarts. The
 * XBL -> XSTS -> minecraftservices legs are documented in PORTING.md and
 * land with the entitlement/multiplayer phase.
 */
class MsalAuthManager(context: Context) : AuthManager {

    private val app: ISingleAccountPublicClientApplication =
        PublicClientApplication.createSingleAccountPublicClientApplication(
            context,
            R.raw.auth_config,
        )

    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    override val state: StateFlow<AuthState> = _state

    init {
        // Restore a previous session silently, if any.
        app.getCurrentAccountAsync(
            object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                override fun onAccountLoaded(account: IAccount?) {
                    account?.let { _state.value = AuthState.SignedIn(it.toProfile()) }
                }

                override fun onAccountChanged(prior: IAccount?, current: IAccount?) {
                    _state.value =
                        current?.let { AuthState.SignedIn(it.toProfile()) } ?: AuthState.SignedOut
                }

                override fun onError(exception: MsalException) {
                    // Silent restore failure is non-fatal; the user signs in again.
                }
            },
        )
    }

    override suspend fun signIn(activity: Activity) {
        val result = suspendCancellableCoroutine { cont ->
            val callback = object : AuthenticationCallback {
                override fun onSuccess(result: IAuthenticationResult) {
                    if (cont.isActive) cont.resume(result)
                }

                override fun onError(exception: MsalException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }

                override fun onCancel() {
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException("sign-in cancelled"),
                    )
                }
            }
            // Empty loginHint lets the user pick any account.
            app.signIn(activity, "", SCOPES, callback)
        }
        _state.value = AuthState.SignedIn(result.account.toProfile())
    }

    override fun signOut() {
        app.signOut(
            object : ISingleAccountPublicClientApplication.SignOutCallback {
                override fun onSignOut() {
                    _state.value = AuthState.SignedOut
                }

                override fun onError(exception: MsalException) {
                    // Local state follows the request regardless.
                    _state.value = AuthState.SignedOut
                }
            },
        )
    }

    private fun IAccount.toProfile(): McProfile {
        // `id` is the <uid>.<tid> home account id; the local part is the MSA
        // user id. The real Minecraft name/UUID come from the entitlement
        // chain (PORTING.md § "Auth & entitlements").
        val local = id?.substringBefore('.')?.takeIf { it.isNotBlank() }
        return McProfile(
            username = username ?: "Player",
            msUserId = local,
        )
    }

    private companion object {
        val SCOPES = arrayOf("XboxLive.signin", "offline_access")
    }
}
