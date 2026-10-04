// Force-clear conflicting environment variable to fix AndroidLocationsBuildService initialization.
// This resolves the conflict between ANDROID_PREFS_ROOT and ANDROID_USER_HOME.
System.clearProperty("ANDROID_PREFS_ROOT")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                ivy {
                    name = "SherpaAndroidReleases"
                    url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
                    patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeGroup("com.k2fsa.sherpa") }
        }
    }
}

rootProject.name = "Fancy AI"
include(":app")
include(":engine")
include(":image")
include(":memory")
include(":vision")
include(":voice")
include(":terminal")
