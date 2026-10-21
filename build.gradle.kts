buildscript {
    dependencies {
        // Bump AGP's bundled KGP to the catalog Kotlin so it stays aligned with
        // the Compose, serialization, and Parcelize compiler plugins.
        classpath(libs.kotlin.gradle.plugin) {
            version { strictly(libs.versions.kotlin.get()) }
        }
    }
}

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}
