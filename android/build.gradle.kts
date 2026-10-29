// Root build file for the Horizon Android launcher.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // AGP >= 9 ships built-in Kotlin support; on AGP 8.x you would also apply:
    // alias(libs.plugins.kotlin.android) apply false
}
