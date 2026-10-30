// SPDX-License-Identifier: Apache-2.0
plugins {
    id("ostrom.kmp")
    id("ostrom.publish")
}

description = "Ostrom telemetry: W3C trace context, spans, metrics and logs exported as OTLP/HTTP protobuf " +
    "(Grafana Cloud, Langfuse), and product analytics events for PostHog. Shared by the server and the CLI."

kotlin {
    explicitApi()
    compilerOptions {
        optIn.addAll("kotlin.io.encoding.ExperimentalEncodingApi", "kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            api(libs.ktor.client.core)
            implementation(libs.serialization.json)
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

// A reviewed exclusion of this module (ostrom-core#7): each use carries a `// coverage: <why>` line right above it
// (compare-and-set retry loops). The 100 % rule itself comes from ostrom.kmp.
kover {
    reports {
        filters {
            excludes {
                annotatedBy("dev.ostrom.core.telemetry.ExcludeFromCoverage")
            }
        }
    }
}
