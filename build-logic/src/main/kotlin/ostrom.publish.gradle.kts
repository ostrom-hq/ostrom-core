import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar

// Publishes the module to GitHub Packages as dev.ostrom:ostrom-<module>.
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
