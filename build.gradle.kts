plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 has built-in Kotlin; declaring KGP here pins the Kotlin version to 2.4.x,
    // which the Meshtastic SDK 0.1.0 artifacts are compiled with.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
