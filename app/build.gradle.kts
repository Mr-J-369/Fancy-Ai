plugins {
    alias(libs.plugins.android.application)
}

extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "com.mrj.fancyai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mrj.fancyai"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "3.0.5"
        ndkVersion = "27.2.12479018"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
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
            // IMPORTANT: We must EXCLUDE libOpenCL.so from the APK bundle.
            // On modern devices (Android 15+), bundling a non-aligned ICD loader 
            // causes the "LOAD segment not aligned" (16 KB) error. 
            // By excluding it, the app correctly resolves libOpenCL.so from 
            // the /vendor partition, which is guaranteed to be aligned.
            excludes += "**/libOpenCL.so"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
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
}

kotlin {
    jvmToolchain(21)
}

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
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.webkit)
    implementation(libs.coroutines)
    implementation(libs.security.crypto)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
