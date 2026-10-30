// SPDX-License-Identifier: Apache-2.0
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.maven)
    alias(libs.plugins.kover)
}

description = "Ostrom frontier actions: classifies a shell command or a tool call, and reads what a yes to it binds."

// The build-logic convention plugin is not on main yet (ostrom-core#8): the test-only jvm() target and the Kover rule
// of ostrom.kmp.gradle.kts are declared here, as in :telemetry, and this file becomes `id("ostrom.kmp")` when it lands.
kotlin {
    explicitApi()
    // jvm() is for tests and Kover coverage of commonMain only (CONVENTIONS 7.2), never shipped in a binary. Its
    // artifact is published all the same: the CLI's and the server's own test-only jvm() targets resolve it.
    jvm()
    macosArm64(); linuxX64(); linuxArm64(); mingwX64()
    sourceSets {
        commonMain.dependencies {
            api(libs.serialization)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// 100 % of lines and branches of commonMain, measured through the jvm() test run (BRIEF, rule TESTS).
kover {
    reports {
        verify {
            rule {
                minBound(100, CoverageUnit.LINE)
                minBound(100, CoverageUnit.BRANCH)
            }
        }
    }
}
