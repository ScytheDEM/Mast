plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    // SQLiteDriver is part of AcmaImporter's public constructor.
    api(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test {
    // A phone-like heap (typical Android heapgrowthlimit) with a bounded young generation, so that
    // FullExportTest's used-heap samples measure retained memory rather than how long G1 defers
    // collecting short-lived row strings. (The importer also completes with a 64 MB heap.)
    maxHeapSize = "256m"
    jvmArgs("-XX:MaxNewSize=32m")
    // FullExportTest runs only when ACMA_FULL_ZIP is set; make the variable an input so it is not skipped as up to date.
    inputs.property("acmaFullZip", providers.environmentVariable("ACMA_FULL_ZIP").orElse(""))
}
