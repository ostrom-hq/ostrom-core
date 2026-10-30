plugins {
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.maven) apply false
    alias(libs.plugins.kover) apply false
}

// CI passes -PostromCoreVersion=0.1.<run number> on main; local builds and includeBuild consumers see the snapshot.
val ostromCoreVersion = providers.gradleProperty("ostromCoreVersion").orElse("0.1.0-SNAPSHOT").get()

allprojects {
    group = "dev.ostrom"
    version = ostromCoreVersion
    project.ext.set("url", "https://github.com/ostrom-hq/ostrom-core")
    project.ext.set("license.name", "Apache 2.0")
    project.ext.set("license.url", "https://www.apache.org/licenses/LICENSE-2.0.txt")
    project.ext.set("scm.url", "https://github.com/ostrom-hq/ostrom-core.git")
}

// Every module that applies the publish plugin goes to GitHub Packages as dev.ostrom:ostrom-<module>.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            configure(
                com.vanniktech.maven.publish.KotlinMultiplatform(
                    javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
                    sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
                )
            )
            coordinates(project.group.toString(), "ostrom-${project.name}", project.version.toString())
            pom {
                name.set("ostrom-${project.name}")
                description.set(project.description)
                url.set(project.ext.get("url")?.toString())
                licenses {
                    license {
                        name.set(project.ext.get("license.name")?.toString())
                        url.set(project.ext.get("license.url")?.toString())
                    }
                }
                scm { url.set(project.ext.get("scm.url")?.toString()) }
            }
        }
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/ostrom-hq/ostrom-core")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR")
                        password = System.getenv("GITHUB_TOKEN")
                    }
                }
            }
        }
    }
}
