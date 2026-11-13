// SPDX-License-Identifier: Apache-2.0
// Included build holding the convention plugins (docs/CONVENTIONS.md section 9), as pkg includes gradle-plugin/.
// The root settings.gradle.kts declares: pluginManagement { includeBuild("build-logic") }
rootProject.name = "build-logic"

dependencyResolutionManagement {
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
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
