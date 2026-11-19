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

rootProject.name = "WarPair"

// Physical dir still android/nearbyshare until a full tree move; project name is WarPair.
include(":warpair-core")
project(":warpair-core").projectDir = file("nearbyshare")
include(":app")
