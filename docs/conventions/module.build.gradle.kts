// Reference build.gradle.kts of one module: the convention plugin, then dependencies only (asonar:domain).
plugins {
    id("ostrom.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.domain)
            implementation(libs.ktor.server.core)
        }
        commonTest.dependencies {
            implementation(libs.ktor.server.test.host)
        }
    }
}
