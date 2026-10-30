// Included build holding the convention plugins (docs/CONVENTIONS.md section 9), as pkg includes gradle-plugin/.
// The root settings.gradle.kts declares: pluginManagement { includeBuild("build-logic") }
rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
