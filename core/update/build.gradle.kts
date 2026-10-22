plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "au.signalatlas.core.update"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Share the ACMA test fixture (fixture.zip) without duplicating the binary.
    sourceSets {
        getByName("test") {
            resources.directories.add("../acma/src/test/resources")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:acma"))
    api(project(":core:data"))
    // OkHttpClient is part of AcmaRemote's public constructor.
    api(libs.okhttp)
    // WorkerFactory / CoroutineWorker are part of the public API.
    api(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
}

// Unit tests run on the host JVM, but the Android variant of sqlite-bundled ships only device .so
// files. Extract the host's libsqliteJni from the JVM artifact and put it on java.library.path.
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
