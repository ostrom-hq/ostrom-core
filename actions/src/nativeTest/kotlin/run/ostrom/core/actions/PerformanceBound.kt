// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.actions

// Test binaries are debug builds: unoptimized, several times slower than the release CLI.
internal actual val MAX_AVERAGE_MICROS: Long = 1_000
