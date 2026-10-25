pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "signal-atlas"

include(":app")
include(":core:model", ":core:acma", ":core:data", ":core:update", ":core:map")
include(":engine", ":core:coverage", ":core:geo")
include(":core:radio", ":core:calibration")
include(":core:roads", ":core:beacons")
include(":feature:trip")
include(":car")
