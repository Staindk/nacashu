plugins {
    alias(libs.plugins.android.application) apply false
    // AGP compiles Kotlin itself; this only pins the Kotlin version it uses.
    alias(libs.plugins.kotlin.android) apply false
}
