// SPDX-License-Identifier: Apache-2.0
plugins {
    id("ostrom.kmp")
    id("ostrom.mobile")
    id("ostrom.publish")
}

description = "Ostrom typed HTTP client over the api contract: the companion app and the CLI supply the engine and " +
    "the credentials."

kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            api(project(":api"))
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            // SHA-256 of the PKCE challenge, on every target (iOS and Android included) without a crypto provider.
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
