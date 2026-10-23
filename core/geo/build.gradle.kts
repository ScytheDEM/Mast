plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "au.signalatlas.core.geo"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric supplies org.json, SharedPreferences and ConnectivityManager for the tests.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // GeoBounds is part of ChunkMath's public API.
    api(project(":core:model"))
    // OkHttpClient is part of DemFetcher's public constructor.
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
