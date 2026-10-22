plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:acma"))
    implementation(libs.androidx.sqlite.bundled)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
