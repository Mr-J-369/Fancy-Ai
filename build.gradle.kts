buildscript {
    dependencies {
        // AGP built-in Kotlin otherwise stays on AGP's bundled compiler. Keep it aligned with
        // the serialization, Compose, and Parcelize compiler plugins in the version catalog.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}
