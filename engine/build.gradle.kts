import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "au.signalatlas.engine"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 29
        ndk {
            // Same -Pabis switch as :app (phone-only builds drop x86_64).
            abiFilters += (findProperty("abis") as String?)?.split(",") ?: listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.sdkCmake.get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

// Fast host (macOS) GoogleTest run of the shared engine sources.
val hostTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds and runs the engine's host GoogleTest suite (SDK CMake 4.1.2, Apple clang)."
    inputs.dir("src/main/cpp")
    inputs.dir("host/test")
    inputs.file("host/CMakeLists.txt")
    inputs.file("host/run-tests.sh")
    // The script is idempotent and incremental; always run it rather than trust Gradle's up-to-date check
    // (the GoogleTest fetch and ctest results are outside Gradle's view).
    outputs.upToDateWhen { false }
    commandLine(file("host/run-tests.sh").absolutePath)
}

tasks.named("check") {
    dependsOn(hostTest)
}
