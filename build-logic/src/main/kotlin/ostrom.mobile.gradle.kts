// SPDX-License-Identifier: Apache-2.0
// Mobile targets of a module the companion app uses (api, client, receipts): Android
// (com.android.kotlin.multiplatform.library, minSdk 26) and iOS (iosArm64, iosSimulatorArm64), published with the
// other targets. Same setup as teamcode's api and client (teamcode:client/build.gradle.kts). A module applies `id("ostrom.mobile")` next to `id("ostrom.kmp")`.
//
// Gated by the Gradle property `ostrom.mobileTargets` (default off). This repository turns it on in its
// gradle.properties; ostrom-server and ostrom-cli include this build in their CI (`ostrom.core.local=true`) and only
// the root build's gradle.properties apply there, so they never configure an Android or iOS target, need no Android
// SDK and no Google repository for their own modules. A build that wants the mobile variants from source (the
// companion app including ostrom-core) sets `ostrom.mobileTargets=true` itself. The Android tasks alone need an SDK
// (ANDROID_HOME or local.properties); JVM, native, Kover and detekt tasks configure and run without one, which this
// repository's CI checks by running them with the SDK variables unset.

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("ostrom.kmp")
}

val mobileTargets = providers.gradleProperty("ostrom.mobileTargets").orNull
require(mobileTargets == null || mobileTargets == "true" || mobileTargets == "false") {
    "ostrom.mobileTargets must be true or false, got $mobileTargets."
}

if (mobileTargets == "true") {
    pluginManager.apply("com.android.kotlin.multiplatform.library")
    val libs = the<VersionCatalogsExtension>().named("libs")
    fun sdk(alias: String): Int = libs.findVersion(alias).get().requiredVersion.toInt()

    extensions.configure<KotlinMultiplatformExtension> {
        iosArm64()
        iosSimulatorArm64()
        // `kotlin { android { } }`: the extension AGP 9 registers on the Kotlin extension (androidLibrary is deprecated).
        (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "run.ostrom.core.${project.name}"
            compileSdk = sdk("android-compileSdk")
            minSdk = sdk("android-minSdk")
            // D8 and minSdk 26 read Java 17 bytecode; the toolchain stays on 21 (teamcode does the same).
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            // commonTest on the JVM of the host, with android.jar: `testAndroidHostTest`, run by CI with the SDK.
            withHostTest {}
        }
    }
}
