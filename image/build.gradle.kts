plugins {
    alias(libs.plugins.android.library)
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
