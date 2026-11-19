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

rootProject.name = "Suixing"
include(":app")
// `-PrustProduction=true` builds the Rust frontend as `com.suixing.agent`, so it
// installs over the existing app and shares its data directory.
if (providers.gradleProperty("rustProduction").orNull == "true") {
    project(":app").projectDir = file("rust-app")
}
if (providers.gradleProperty("rustApp").orNull == "true") {
    include(":rustApp")
    project(":rustApp").projectDir = file("rust-app")
}
