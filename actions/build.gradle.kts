// SPDX-License-Identifier: Apache-2.0
plugins {
    id("ostrom.kmp")
    id("ostrom.publish")
}

description = "Ostrom frontier actions: classifies a shell command or a tool call, and reads what a yes to it binds."

kotlin {
    explicitApi()
    sourceSets {
        // ToolSubject reads the JSON input of a tool call: JsonObject is part of the public API.
        commonMain.dependencies {
            api(libs.serialization.json)
        }
    }
}
