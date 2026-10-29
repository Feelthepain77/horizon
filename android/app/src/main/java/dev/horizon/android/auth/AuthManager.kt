package dev.horizon.android.auth

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/** Signed-in identity as far as the launcher knows it. */
data class McProfile(
    val username: String,
    /** Microsoft account id (local part of `homeAccountId`). */
    val msUserId: String?,
    /** Minecraft profile UUID — filled by the entitlement step, see PORTING.md. */
    val uuid: String? = null,
)

sealed interface AuthState {
    data object SignedOut : AuthState
    data class SignedIn(val profile: McProfile) : AuthState
}

/**
 * Sign-in surface. MSAL implements the real flow; [StubAuthManager] keeps the
 * launcher usable before an Azure app registration exists.
 *
 * NOTE (PORTING.md § "Auth & entitlements"): after MSAL returns an MSA token,
 * the desktop launcher completes the chain
 *   MSA token -> Xbox Live (userhash + XSTS) -> minecraftservices profile
 * to prove Minecraft: Java Edition ownership and fetch the real player name +
 * UUID. The Android port performs the same calls with the same client id;
 * that step lands with the multiplayer phase of the porting roadmap.
 */
interface AuthManager {
    val state: StateFlow<AuthState>
    suspend fun signIn(activity: Activity)
    fun signOut()
}

/**
 * Local-profile fallback. Generates a stable-looking "PlayerNNN" identity so
 * single-device testing (demo renderer, imports, settings) works without any
 * Azure registration.
 */
class StubAuthManager : AuthManager {
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    override val state: StateFlow<AuthState> = _state

    override suspend fun signIn(activity: Activity) {
        val name = "Player%03d".format(Random.nextInt(1000))
        _state.value = AuthState.SignedIn(McProfile(username = name, msUserId = null))
    }

    override fun signOut() {
        _state.value = AuthState.SignedOut
    }
}
