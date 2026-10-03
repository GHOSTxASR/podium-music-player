pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
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

rootProject.name = "podium"

// Pure Kotlin domain modules (ADR-012): no Android imports.
include(":core:model")
include(":core:common")
include(":sources:api")
include(":player:api")

// Android modules
include(":player:service")
include(":sources:test")
include(":sources:local")
include(":core:database")
include(":core:interaction")
include(":core:designsystem")
include(":feature:library")
include(":feature:nowplaying")
include(":feature:settings")
include(":app")
