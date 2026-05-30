import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.ksp)
    // TODO: Re-add Hilt after resolving version compatibility
    // alias(libs.plugins.hilt.android)
}

extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "com.mrj.fancyai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mrj.fancyai"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "4.0.0"
        ndkVersion = "27.2.12479018"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
                // CPU-only build (FANCYAI_ENABLE_ACCELERATORS=OFF in CMakeLists). If you
                // re-enable the Vulkan backend, also add "-DANDROID_PLATFORM=android-28"
                // here — ggml-vulkan links Vulkan 1.1 symbols the NDK libvulkan only
                // exports from API 28.
                val hexagonSdkRoot = System.getenv("HEXAGON_SDK_ROOT")
                    ?: project.findProperty("HEXAGON_SDK_ROOT")?.toString()
                if (!hexagonSdkRoot.isNullOrBlank()) {
                    arguments += "-DHEXAGON_SDK_ROOT=$hexagonSdkRoot"
                    val hexagonToolsRoot = System.getenv("HEXAGON_TOOLS_ROOT")
                        ?: "$hexagonSdkRoot/tools/HEXAGON_Tools/19.0.07"
                    arguments += "-DHEXAGON_TOOLS_ROOT=$hexagonToolsRoot"
                    println("[fancy_ai] Hexagon SDK: $hexagonSdkRoot")
                    println("[fancy_ai] Hexagon Tools: $hexagonToolsRoot")
                } else {
                    println("[fancy_ai] HEXAGON_SDK_ROOT not set — CPU-only build")
                }
                
                // Add 16 KB page size support for Android 15+ (S25 Ultra, etc.)
                // Ensures native segments are aligned to the system's page size.
                arguments += "-DCMAKE_C_FLAGS=-Wl,-z,common-page-size=16384 -Wl,-z,max-page-size=16384"
                arguments += "-DCMAKE_CXX_FLAGS=-Wl,-z,common-page-size=16384 -Wl,-z,max-page-size=16384"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // FastRPC opens skel .so files via regular file I/O. With page-aligned
    // (zip-embedded) libs the path becomes "base.apk!/lib/arm64-v8a" which
    // fopen() cannot read. Force extraction so the DSP can load them.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Exclude libOpenCL.so: on Android 15+ a non-aligned ICD loader triggers
            // the "LOAD segment not aligned" (16 KB) error; the app resolves the
            // aligned libOpenCL.so from /vendor instead.
            excludes += "**/libOpenCL.so"
            // CPU-only build: the Qualcomm QNN + Hexagon HTP skel libs aren't used and
            // would just bloat the APK (~70 MB) and get probed at startup. They remain
            // in src/main/jniLibs — re-enabling FANCYAI_ENABLE_ACCELERATORS repackages
            // them (drop these two excludes when you do).
            excludes += "**/libQnn*.so"
            excludes += "**/libggml-htp-*.so"
        }
    }

    // Release signing: private credentials live in keystore.properties (gitignored).
    // If it's absent (fresh clone / CI without the secret), we fall back to debug
    // signing so the project still builds. See CLAUDE.md → "Release signing".
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
    if (keystorePropsFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Keep dev (Shift+F10) builds non-debuggable so ART runs full JIT optimizations
            // — on-device CPU inference is ~several× faster this way. (Previously enforced via
            // a hardcoded android:debuggable="false" in the manifest, which tripped release
            // lint; setting it here is the lint-safe equivalent.)
            isDebuggable = false
        }
        release {
            // R8 full-mode: shrink + obfuscate + optimize. Keep rules that protect the
            // JNI boundary, Gson models, Retrofit, and WorkManager live in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Real release key when keystore.properties is present; debug otherwise.
            signingConfig = if (keystorePropsFile.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main") {
            // Note: We include signed skeletons directly in src/main/jniLibs/arm64-v8a
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "2.3.21"
    }
}

kotlin {
    jvmToolchain(21)
}

// The release-only "produceReleaseComposeMapping" task pulls an artifact
// (org.jetbrains.kotlin:compose-group-mapping) that isn't published in our configured
// repos. It only emits Compose group metadata for Layout Inspector — nothing the APK needs
// at runtime — so disable it to unblock release builds.
tasks.matching { it.name.contains("ComposeMapping") }.configureEach { enabled = false }

// Note: Hexagon skeleton libraries are now signed during the build process to ensure
// reliable HPU/NPU offloading on production devices (Signed Process Domain).

