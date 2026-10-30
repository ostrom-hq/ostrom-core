plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
    alias(libs.plugins.maven)
    alias(libs.plugins.kover)
}

description = "Ostrom telemetry: W3C trace context, spans, metrics and logs exported as OTLP/HTTP protobuf " +
    "(Grafana Cloud, Langfuse), and product analytics events for PostHog. Shared by the server and the CLI."

kotlin {
    explicitApi()
    // jvm() is for tests and Kover coverage of commonMain only (CONVENTIONS 7.2), never shipped. Its artifact is
    // published so that the server's and the CLI's own test-only jvm() targets can depend on this module.
    jvm()
    macosArm64(); linuxX64(); linuxArm64(); mingwX64()
    sourceSets {
        all {
            languageSettings.optIn("kotlin.io.encoding.ExperimentalEncodingApi")
            languageSettings.optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
        commonMain.dependencies {
            api(libs.coroutines.core)
            api(libs.ktor.client.core)
            implementation(libs.serialization)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.opentelemetry.proto)
        }
    }
}

// The jvm() target exists for tests: it is not published (CONVENTIONS 7.2).
tasks.matching { it.name.startsWith("publishJvm") }.configureEach { enabled = false }

// 100 % of lines and branches of commonMain, measured through the jvm() test run (BRIEF, rule TESTS).
// An exclusion is added here with a one-line reason, and reviewed.
kover {
    reports {
        filters {
            excludes {
                // Each use carries a `// coverage: <why>` line right above it (compare-and-set retry loops).
                annotatedBy("dev.ostrom.core.telemetry.ExcludeFromCoverage")
            }
        }
        verify {
            rule {
                minBound(100, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
                minBound(100, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}
