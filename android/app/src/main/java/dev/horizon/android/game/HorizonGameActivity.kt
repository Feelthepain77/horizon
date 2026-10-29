package dev.horizon.android.game

import android.app.NativeActivity
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * The game window. Everything interesting happens in Rust
 * (`libhorizon_android.so` — see crates/horizon-android/src/lib.rs): this
 * Kotlin subclass exists only to keep the screen on and pin immersive
 * fullscreen across focus changes.
 */
class HorizonGameActivity : NativeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = window.insetsController ?: return
        controller.hide(WindowInsets.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