tasks.register("bundleLibOmp") {
    description = "Bundles libomp.so from the NDK for OpenMP support"
    val outputDir = file("src/main/jniLibs/arm64-v8a")
    outputs.file(File(outputDir, "libomp.so"))

    doLast {
        val components = project.extensions.getByType<com.android.build.api.variant.ApplicationAndroidComponentsExtension>()
        val ndkDir = components.sdkComponents.ndkDirectory.get().asFile
        project.copy {
            from(project.fileTree(ndkDir).matching { include("**/aarch64/libomp.so") })
            into(outputDir)
            eachFile { path = name }
            includeEmptyDirs = false
        }
    }
}

tasks.register("signHexagonSkeletons") {
    description = "Signs Hexagon HTP skeletons using elfsigner for Signed PD support"
    dependsOn("bundleLibOmp")

    doLast {
        val hexagonSdkRoot = System.getenv("HEXAGON_SDK_ROOT")
            ?: project.findProperty("HEXAGON_SDK_ROOT")?.toString()

        if (hexagonSdkRoot.isNullOrBlank()) {
            println("[fancy_ai] HEXAGON_SDK_ROOT not set — skipping skeleton signing")
            return@doLast
        }

        val elfsigner = file("$hexagonSdkRoot/tools/elfsigner/elfsigner.py")
        if (!elfsigner.exists()) {
            println("[fancy_ai] elfsigner.py not found at ${elfsigner.absolutePath}")
            return@doLast
        }

        val buildDir = layout.buildDirectory.asFile.get()
        val cmakeDir = File(buildDir, "intermediates/cmake")
        val jniLibsDir = file("src/main/jniLibs/arm64-v8a")
        val outputDir = file("src/main/jniLibs/arm64-v8a")
        outputDir.mkdirs()

        val signAction = { skel: File ->
            println("[fancy_ai] Signing skeleton: ${skel.name}")
            // Sign the skeleton and output directly to jniLibs
            ProcessBuilder("sh", "-c", "echo y | python3 ${elfsigner.absolutePath} -i ${skel.absolutePath} -o ${outputDir.absolutePath}/${skel.name}")
                .inheritIO()
                .start()
                .waitFor()
            // Clean up the log file produced by elfsigner
            val logFile = File(outputDir, "Elfsigner_log.txt")
            if (logFile.exists()) logFile.delete()
        }

        if (cmakeDir.exists()) {
            cmakeDir.walkTopDown().filter { (it.name.startsWith("libggml-htp-") || it.name.endsWith("_skel.so")) && it.name.endsWith(".so") }.forEach { signAction(it) }
        }
        if (jniLibsDir.exists()) {
            jniLibsDir.listFiles()?.filter { (it.name.startsWith("libggml-htp-") || it.name.contains("Skel")) && it.name.endsWith(".so") }?.forEach { signAction(it) }
        }
    }
}

// Hook tasks into the build lifecycle
tasks.named("preBuild") {
    dependsOn("bundleLibOmp")
}

// DISABLED: skeleton signing is unnecessary and was actively harmful.
// Device logs confirm the Hexagon NPU runs on an *Unsigned* Protected Domain
// (FastRPC: "Unsigned:Y, Signed:N"; HTP0 registered with the unsigned skel).
// elfsigner also renamed its output to "<lib>_signed.so" — a name FastRPC never
// requests — so the signed copies were never loaded, and because the task wrote
// them back into the folder it scans, every build re-signed its own output
// (libggml-htp-v68_signed_signed_signed...), ballooning jniLibs to ~459 MB.
// Re-enable only if a device is ever found that *requires* Signed PD, and only
// after fixing the task to sign into a temp dir with the canonical filename.
 tasks.configureEach {
     if (name.startsWith("merge") && name.endsWith("NativeLibs")) {
         dependsOn("signHexagonSkeletons")
     }
 }

dependencies {
    // Core Android
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.activity.compose)
    implementation(libs.constraintlayout)
    implementation(libs.webkit)

    // Jetpack Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.compose.navigation)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // TODO: Re-add Hilt after resolving AGP 9.2.1 compatibility
    // Hilt
    // implementation(libs.hilt.android)
    // ksp(libs.hilt.compiler)
    // implementation(libs.hilt.navigation.compose)

    // ViewModel + Compose
    implementation(libs.lifecycle.viewmodel.compose)

    // Retrofit + OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)

    // Coil (image loading)
    implementation(libs.coil.compose)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Coroutines
    implementation(libs.coroutines)
    implementation(libs.coroutines.core)

    // JSON
    implementation(libs.gson)

    // Security
    implementation(libs.security.crypto)

    // Camera & Vision
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.vision.common)
    implementation(libs.mlkit.text.recognition)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
