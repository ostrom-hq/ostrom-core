import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.maven)
    alias(libs.plugins.kover)
}

description = "Ostrom frontier actions: classifies a shell command or a tool call, and reads what a yes to it binds."

// The build-logic convention plugin is not on main yet (ostrom-core#8): the test-only jvm() target and the Kover rule
// of ostrom.kmp.gradle.kts are declared here, and this file becomes `id("ostrom.kmp")` when it lands.
kotlin {
    explicitApi()
    jvmToolchain(21)
    // Test-only: Kover cannot measure Kotlin/Native, so commonMain runs and is measured on the JVM; never shipped.
    jvm {
        testRuns.named("test") { executionTask.configure { useJUnitPlatform() } }
    }
    macosArm64(); linuxX64(); linuxArm64(); mingwX64()
    sourceSets {
        commonMain.dependencies {
            api(libs.serialization)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            runtimeOnly(libs.junit.jupiter.engine)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

// 100 % of lines and branches of commonMain, measured through the jvm() test run (BRIEF, rule TESTS).
kover {
    reports {
        verify {
            rule("100 % of lines and branches (Lyes, 30/10/2026)") {
                minBound(100, CoverageUnit.LINE)
                minBound(100, CoverageUnit.BRANCH)
            }
        }
    }
}

// The jvm target exists for tests only: never publish it (ostrom-e2e templates, docs/CONVENTIONS.md section 7.2).
tasks.matching { it.name.contains("JvmPublication") }.configureEach { enabled = false }
