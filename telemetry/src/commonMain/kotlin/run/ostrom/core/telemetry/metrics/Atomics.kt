// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.telemetry.metrics

import run.ostrom.core.telemetry.ExcludeFromCoverage
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.concurrent.atomics.updateAndFetch

// coverage: a compare-and-set retry loop; the retry branch only runs under contention, which a test cannot force.
@ExcludeFromCoverage
@OptIn(ExperimentalAtomicApi::class)
internal fun <T> AtomicReference<T>.atomicUpdate(transform: (T) -> T): T = updateAndFetch(transform)

// coverage: a compare-and-set retry loop; the retry branch only runs under contention, which a test cannot force.
@ExcludeFromCoverage
@OptIn(ExperimentalAtomicApi::class)
internal fun AtomicLong.atomicUpdate(transform: (Long) -> Long) {
    update(transform)
}
