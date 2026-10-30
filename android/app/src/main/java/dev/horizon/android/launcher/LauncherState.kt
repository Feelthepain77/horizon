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
import kotlinx.coroutines.Dispatchers
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

    /** Latest validation/prepare events (from Rust) for the checklist UI. */
    var validationLog: List<String> by mutableStateOf(emptyList())
        private set

    /** The tail of the last game run's log, or the head of its crash report
     *  when it wrote one. `logs/latest.log` is rolled at every start, so it
     *  is always the most recent run; the crash reports are dated files.
     *  Empty until the game has run once. Written from Rust's side too: a
     *  `game` event with an `error:` payload arrives the moment the client
     *  dies, and this list is refreshed from disk when the launcher comes
     *  back to the foreground. */
    var lastGameRun: List<String> by mutableStateOf(emptyList())
        private set

    /** True when the prepared root passed the play-time inspection (all
     *  exporter tables present) — the gate for the PLAY button. Set from the
     *  inspection's synchronous return value (never from events — before the
     *  sink is cached those cannot fire, and the gate would deadlock). */
    var usable: Boolean by mutableStateOf(false)
        private set

    /** What the last inspection found missing (file or directory names).
     *  Same source as [usable]: the inspection's return value. */
    var missing: List<String> by mutableStateOf(emptyList())
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
                when (event.type) {
                    "validate" -> {
                        // Progress lines only — the authoritative usable/
                        // missing state comes from the inspection's return
                        // value in validate(); never from events, which
                        // cannot fire before the sink class is cached.
                        validationLog = (validationLog + event.payload).takeLast(12)
                        if (event.payload.startsWith("result:")) refreshFiles()
                    }
                    "prepare" -> {
                        // progress lines from the on-device prepare; same pane
                        validationLog = (validationLog + event.payload).takeLast(12)
                        if (event.payload.startsWith("result:")) refreshFiles()
                    }
                    "game" -> {
                        // The game session just ended. An error line says why
                        // (it also goes to the crash report on disk); refresh
                        // the last-run panel from the files either way, since
                        // the report carries the full story.
                        if (event.payload.startsWith("error:")) {
                            validationLog = (validationLog + event.payload).takeLast(12)
                        }
                        refreshAfterGame()
                    }
                }
            }
        }
        // Show the current state (usable / what is missing) right at launch.
        if (files.hasPrepared()) validate()
        // And why the last game run ended, if there was one.
        refreshAfterGame()
    }

    fun signIn(activity: android.app.Activity) = scope.launch { auth.signIn(activity) }
    fun signOut() = scope.launch { auth.signOut() }

    fun refreshFiles() {
        hasPrepared = files.hasPrepared()
        importedBytes = files.importedBytes()
    }

    fun importZip(uri: android.net.Uri) = scope.launch {
        files.importFromZip(uri)
        afterImport()
    }

    fun importTree(uri: android.net.Uri) = scope.launch {
        files.importFromTree(uri)
        afterImport()
    }

    /** The no-PC path: stage the picked .minecraft and prepare on device. */
    fun importMinecraft(uri: android.net.Uri) = scope.launch {
        files.importFromMinecraft(uri)
        afterImport()
    }

    /** Refresh after any import and re-run the inspection, so the PLAY gate
     *  and the missing list always reflect the current state. */
    private fun afterImport() {
        refreshFiles()
        validate()
    }

    fun validate() {
        if (!files.hasPrepared()) {
            // Nothing to inspect yet. Surface the last prepare report instead,
            // so a failed prepare is never a silent "0 items missing".
            files.lastReport?.let { report ->
                usable = false
                missing = report.missing
                report.error?.let { err ->
                    validationLog = listOf("error: ${err.take(200)}")
                }
            }
            return
        }
        scope.launch {
            validationLog = emptyList()
            missing = emptyList()
            usable = false
            // The synchronous return is the authority for the PLAY gate:
            // whatever the prepared root holds, the UI reflects it now.
            val inspection =
                HorizonNative.validateGameFiles(StorageLayout.preparedDir(context).absolutePath)
            usable = inspection.usable
            missing = inspection.missing
            refreshFiles()
        }
    }

    fun deleteFiles() {
        files.deleteAll()
        refreshFiles()
        validationLog = emptyList()
        missing = emptyList()
        usable = false
    }

    /** Re-read the last game run's log/crash report. Called when the game
     *  activity goes away (the launcher resumes behind it), when a `game`
     *  event arrives, and once at launch. */
    fun refreshAfterGame() {
        scope.launch(Dispatchers.IO) { lastGameRun = readLastGameRun() }
    }

    /** The newest client crash report (its head — the error and its stack
     *  lead), else the tail of the last session's `logs/latest.log`. */
    private fun readLastGameRun(): List<String> {
        val home = StorageLayout.homeDir(context)
        val crash = home.resolve("crash-reports")
            .listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash-") && it.name.endsWith("-client.txt") }
            ?.maxByOrNull { it.lastModified() }
        if (crash != null) {
            // The report opens with a comment, the time and the description,
            // then the error with its stack — the head carries the reason.
            val head = crash.useLines { lines -> lines.take(16).toList() }
            return listOf("crash report — ${crash.name}") + head
        }
        val log = home.resolve("logs").resolve("latest.log")
        if (log.isFile) {
            val tail = log.useLines { lines -> lines.toList().takeLast(14) }
            return listOf("log tail of the last game run:") + tail
        }
        return emptyList()
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

    val ready: Boolean get() = hasPrepared && usable
}
