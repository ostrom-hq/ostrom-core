# Publishing

Core's Kotlin libraries (`run.ostrom:ostrom-*`) are published to two places:

| Where | When | How |
| --- | --- | --- |
| GitHub Packages | every merge on `main` | CI (`.github/workflows/build.yml`, job `publish`), version `0.1.<run number>`, unsigned |
| Maven Central | major releases only | by hand, from a Mac, as described below |

Maven Central has publication limits, so development builds stay on GitHub Packages. The `run.ostrom` namespace
belongs to the project on the Central Portal.

## Maven Central

The configuration follows TeamCode's `api` and `client` modules (`com.vanniktech.maven.publish`, empty javadoc jars,
signed publications). Publish from a checkout of the exact release commit on a Mac: the iOS and macOS klibs only build
there. Put the Central and signing properties in `~/.gradle/gradle.properties`, never in the repository:

```properties
mavenCentralUsername=…
mavenCentralPassword=…
signing.keyId=…
signing.password=…
signing.secretKeyRingFile=…
```

Then:

```sh
./gradlew --no-daemon -PostromCoreVersion=X.Y.Z publishToMavenCentral
```

Always pass `-PostromCoreVersion`: without it the version is `0.1.0-SNAPSHOT`. This uploads a deployment that is then
released from the Central Portal. Check locally first with `publishToMavenLocal`.

Signing is only enabled when a signing key is configured, so the CI's GitHub Packages publication, which has no key,
is not affected.
