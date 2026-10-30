rootProject.name = "ostrom-core"
// No TYPESAFE_PROJECT_ACCESSORS: a feature preview applies to the whole build tree, so it reached ostrom-server and
// ostrom-cli when their CI includes this build (JOURNAL 05:50) and broke them (their module `artifacts` clashes with
// ProjectDependency.getArtifacts()). Modules depend on project(":name").

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
    }
}

include(":api", ":receipts", ":telemetry")
