package dev.horizon.android.launcher

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.horizon.android.HorizonApp
import dev.horizon.android.auth.AuthState
import dev.horizon.android.auth.LocalAccount
import dev.horizon.android.auth.LocalAccountManager
import dev.horizon.android.game.HorizonGameActivity
import dev.horizon.android.game.HorizonNative
import dev.horizon.android.setup.ImportState
import dev.horizon.android.setup.StorageLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Launcher UI state + actions. Plain observable state (no ViewModel) — the
 * launcher is small, and this keeps the dependency surface tight.
 */
class LauncherState(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    // --- auth ---------------------------------------------------------------
    val auth = (context.applicationContext as HorizonApp).authManager
    val isStubAuth: Boolean get() = auth::class.simpleName == "StubAuthManager"

    // --- local accounts (no Microsoft account needed) -------------------------
    private val local = LocalAccountManager(context)
    var localAccounts: List<LocalAccount> by mutableStateOf(local.accounts())
        private set
    var activeLocalUuid: String? by mutableStateOf(local.activeUuid())

    /** The identity the next game runs with: the signed-in Microsoft account,
     *  or the selected local account — whichever is active. */
    fun activeProfile(): dev.horizon.android.auth.McProfile? {
        (auth.state.value as? AuthState.SignedIn)?.profile?.let { return it }
        return localAccounts.firstOrNull { it.uuid == activeLocalUuid }?.let {
            dev.horizon.android.auth.McProfile(username = it.name, msUserId = null, uuid = it.uuid)
        }
    }

    fun createLocalAccount(name: String): Boolean {
        val account = local.create(name) ?: return false
        refreshAccounts()
        local.select(account.uuid)
        activeLocalUuid = account.uuid
        return true
    }

    fun selectLocalAccount(uuid: String?) {
        local.select(uuid)
        activeLocalUuid = uuid
    }

    fun deleteLocalAccount(uuid: String) {
        local.delete(uuid)
        refreshAccounts()
        if (activeLocalUuid == uuid) activeLocalUuid = null
    }

    private fun refreshAccounts() {
        localAccounts = local.accounts()
    }

    // --- game files ----------------------------------------------------------
    private val files = (context.applicationContext as HorizonApp).gameFiles
    val importState: ImportState get() = files.importState.value
    var hasPrepared: Boolean by mutableStateOf(files.hasPrepared())
        private set
    var importedBytes: Long by mutableStateOf(files.importedBytes())
        private set

    /** Latest validation events (from Rust) for the checklist UI. */
    var validationLog: List<String> by mutableStateOf(emptyList())
        private set

    // --- device ---------------------------------------------------------------
    var nativeVersion: String by mutableStateOf("…")
        private set
    var gpuInfo: String by mutableStateOf("not initialized (start the game once)")
        private set

    // --- settings (persisted as plain files; swap for DataStore if it grows) --
    var renderDistance: Int by mutableStateOf(8)
    var sensitivity: Float by mutableStateOf(1.0f)
    var invertLook: Boolean by mutableStateOf(false)
    var graphicsPreset: String by mutableStateOf("medium")

    init {
        scope.launch { nativeVersion = HorizonNative.version() }
        scope.launch { gpuInfo = HorizonNative.gpuInfo() }
        scope.launch {
            HorizonNative.events.collect { event ->
                if (event.type == "validate") {
                    validationLog = (validationLog + event.payload).takeLast(12)
                    if (event.payload.startsWith("result:")) refreshFiles()
                }
            }
        }
    }

    fun signIn(activity: android.app.Activity) = scope.launch { auth.signIn(activity) }
    fun signOut() = scope.launch { auth.signOut() }

    fun refreshFiles() {
        hasPrepared = files.hasPrepared()
        importedBytes = files.importedBytes()
    }

    fun importZip(uri: android.net.Uri) = scope.launch {
        files.importFromZip(uri)
        refreshFiles()
    }

    fun importTree(uri: android.net.Uri) = scope.launch {
        files.importFromTree(uri)
        refreshFiles()
    }

    fun validate() = scope.launch {
        validationLog = emptyList()
        HorizonNative.validateGameFiles(StorageLayout.preparedDir(context).absolutePath)
        refreshFiles()
    }

    fun deleteFiles() {
        files.deleteAll()
        refreshFiles()
        validationLog = emptyList()
    }

    /**
     * Hand the configuration to Rust, then start the game activity.
     *
     * The session file must be written right before the start (the client
     * refuses one older than 120 s and deletes it after reading): a local
     * account gets an offline-UUID session; a Microsoft account that has
     * completed the entitlement chain (a profile uuid) gets the same file
     * with its own uuid, and one without it falls back to `--name`.
     */
    fun play() {
        if (!HorizonNative.available) {
            return // The Play button is disabled in this state; belt and braces.
        }
        val profile = activeProfile()
        val session = StorageLayout.sessionFile(context)
        if (profile != null) {
            LocalAccountManager.writeSession(session, profile.username, profile.uuid ?: "")
        }
        val config = JSONObject().apply {
            put("prepared_dir", StorageLayout.preparedDir(context).absolutePath)
            put("home_dir", StorageLayout.homeDir(context).absolutePath)
            put("session_file", session.absolutePath)
            profile?.let { p ->
                put("player_name", p.username)
                p.uuid?.let { put("uuid", it) }
            }
            put("render_distance", renderDistance)
            put("graphics_preset", graphicsPreset)
            put("invert_look", invertLook)
            put("sensitivity", sensitivity)
        }
        scope.launch {
            if (HorizonNative.configure(config.toString())) {
                context.startActivity(
                    Intent(context, HorizonGameActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }
    }

    val ready: Boolean get() = hasPrepared
}
