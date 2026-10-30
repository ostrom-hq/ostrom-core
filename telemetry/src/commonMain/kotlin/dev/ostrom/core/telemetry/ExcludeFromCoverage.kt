// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.telemetry

/**
 * Leaves a function out of the 100 % coverage rule (telemetry/build.gradle.kts). Every use carries a
 * `// coverage: <why>` line right above it, read in review.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
internal annotation class ExcludeFromCoverage
