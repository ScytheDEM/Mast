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
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:acma"))
    api(project(":core:data"))
}
