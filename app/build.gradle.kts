import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

abstract class PropertiesFileValueSource : ValueSource<Map<String, String>, PropertiesFileValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val propertiesFile: RegularFileProperty
    }

    override fun obtain(): Map<String, String>? {
        val file = parameters.propertiesFile.orNull?.asFile ?: return null
        if (!file.exists()) return null
        val props = Properties()
        file.inputStream().use { props.load(it) }
        return props.stringPropertyNames().associateWith { props.getProperty(it) }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val releaseKeystorePropertiesFile = layout.projectDirectory.dir("..").file("keystore.properties")
val releaseKeystoreProperties = providers.of(PropertiesFileValueSource::class.java) {
    parameters.propertiesFile.set(releaseKeystorePropertiesFile)
}

extensions.configure<ApplicationExtension> {
    namespace = "com.mrj.fancyai"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.mrj.fancyai.github"
        minSdk = 33
        targetSdk = 37
        versionCode = 83
        versionName = "4.63"
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    androidResources {
        noCompress += "tflite"
    }

    packaging {
        jniLibs {
            // Native merging runs before ABI filtering; Sherpa also bundles an x86 ORT.
            excludes += "lib/x86/libonnxruntime.so"
            excludes += "**/libcdsprpc.so"
            excludes += "**/libOpenCL.so"
            keepDebugSymbols += "**/libggml-htp-*.so"
            keepDebugSymbols += "**/libQnn*.so"
            useLegacyPackaging = true
        }
    }

    val keystoreProps = releaseKeystoreProperties.getOrElse(emptyMap())

    if (keystoreProps.isNotEmpty()) {
        signingConfigs {
            register("release") {
                storeFile = layout.projectDirectory.dir("..").file(keystoreProps["storeFile"] ?: "").asFile
                storePassword = keystoreProps["storePassword"]
                keyAlias = keystoreProps["keyAlias"]
                keyPassword = keystoreProps["keyPassword"]
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        lintConfig = rootProject.file("app/lint.xml")
        abortOnError = true
        absolutePaths = false
        checkAllWarnings = true
        checkDependencies = true
        checkGeneratedSources = false
        checkReleaseBuilds = true
        checkTestSources = true
        explainIssues = true
        ignoreTestSources = false
        ignoreWarnings = false
        noLines = false
        showAll = true
        warningsAsErrors = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.material)

    implementation(project(":engine"))
    implementation(project(":image"))
    implementation(project(":vision"))
    implementation(project(":voice"))
    implementation(project(":terminal"))
    implementation(libs.sherpa.android) { artifact { type = "aar"; extension = "aar" } }
    implementation(libs.commons.compress)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.core)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.navigation3.runtime)
    implementation(libs.markdown.m3)

    implementation(libs.okhttp)
    implementation(libs.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.parcelize.runtime)
}
