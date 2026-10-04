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

// Physical lines, including imports, comments, and blanks. Scan new files too.
val firstPartySources = fileTree(rootDir) {
    include(
        "**/*.kt", "**/*.kts", "**/*.java", "**/*.aidl", "**/*.c", "**/*.cpp",
        "**/*.h", "**/*.hpp", "**/*.rs", "**/*.py", "**/*.sh", "**/*.js",
        "**/*.jsx", "**/*.ts", "**/*.tsx", "**/*.cjs", "**/*.mjs", "**/*.css",
        "**/*.html", "**/*.xml", "**/*.sql", "**/*.cmake", "**/CMakeLists.txt",
    )
    exclude(
        "**/.git/**", "**/.gradle/**", "**/.idea/**", "**/.kotlin/**", "**/.cxx/**",
        "**/build/**", "**/.gradle/**", "**/gen/**", "**/generated/**",
        "**/node_modules/**", "**/target/**", "**/third_party/**", "**/vendor/**",
        "**/external/**", "**/qnn-sdk/**", "image/src/main/cpp/sd/zstd/**",
        "tools/dit_engine/**",
        "terminal/src/main/assets/terminal/xterm.js",
        "terminal/src/main/assets/terminal/xterm.css",
        "terminal/src/main/assets/terminal/addon-fit.js",
    )
}
val sourceRoot = rootDir
val sizeBaseline = layout.projectDirectory.file("gradle/code-size-baseline.properties")
val sizeReport = layout.buildDirectory.file("reports/code-size.txt")
val checkCodeSize = tasks.register("checkCodeSize") {
    val sources = firstPartySources
    val rootDirectory = sourceRoot
    val baseline = sizeBaseline
    val reportFile = sizeReport
    group = "verification"
    description = "Enforces 600 lines per source file and shrinking legacy limits."
    inputs.files(firstPartySources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(sizeBaseline)
    outputs.file(sizeReport)
    doLast {
        val properties = java.util.Properties().apply {
            baseline.asFile.reader().use { load(it) }
        }
        val limits = properties.stringPropertyNames().associateWith { propertyName ->
            properties.getProperty(propertyName).toInt()
        }
        val counts = sources.files.associateBy({ it.relativeTo(rootDirectory).invariantSeparatorsPath }) {
            it.bufferedReader().useLines(Sequence<String>::count)
        }
        val violations = mutableListOf<String>()
        counts.forEach { (path, lines) ->
            val limit = limits[path] ?: 600
            if (lines > limit) violations += "$path: $lines lines exceeds $limit"
        }
        limits.forEach { (path, limit) ->
            val lines = counts[path]
            when {
                (limit <= 600) || (lines == null) || (lines <= 600) ->
                    violations += "$path: remove obsolete baseline entry"
                lines < limit ->
                    violations += "$path: lower baseline from $limit to $lines"
            }
        }
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            counts.asSequence().sortedByDescending(Map.Entry<String, Int>::value).joinToString("\n") { (path, count) ->
                "$count\t$path\tlimit=${limits[path] ?: 600}"
            } + "\n",
        )
        logger.lifecycle("Source size report: ${report.absolutePath}")
        if (violations.isNotEmpty()) throw GradleException(violations.joinToString("\n"))
    }
}

// Build-time CLIs only: isolated from the app's Kotlin/compiler/runtime dependencies.
val detektCli = configurations.create("detektCli") {
    isCanBeConsumed = false
    isTransitive = false
}
val cpdCli = configurations.create("cpdCli") { isCanBeConsumed = false }
dependencies {
    add(detektCli.name, variantOf(libs.detekt.cli) { classifier("all") })
    add(cpdCli.name, libs.pmd.cli)
    add(cpdCli.name, libs.pmd.kotlin)
}
val kotlinSources = firstPartySources.matching { include("**/*.kt", "**/*.kts") }
val complexityConfig = layout.projectDirectory.file("gradle/detekt.yml")
val complexityOutput = layout.buildDirectory.file("reports/complexity.html")
val complexityReport = tasks.register<JavaExec>("complexityReport") {
    val sources = kotlinSources
    val configFile = complexityConfig
    val reportFile = complexityOutput
    val rootDirectory = sourceRoot
    group = "verification"
    description = "Reports Kotlin complexity for cleanup review; does not reject existing findings."
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    inputs.files(kotlinSources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(complexityConfig)
    outputs.file(complexityOutput)
    doFirst {
        reportFile.get().asFile.parentFile.mkdirs()
        args = listOf(
            "--input", sources.files.asSequence().sorted().joinToString(",") { it.absolutePath },
            "--config", configFile.asFile.absolutePath,
            "--base-path", rootDirectory.absolutePath,
            "--report", "html:${reportFile.get().asFile.absolutePath}",
            "--max-issues", Int.MAX_VALUE.toString(),
        )
    }
}
val duplicationOutput = layout.buildDirectory.file("reports/duplication.txt")
val duplicationReport = tasks.register<JavaExec>("duplicationReport") {
    val sources = kotlinSources
    val reportFile = duplicationOutput
    val rootDirectory = sourceRoot
    group = "verification"
    description = "Reports Kotlin duplicates of at least 100 tokens for manual review."
    classpath = cpdCli
    mainClass.set("net.sourceforge.pmd.cli.PmdCli")
    inputs.files(kotlinSources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(duplicationOutput)
    doFirst {
        reportFile.get().asFile.parentFile.mkdirs()
        args = listOf(
            "cpd", "--language", "kotlin", "--minimum-tokens", "100",
            "--format", "text", "--no-fail-on-violation",
            "--report-file", reportFile.get().asFile.absolutePath,
            "--relativize-paths-with", rootDirectory.absolutePath,
            "--dir",
        ) + sources.files.asSequence().sorted().map { it.absolutePath }
    }
}
tasks.named("check") { dependsOn(checkCodeSize, complexityReport, duplicationReport) }
subprojects {
    tasks.configureEach {
        if ((name == "preBuild") || (name == "check") || name.startsWith("lint")) {
            dependsOn(checkCodeSize)
        }
    }
}
