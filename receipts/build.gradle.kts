plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
    alias(libs.plugins.maven)
}

description = "Ostrom signed receipts: canonical JSON, SHA-256 digests, Ed25519 signing and offline verification."

// The golden vectors of the TypeScript prototype live in compat/vectors; native tests read them from disk.
val vectorsDirectory = rootProject.layout.projectDirectory.dir("compat/vectors").asFile.absolutePath
val generatedTestDirectory = layout.buildDirectory.dir("generated/compat-vectors")
val generateVectorsPath by tasks.registering {
    inputs.property("vectors", vectorsDirectory)
    outputs.dir(generatedTestDirectory)
    doLast {
        val output = generatedTestDirectory.get().file("dev/ostrom/receipts/CompatVectorsPath.kt").asFile
        output.parentFile.mkdirs()
        output.writeText(
            "package dev.ostrom.receipts\n\n" +
                "/** Absolute path of compat/vectors, written at build time. */\n" +
                "internal const val COMPAT_VECTORS_DIR: String = \"${vectorsDirectory.replace("\\", "/")}\"\n"
        )
    }
}

kotlin {
    explicitApi()
    macosArm64(); linuxX64(); linuxArm64(); mingwX64()
    sourceSets {
        all { languageSettings.optIn("kotlin.io.encoding.ExperimentalEncodingApi") }
        commonMain.dependencies {
            api(project(":api"))
            api(libs.serialization)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.openssl3)
        }
        commonTest {
            kotlin.srcDir(generateVectorsPath)
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.io)
            }
        }
    }
}
