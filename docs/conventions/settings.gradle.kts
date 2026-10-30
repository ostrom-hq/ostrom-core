// Reference settings.gradle.kts of an Ostrom Kotlin repository (teamcode, asonar, pkg).
rootProject.name = "ostrom-server"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // ostrom-core artifacts (private): read with a token that has read:packages.
        maven("https://maven.pkg.github.com/ostrom-hq/ostrom-core") {
            name = "OstromCore"
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}

include(":domain", ":infrastructure", ":presentation", ":app")
