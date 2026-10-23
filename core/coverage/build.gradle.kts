plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "au.signalatlas.core.coverage"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric supplies android.graphics.Bitmap for the PNG encoder tests.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // RenderedTile, CancelToken and AreaClass are part of TileRenderer's public API.
    api(project(":engine"))
    api(project(":core:data"))
    api(project(":core:model"))
    // DemChunkStore and ChunkId are part of TerrainSupport's public API.
    api(project(":core:geo"))
    // Interceptor is part of CoverageInterceptor's public API.
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
