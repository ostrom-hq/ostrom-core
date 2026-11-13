// SPDX-License-Identifier: Apache-2.0
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar

// Publishes the module as run.ostrom:ostrom-<module>: to GitHub Packages on every merge (CI), and to Maven Central by
// hand for major releases (docs/publishing.md), configured as TeamCode's api and client modules are (kourier style:
// Vanniktech, empty javadoc jars, signed publications).
// It lives in build-logic, next to ostrom.kmp, so the publish plugin and the Kotlin plugin share one classloader.
plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
        ),
    )
    coordinates(project.group.toString(), "ostrom-${project.name}", project.version.toString())
    publishToMavenCentral()
    // Central requires signed publications. The key lives only on the publisher's machine (~/.gradle/gradle.properties),
    // so the CI's GitHub Packages publication, which has no key, stays unsigned.
    if (providers.gradleProperty("signing.keyId").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    pom {
        name.set("ostrom-${project.name}")
        description.set(provider { project.description.orEmpty() })
        url.set("https://github.com/ostrom-hq/ostrom-core")
        licenses {
            license {
                name.set("Apache 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("ostrom-hq")
                name.set("Ostrom")
                url.set("https://ostrom.run")
            }
        }
        scm { url.set("https://github.com/ostrom-hq/ostrom-core.git") }
    }
}

publishing {
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
