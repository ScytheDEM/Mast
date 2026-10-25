plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "au.signalatlas.core.beacons"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // LatLon is part of the public API.
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.sqlite.bundled)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Unit tests run on the host JVM, but the Android variant of sqlite-bundled ships only device .so files. Extract the
// host's libsqliteJni from the JVM artifact and put it on java.library.path (as :core:update does).
val hostSqliteNatives: Configuration = configurations.create("hostSqliteNatives") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    hostSqliteNatives(libs.androidx.sqlite.bundled.jvm)
}

val hostSqlitePlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("mac") -> "osx_arm64"
        os.contains("win") -> "windows_x64"
        arm -> "linux_arm64"
        else -> "linux_x64"
    }
}

val extractHostSqliteNatives = tasks.register<Sync>("extractHostSqliteNatives") {
    from({ zipTree(hostSqliteNatives.singleFile) }) {
        include("natives/$hostSqlitePlatform/*")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("hostSqliteNatives"))
}

tasks.withType<Test>().configureEach {
    dependsOn(extractHostSqliteNatives)
    val nativesDir = layout.buildDirectory.dir("hostSqliteNatives").get().asFile
    systemProperty("java.library.path", nativesDir.absolutePath)
}
