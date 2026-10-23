plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:acma"))
    // StateFlow / CoroutineScope are part of TowerRepository's public API.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Share the ACMA test fixture (fixture.zip) without duplicating the binary.
sourceSets.test {
    resources.srcDir("../acma/src/test/resources")
}
