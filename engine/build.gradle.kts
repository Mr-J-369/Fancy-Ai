import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    id("kotlin-parcelize")
    alias(libs.plugins.kotlin.serialization)
}

val localHexagonSdk = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { contents ->
        Properties().apply { load(contents.reader()) }.getProperty("hexagon.sdk.dir").orEmpty()
    }
val hexagonSdkRoot = providers.gradleProperty("hexagon.sdk.dir")
    .orElse(providers.environmentVariable("HEXAGON_SDK_ROOT"))
    .orElse(localHexagonSdk)

android {
    namespace = "com.mrj.fancyai.engine"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("github") {
            dimension = "distribution"
            isDefault = true
        }
    }

    defaultConfig {
        minSdk = 33
        ndk {
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_PLATFORM=android-33",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DHEXAGON_SDK_ROOT=${hexagonSdkRoot.get()}",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        aidl = true
    }

    lint {
        lintConfig = rootProject.file("app/lint.xml")
        checkAllWarnings = true
        warningsAsErrors = true
    }

    packaging {
        jniLibs.keepDebugSymbols += "**/libggml-htp-*.so"
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.litertlm)
    implementation(libs.androidx.annotation)
    implementation(libs.coroutines)
    implementation(libs.kotlinx.serialization.json)
}
