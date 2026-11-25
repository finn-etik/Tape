plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied anywhere (AGP 9 has built-in Kotlin); declared so Gradle uses this
    // Kotlin version instead of the older one AGP ships with.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
