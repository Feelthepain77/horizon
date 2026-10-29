package dev.horizon.android.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Kotlin side of the Rust JNI contract (crates/horizon-android/src/jni_bridge.rs).
 *
 * All native entry points live here; keep the two files in sync.
 */
object HorizonNative {

    /** An event pushed from Rust (import/validate progress, fatal errors). */
    data class NativeEvent(val type: String, val payload: String) {
        val isOk: Boolean get() = payload.startsWith("ok:") || payload.startsWith("result:")
    }

    private var loaded = false

    /** Progress events from Rust. Collect on the main thread. */
    private val _events = MutableSharedFlow<NativeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NativeEvent> = _events

    /** Whether libhorizon_android.so was found. False until the Rust build runs. */
    var available: Boolean = false
        private set

    fun load() {
        if (!loaded) {
            available = try {
                System.loadLibrary("horizon_android")
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
            loaded = true
        }
    }

    // --- Rust -> Kotlin callback (called on a Rust worker thread) ----------

    /**
     * Invoked from Rust via JNI (see `emit_event` in jni_bridge.rs). Must stay
     * static, public, and take a single String — the cached method id targets
     * exactly this signature.
     */
    @JvmStatic
    fun onNativeEvent(json: String) {
        runCatching {
            val obj = JSONObject(json)
            NativeEvent(obj.optString("type"), obj.optString("payload"))
        }.getOrNull()?.let { _events.tryEmit(it) }
    }

    // --- Kotlin -> Rust ------------------------------------------------------

    /** Configure the next game session. Call before starting HorizonGameActivity. */
    suspend fun configure(json: String): Boolean = withContext(Dispatchers.IO) {
        if (!available) false else nativeConfigure(json)
    }

    suspend fun version(): String = withContext(Dispatchers.IO) {
        if (!available) "native library not loaded — run scripts/build-android-libs.sh" else nativeGetVersion()
    }

    /** Adapter string once the game has booted at least once, else a placeholder. */
    suspend fun gpuInfo(): String = withContext(Dispatchers.IO) {
        if (!available) "native library not loaded" else nativeGpuInfo()
    }

    /** Structural validation of the imported game files; emits `validate` events. */
    suspend fun validateGameFiles(gameDir: String): Boolean = withContext(Dispatchers.IO) {
        if (!available) false else nativeValidateGameFiles(gameDir)
    }

    /**
     * The Minecraft version this build is pinned to (research-lock.json).
     * Cached; "" when the native library is unavailable (StorageLayout then
     * degrades to "nothing prepared" — the same state as before a first run).
     */
    fun pinnedVersion(): String {
        synchronized(this) {
            pinned ?: run {
                pinned = if (available) {
                    runCatching { nativePinnedVersion() }.getOrDefault("")
                } else {
                    ""
                }
                return pinned!!
            }
            return pinned!!
        }
    }

    /**
     * Run the on-device prepare over the SAF-staged .minecraft: import and
     * check the pinned jars, extract the client JAR's resources, copy the
     * asset store's sounds and overlays. Returns the report JSON
     * `{ok, version, error?, missing, prepared_bytes}`; progress lines arrive
     * as `prepare` events and always end with `result:usable|incomplete`.
     */
    suspend fun prepareFromMinecraft(filesRoot: String): String = withContext(Dispatchers.IO) {
        if (!available) {
            """{"ok":false,"error":"native library not loaded — run scripts/build-android-libs.sh"}"""
        } else {
            nativePrepareFromMinecraft(filesRoot)
        }
    }

    // --- native declarations (signatures must match jni_bridge.rs) ----------

    private external fun nativeConfigure(json: String): Boolean
    private external fun nativeGetVersion(): String
    private external fun nativeGpuInfo(): String
    private external fun nativeValidateGameFiles(gameDir: String): Boolean
    private external fun nativePinnedVersion(): String
    private external fun nativePrepareFromMinecraft(filesRoot: String): String

    /** Cache for [pinnedVersion]. */
    private var pinned: String? = null
}
