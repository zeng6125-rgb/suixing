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
if (providers.gradleProperty("rustApp").orNull == "true") {
    include(":rustApp")
    project(":rustApp").projectDir = file("rust-app")
}
