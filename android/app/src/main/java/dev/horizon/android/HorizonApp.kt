package dev.horizon.android

import android.app.Application
import dev.horizon.android.auth.AuthManager
import dev.horizon.android.auth.MsalAuthManager
import dev.horizon.android.auth.StubAuthManager
import dev.horizon.android.game.HorizonNative
import dev.horizon.android.setup.GameFilesManager

/**
 * Process entry point.
 *
 * Loads `libhorizon_android.so` (which captures the JavaVM in `JNI_OnLoad`)
 * and owns the app-scoped singletons. The game activity deliberately touches
 * none of this — Rust gets its configuration ahead of time via
 * [HorizonNative.configure] and owns the window from there.
 */
class HorizonApp : Application() {

    lateinit var authManager: AuthManager
        private set

    lateinit var gameFiles: GameFilesManager
        private set

    override fun onCreate() {
        super.onCreate()
        // Loading the library also runs JNI_OnLoad on this (main) thread.
        HorizonNative.load()

        authManager = try {
            MsalAuthManager(this)
        } catch (e: Exception) {
            // auth_config.json still contains placeholders — fall back to a
            // local profile so the rest of the launcher stays testable.
            // The UI shows a banner in this mode.
            StubAuthManager()
        }

        gameFiles = GameFilesManager(this)
    }

    companion object {
        @JvmStatic
        fun of(app: Application): HorizonApp = app as HorizonApp
    }
}
