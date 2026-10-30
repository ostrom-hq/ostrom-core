plugins {
    id("ostrom.kmp")
    id("ostrom.publish")
}

description = "Ostrom shared contract: the serializable models of the protocol and of the server API."

// ContractTest reads api/contract/shapes.json (derived from api/openapi.yaml by api-types/scripts/contract-shapes.mjs).
val contractFile = layout.projectDirectory.file("contract/shapes.json").asFile.absolutePath
val generatedTestDirectory = layout.buildDirectory.dir("generated/contract")
val generateContractPath = tasks.register("generateContractPath") {
    inputs.property("contract", contractFile)
    outputs.dir(generatedTestDirectory)
    doLast {
        val output = generatedTestDirectory.get().file("dev/ostrom/core/api/ContractPath.kt").asFile
        output.parentFile.mkdirs()
        output.writeText(
            "package dev.ostrom.core.api\n\n" +
                "/** Absolute path of api/contract/shapes.json, written at build time. */\n" +
                "internal const val CONTRACT_SHAPES_FILE: String = \"${contractFile.replace("\\", "/")}\"\n"
        )
    }
}

// A changed contract must re-run the tests, never replay a cached result.
tasks.withType<AbstractTestTask>().configureEach {
    inputs.file(contractFile).withPathSensitivity(PathSensitivity.RELATIVE)
}

kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            api(libs.serialization.json)
        }
        commonTest {
            kotlin.srcDir(generateContractPath)
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.io)
            }
        }
    }
}
