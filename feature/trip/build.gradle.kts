plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "au.signalatlas.feature.trip"
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
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // CoverageTileService, RadioState, Fix and the model types are part of TripController's public API.
    api(project(":core:coverage"))
    api(project(":core:radio"))
    api(project(":core:calibration"))
    api(project(":core:data"))
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
