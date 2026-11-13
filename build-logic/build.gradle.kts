// SPDX-License-Identifier: Apache-2.0
plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.maven.publish.gradle.plugin)
    // ostrom.mobile applies com.android.kotlin.multiplatform.library only when ostrom.mobileTargets=true.
    implementation(libs.android.gradle.plugin)
}
