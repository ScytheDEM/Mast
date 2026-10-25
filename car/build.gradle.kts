plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "au.signalatlas.car"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    // fullMap: sideload build, navigation category, draws its own map surface (Play only allows that for turn-by-turn
    // apps). play: template-only status screens, for the Play Store.
    flavorDimensions += "distribution"
    productFlavors {
        create("fullMap") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
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
    api(project(":core:model"))
    api(project(":core:radio"))
    implementation(project(":core:map"))
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    "fullMapImplementation"(libs.maplibre.android)
    "fullMapImplementation"(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.car.app.testing)
}
