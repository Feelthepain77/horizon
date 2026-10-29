package dev.horizon.android.launcher

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.horizon.android.HorizonApp
import dev.horizon.android.auth.AuthState
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

    // --- game files ----------------------------------------------------------
    private val files = (context.applicationContext as HorizonApp).gameFiles
    val importState: ImportState get() = files.importState.value
    var imported: Boolean by mutableStateOf(files.looksImported())
        private set
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
        imported = files.looksImported()
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
        HorizonNative.validateGameFiles(StorageLayout.gameDir(context).absolutePath)
        refreshFiles()
    }

    fun deleteFiles() {
        files.deleteAll()
        refreshFiles()
        validationLog = emptyList()
    }

    /** Hand the configuration to Rust, then start the game activity. */
    fun play() {
        if (!HorizonNative.available) {
            return // The Play button is disabled in this state; belt and braces.
        }
        val config = JSONObject().apply {
            put("game_dir", StorageLayout.gameDir(context).absolutePath)
            put("local_dir", StorageLayout.localDir(context).absolutePath)
            (auth.state.value as? AuthState.SignedIn)?.profile?.let { p ->
                put("username", p.username)
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

    val ready: Boolean get() = imported
}
