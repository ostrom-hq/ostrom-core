plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
    alias(libs.plugins.maven)
}

description = "Ostrom shared contract: the serializable models of the protocol and of the server API."

kotlin {
    explicitApi()
    macosArm64(); linuxX64(); linuxArm64(); mingwX64()
    sourceSets {
        all { languageSettings.optIn("kotlin.io.encoding.ExperimentalEncodingApi") }
        commonMain.dependencies {
            api(libs.serialization)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
