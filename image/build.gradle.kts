plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mrj.fancyai.image"
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
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
        }
        consumerProguardFiles("consumer-rules.pro")
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines)
}

// Builds native weight writers and the QNN compiler allocator; model libraries are assets.
abstract class CompileQnnConversion @Inject constructor(
    private val process: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory abstract val sources: DirectoryProperty
    @get:InputFile abstract val cppCompiler: RegularFileProperty
    @get:InputFile abstract val cCompiler: RegularFileProperty
    @get:InputFile abstract val ndkRevision: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun compile() {
        val directory = output.get().dir("arm64-v8a").asFile.apply { mkdirs() }
        val src = sources.get().asFile
        for ((source, executable) in listOf(
            "tplconv.cpp" to "libaura_tplconv.so",
            "componentconv.cpp" to "libaura_componentconv.so",
        )) {
            process.exec {
                commandLine(
                    cppCompiler.get().asFile, "-O2", "-std=c++17",
                    "-ffp-contract=off", "-static-libstdc++", "-Wl,-z,max-page-size=16384",
                    "-Wl,-z,common-page-size=16384", src.resolve(source),
                    "-o", directory.resolve(executable),
                )
            }
        }
        val heapObject = temporaryDir.resolve("compiler_heap.o")
        process.exec {
            commandLine(
                cCompiler.get().asFile, "-O2", "-std=c11",
                "-fPIC", "-fno-builtin", "-Wall", "-Wextra",
                "-c", src.resolve("compiler_heap.c"), "-o", heapObject,
            )
        }
        process.exec {
            commandLine(
                cppCompiler.get().asFile, "-O2", "-std=c++17",
                "-fPIC", "-shared", "-fno-builtin", "-Wall", "-Wextra",
                "-static-libstdc++", "-Wl,--exclude-libs,ALL",
                "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384", "-Wl,-z,defs",
                "-Wl,--version-script=" + src.resolve("compiler_heap.map"),
                heapObject, src.resolve("compiler_new.cpp"), "-ldl", "-o", directory.resolve("libcompiler_heap.so"),
            )
        }
    }
}

val compileQnnConversion = tasks.register<CompileQnnConversion>("compileQnnConversion") {
    description = "Build the checkpoint weight converters and QNN compiler allocator."
    sources.set(layout.projectDirectory.dir("src/main/cpp/sd/third_party/aura_qnn"))
    val ndk = androidComponents.sdkComponents.ndkDirectory
    ndkRevision.set(ndk.map { it.file("source.properties") })
    cppCompiler.set(ndk.map { it.file("toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang++") })
    cCompiler.set(ndk.map { it.file("toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang") })
    output.set(layout.buildDirectory.dir("generated/qnnConversion/jniLibs"))
}
androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(compileQnnConversion, CompileQnnConversion::output)
}
