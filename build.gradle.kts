// Kotlin, serialization, Kover, detekt and the publish plugin come from build-logic (ostrom.kmp and ostrom.publish).

// CI passes -PostromCoreVersion=0.1.<run number> on main; local builds and includeBuild consumers see the snapshot.
val ostromCoreVersion = providers.gradleProperty("ostromCoreVersion").orElse("0.1.0-SNAPSHOT").get()

allprojects {
    group = "run.ostrom"
    version = ostromCoreVersion
}
