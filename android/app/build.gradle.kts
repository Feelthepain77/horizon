plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.horizon.android"
    // Compose 1.12 (BOM 2026.09.00) requires API 37; targetSdk stays 36.
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "dev.horizon.android"
        // Android 15 per the porting decision (Vulkan 1.1 baseline hardware).
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // arm64-v8a only: every supported phone is aarch64 and the other
        // three ABIs (armeabi-v7a/x86/x86_64) were pure bloat. The build
        // script still accepts --abis for local overrides.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        debug {
            isJniDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No signing config in the scaffold: assembleRelease produces an
            // unsigned APK for sideloading. Add your signingConfig here for
            // distribution.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Rust .so files are uncompressed + page-aligned in the APK so the
        // loader can mmap them directly (required with extractNativeLibs=false).
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.kotlinx.coroutines.android)

    // MSAL BrowserTabActivity is an AppCompatActivity subclass.
    implementation(libs.androidx.appcompat)
    implementation(libs.msal) {
        // display-mask:0.3.0 was a jcenter-era Surface Duo artifact that no
        // longer exists on Maven Central (MSAL's `common` still declares it
        // as a runtime dep). It only provides dual-screen hints; excluding it
        // is the standard workaround. Drop the exclude if Microsoft republishes.
        exclude(group = "com.microsoft.device.display", module = "display-mask")
    }

    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)
}

// ---------------------------------------------------------------------------
// Optional Rust integration: run `./gradlew assembleDebug -PrustBuild` to
// build the native libraries via cargo-ndk before packaging. Without the
// property, Gradle packages whatever is in src/main/jniLibs (the default
// output location of scripts/build-android-libs.sh).
// ---------------------------------------------------------------------------
val rustBuildRequested = providers.gradleProperty("rustBuild").isPresent
if (rustBuildRequested) {
    val scaffoldRoot = rootProject.projectDir.parentFile!! // android/ -> scaffold root
    tasks.register<Exec>("rustLibs") {
        workingDir(scaffoldRoot)
        commandLine(
            "bash", "scripts/build-android-libs.sh",
            "--profile", providers.gradleProperty("rustProfile").getOrElse("dev"),
        )
    }
    tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLib", ignoreCase = true) }
        .configureEach { dependsOn("rustLibs") }
}
