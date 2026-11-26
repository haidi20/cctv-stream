pluginManagement {
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

rootProject.name = "cctv-monitor"

include(":app")
include(":core:model")
include(":core:database")
include(":core:network")
include(":core:sync")
include(":core:ui")
include(":feature:cameras")
include(":feature:tables")
include(":feature:auth")
include(":feature:home")
include(":feature:onboarding")
include(":feature:settings")
