plugins {
    id("ostrom.kmp")
    alias(libs.plugins.maven)
}

description = "Ostrom signed receipts: canonical JSON, SHA-256 digests, Ed25519 signing and offline verification."

// The golden vectors of the TypeScript prototype live in compat/vectors; native tests read them from disk.
val vectorsDirectory = rootProject.layout.projectDirectory.dir("compat/vectors").asFile.absolutePath
val generatedTestDirectory = layout.buildDirectory.dir("generated/compat-vectors")
val generateVectorsPath = tasks.register("generateVectorsPath") {
    inputs.property("vectors", vectorsDirectory)
    outputs.dir(generatedTestDirectory)
    doLast {
        val output = generatedTestDirectory.get().file("dev/ostrom/core/receipts/CompatVectorsPath.kt").asFile
        output.parentFile.mkdirs()
        output.writeText(
            "package dev.ostrom.core.receipts\n\n" +
                "/** Absolute path of compat/vectors, written at build time. */\n" +
                "internal const val COMPAT_VECTORS_DIR: String = \"${vectorsDirectory.replace("\\", "/")}\"\n"
        )
    }
}

// The tests read compat/ at run time: a changed vector must re-run them, never replay a cached result.
tasks.withType<AbstractTestTask>().configureEach {
    inputs.dir(rootProject.layout.projectDirectory.dir("compat")).withPathSensitivity(PathSensitivity.RELATIVE)
}

kotlin {
    explicitApi()
    sourceSets {
        all { languageSettings.optIn("kotlin.io.encoding.ExperimentalEncodingApi") }
        commonMain.dependencies {
            api(projects.api)
            api(libs.serialization.json)
            implementation(libs.cryptography.core)
        }
        // OpenSSL 3, linked in, on every native target; the JDK provider for the jvm() test target.
        nativeMain.dependencies { implementation(libs.cryptography.openssl3) }
        jvmMain.dependencies { implementation(libs.cryptography.jdk) }
        commonTest {
            kotlin.srcDir(generateVectorsPath)
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.io)
            }
        }
    }
}
