# ostrom-core

The Kotlin Multiplatform library shared by the Ostrom CLI and server: the protocol models, the signed receipts and the
API contract. Targets: `macosArm64`, `linuxX64`, `linuxArm64`, `mingwX64` (Kotlin/Native only, no JVM). Apache 2.0.

| Module | Artifact | Contents |
|---|---|---|
| `api` | `dev.ostrom:ostrom-api` | serializable models (`dev.ostrom.core.api.models`) |
| `receipts` | `dev.ostrom:ostrom-receipts` | canonical JSON v1, SHA-256 digests, Ed25519 signing and offline verification of receipts and session packages, subject preimages |
| `telemetry` | `dev.ostrom:ostrom-telemetry` | W3C trace context, spans, metrics and logs exported as OTLP/HTTP protobuf (Grafana Cloud, any collector), model-call spans for Langfuse, PostHog product events and their taxonomy ([docs/telemetry.md](docs/telemetry.md)) |

Receipts and packages are byte-compatible with the TypeScript prototype: the tests replay its golden vectors
(`compat/`, see its README).

## Use it from another repository

Every merge on `main` publishes `0.1.<run number>` to GitHub Packages (the run number is in the Actions summary and in
`.coord/JOURNAL.md`). GitHub Packages needs a token with `read:packages`, even for reading:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/ostrom-hq/ostrom-core")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).get()
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).get()
            }
        }
    }
}

// build.gradle.kts
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("dev.ostrom:ostrom-receipts:0.1.<run>") // brings ostrom-api
    }
}
```

In GitHub Actions of another `ostrom-hq` repository, `GITHUB_TOKEN` reads the package once the repository is granted
access in the package settings (Package settings → Manage Actions access).

To work on core and a consumer together, without publishing, in the consumer's `settings.gradle.kts`:

```kotlin
includeBuild("../ostrom-core") {
    // the published artifact ids carry an ostrom- prefix the project names do not have
    dependencySubstitution {
        substitute(module("dev.ostrom:ostrom-api")).using(project(":api"))
        substitute(module("dev.ostrom:ostrom-receipts")).using(project(":receipts"))
    }
}
```

## Receipts in three calls

```kotlin
val key = ReceiptSigningKey.fromPkcs8Pem(pem)                  // or fromSeed, generate
val receipt = ReceiptSignerImpl(key).sign(draft)               // ReceiptDraft -> Receipt, signature field added
val result = ReceiptVerifierImpl(listOf(key.publicKey)).verify(json) // the raw JSON object as received, never the typed model
```

Subject digests: `Digests.digestOf(SubjectPreimages.session(...))` (also `gate`, `workflow`, `ruleChange`,
`decision`, and `pushActionKey` for the key of a push gate request).

## Build

Builds run in GitHub Actions (Linux): `linuxX64` tests, cross-compilation of `linuxArm64`, `mingwX64` and the
`macosArm64` klibs. Locally: `./gradlew linuxX64Test` on Linux, `./gradlew macosArm64Test` on a Mac.

Session packages: `PackageEnvelopes.verify(envelope, keys)` returns the verified payload text; only the payload is signed,
so read the repository and version from the decoded payload, never from the envelope fields.
