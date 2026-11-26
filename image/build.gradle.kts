import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

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

abstract class CompileCompilerHeap @Inject constructor(
    private val process: ExecOperations,
) : DefaultTask() {
    @get:InputFile abstract val source: RegularFileProperty
    @get:InputFile abstract val cppSource: RegularFileProperty
    @get:InputFile abstract val symbols: RegularFileProperty
    @get:InputFile abstract val compiler: RegularFileProperty
    @get:InputFile abstract val ndkRevision: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun compile() {
        val output = outputDirectory.get().file("arm64-v8a/libcompiler_heap.so").asFile
        output.parentFile.mkdirs()
        val cObject = temporaryDir.resolve("compiler_heap.o")
        process.exec {
            commandLine(
                compiler.get().asFile,
                "-O2", "-std=c11", "-fPIC", "-fno-builtin", "-Wall", "-Wextra",
                "-c", source.get().asFile, "-o", cObject,
            )
        }
        process.exec {
            commandLine(
                compiler.get().asFile.absolutePath.let { c ->
                    if (c.endsWith(".cmd")) c.removeSuffix(".cmd") + "++.cmd" else "$c++"
                },
                "-O2", "-std=c++17", "-fPIC", "-shared", "-fno-builtin", "-Wall", "-Wextra",
                "-static-libstdc++", "-Wl,--exclude-libs,ALL",
                "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
                "-Wl,-z,defs", "-Wl,--version-script=${symbols.get().asFile}",
                cObject, cppSource.get().asFile, "-ldl", "-o", output,
            )
        }
    }
}

abstract class CompileQnnCompiler @Inject constructor(
    private val process: ExecOperations,
) : DefaultTask() {
    @get:InputFile abstract val cmakeExe: RegularFileProperty
    @get:InputFile abstract val ninjaExe: RegularFileProperty
    @get:InputFile abstract val toolchainFile: RegularFileProperty
    @get:InputFile abstract val ndkRevision: RegularFileProperty
    @get:InputFile abstract val cmakeLists: RegularFileProperty
    @get:InputFile abstract val convertSource: RegularFileProperty
    @get:InputFile abstract val graphsSource: RegularFileProperty
    @get:InputFile abstract val graphsHeader: RegularFileProperty
    @get:InputFile abstract val bufferHeader: RegularFileProperty
    @get:InputDirectory abstract val safetensorDir: DirectoryProperty
    @get:InputDirectory abstract val jsonDir: DirectoryProperty
    @get:InputDirectory abstract val qnnIncludeDir: DirectoryProperty
    @get:Internal abstract val sdDirPath: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun compile() {
        val outAbiDir = outputDirectory.get().dir("arm64-v8a").asFile
        outAbiDir.mkdirs()
        val buildDir = temporaryDir.resolve("cmake-build").apply { mkdirs() }
        process.exec {
            commandLine(
                cmakeExe.get().asFile.absolutePath,
                "-S", cmakeLists.get().asFile.parentFile.absolutePath,
                "-B", buildDir.absolutePath,
                "-G", "Ninja",
                "-DCMAKE_MAKE_PROGRAM=${ninjaExe.get().asFile.absolutePath}",
                "-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.get().asFile.absolutePath}",
                "-DANDROID_ABI=arm64-v8a",
                "-DANDROID_PLATFORM=android-33",
                "-DANDROID_STL=c++_static",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DSD_DIR=${sdDirPath.get()}",
                "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=${outAbiDir.absolutePath}",
            )
        }
        process.exec {
            commandLine(
                cmakeExe.get().asFile.absolutePath,
                "--build", buildDir.absolutePath,
                "--target", "fancy_qnn_compile",
            )
        }
    }
}

val qnnNdkDir = androidComponents.sdkComponents.ndkDirectory
val qnnSdkDir = androidComponents.sdkComponents.sdkDirectory
val osName = System.getProperty("os.name").lowercase()
val ndkHost = when {
    osName.startsWith("windows") -> "windows-x86_64"
    osName.startsWith("mac") -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val ndkWrapperSuffix = if (osName.startsWith("windows")) ".cmd" else ""

val compileCompilerHeap = tasks.register<CompileCompilerHeap>("compileCompilerHeap") {
    group = "build"
    description = "Build storage-backed LD_PRELOAD allocator for QNN SDXL compilation."
    source.set(layout.projectDirectory.file("src/main/cpp/sd/compiler_heap/compiler_heap.c"))
    cppSource.set(layout.projectDirectory.file("src/main/cpp/sd/compiler_heap/compiler_new.cpp"))
    symbols.set(layout.projectDirectory.file("src/main/cpp/sd/compiler_heap/compiler_heap.map"))
    ndkRevision.set(qnnNdkDir.map { it.file("source.properties") })
    compiler.set(
        qnnNdkDir.map {
            it.file("toolchains/llvm/prebuilt/$ndkHost/bin/aarch64-linux-android33-clang$ndkWrapperSuffix")
        },
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/compilerHeap/jniLibs"))
}

val compileQnnCompiler = tasks.register<CompileQnnCompiler>("compileQnnCompiler") {
    group = "build"
    description = "Build on-device QNN SD1.5/SDXL compiler executable."
    cmakeExe.set(qnnSdkDir.map { it.file("cmake/4.1.2/bin/cmake") })
    ninjaExe.set(qnnSdkDir.map { it.file("cmake/4.1.2/bin/ninja") })
    toolchainFile.set(qnnNdkDir.map { it.file("build/cmake/android.toolchain.cmake") })
    ndkRevision.set(qnnNdkDir.map { it.file("source.properties") })
    cmakeLists.set(layout.projectDirectory.file("src/main/cpp/sd/qnn_compile/CMakeLists.txt"))
    convertSource.set(layout.projectDirectory.file("src/main/cpp/sd/qnn_convert.cpp"))
    graphsSource.set(layout.projectDirectory.file("src/main/cpp/sd/qnn_sd_graphs.cpp"))
    graphsHeader.set(layout.projectDirectory.file("src/main/cpp/sd/qnn_sd_graphs.h"))
    bufferHeader.set(layout.projectDirectory.file("src/main/cpp/sd/file_backed_buffer.h"))
    safetensorDir.set(layout.projectDirectory.dir("src/main/cpp/sd/safetensor2mnn"))
    jsonDir.set(layout.projectDirectory.dir("src/main/cpp/sd/third_party/json"))
    qnnIncludeDir.set(layout.projectDirectory.dir("src/main/cpp/sd/qnn-sdk/include/QNN"))
    sdDirPath.set(layout.projectDirectory.dir("src/main/cpp/sd").asFile.absolutePath)
    outputDirectory.set(layout.buildDirectory.dir("generated/qnnCompile/jniLibs"))
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(compileCompilerHeap, CompileCompilerHeap::outputDirectory)
    variant.sources.jniLibs?.addGeneratedSourceDirectory(compileQnnCompiler, CompileQnnCompiler::outputDirectory)
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
