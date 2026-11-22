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

rootProject.name = "ProjectCit"

include(":app")
include(":core:layout")   // Pure-Kotlin posture/layout engine (JVM, unit-tested)
include(":core:storage")  // SAF ROM/BIOS indexer + system/ mirror
include(":core:libretro") // C++17 libretro host (NDK/CMake) — stub in Phase 1
