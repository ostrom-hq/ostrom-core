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
        // ContractTest calls every public function of the client by reflection.
        jvmTest.dependencies {
            implementation(kotlin("reflect"))
        }
    }
}

// ContractTest compares the client with the server's generated document, the @ostrom-hq/openapi version of
// openapi.version, which CI installs and names in OSTROM_OPENAPI_DOCUMENT: another document re-runs the test.
val openApiDocument = providers.environmentVariable("OSTROM_OPENAPI_DOCUMENT")
val openApiRequired = providers.environmentVariable("OSTROM_OPENAPI_REQUIRED").orElse("false")
tasks.withType<AbstractTestTask>().configureEach {
    inputs.property("openApiRequired", openApiRequired)
    inputs.property("openApiDocumentSet", openApiDocument.map { it.isNotBlank() }.orElse(false))
    inputs.files(openApiDocument.map { listOfNotNull(it.takeIf(String::isNotBlank)) }.orElse(emptyList()))
        .withPathSensitivity(PathSensitivity.NONE)
}
