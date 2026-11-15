plugins {
    id("ostrom.kmp")
    id("ostrom.mobile")
    id("ostrom.publish")
}

description = "Ostrom shared contract: the serializable models of the protocol and of the server API."

kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            api(libs.serialization.json)
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
