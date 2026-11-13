rootProject.name = "ostrom-core"
// No TYPESAFE_PROJECT_ACCESSORS: a feature preview applies to the whole build tree, so it reached ostrom-server and
// ostrom-cli when their CI includes this build (JOURNAL 05:50) and broke them (their module `artifacts` clashes with
// ProjectDependency.getArtifacts()). Modules depend on project(":name").

pluginManagement {
    includeBuild("build-logic")
    repositories {
        // The Android Gradle plugin, on the classpath of build-logic's plugins (ostrom.mobile).
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google.android")
                includeGroupAndSubgroups("com.google.testing.platform")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Google's repository serves only the Android Gradle plugin, AndroidX and Google's Android groups (never
        // com.google.* libraries such as Guava or protobuf, which keep resolving from Maven Central), as in teamcode.
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google.android")
                includeGroupAndSubgroups("com.google.testing.platform")
            }
        }
        mavenCentral()
    }
}

include(":api", ":client", ":receipts", ":telemetry", ":actions")
