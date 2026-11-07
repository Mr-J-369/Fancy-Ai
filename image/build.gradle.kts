import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

val localHexagonSdk = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { contents ->
        Properties().apply { load(contents.reader()) }.getProperty("hexagon.sdk.dir").orEmpty()
    }
val hexagonSdkRoot = providers.gradleProperty("hexagon.sdk.dir")
    .orElse(providers.environmentVariable("HEXAGON_SDK_ROOT"))
    .orElse(localHexagonSdk)
val hexagonToolsRoot = hexagonSdkRoot.map { sdkRoot ->
    val toolsDir = File(sdkRoot, "tools/HEXAGON_Tools")
    val latest = toolsDir.list()?.maxOrNull()
    if (latest != null) "${toolsDir.absolutePath}/$latest" else toolsDir.absolutePath
}

android {
    namespace = "com.mrj.fancyai.image"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 33
        ndk {
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DHEXAGON_SDK_ROOT=${hexagonSdkRoot.get()}",
                    "-DHEXAGON_TOOLS_ROOT=${hexagonToolsRoot.get()}",
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
        jniLibs.keepDebugSymbols += "**/libMNN_htpops_skel*.so"
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.named<Delete>("clean") {
    delete(layout.projectDirectory.dir(".cxx"))
}

val buildDitEngine = tasks.register<Exec>("buildDitEngine") {
    description = "Compiles libdit_engine.so and Hexagon DSP skels using tools/dit_engine/build.sh"
    workingDir = rootProject.file("tools/dit_engine")
    commandLine("./build.sh")

    inputs.dir(rootProject.file("tools/dit_engine/src/stable-diffusion.cpp/src"))
    inputs.file(rootProject.file("tools/dit_engine/DitEngine.cpp"))
    inputs.file(rootProject.file("image/src/main/cpp/sd/DitEngine.h"))
    inputs.file(rootProject.file("tools/dit_engine/build.sh"))
    outputs.file(rootProject.file("image/src/main/jniLibs/arm64-v8a/libdit_engine.so"))
}

tasks.named("preBuild") {
    dependsOn(buildDitEngine)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines)
}
