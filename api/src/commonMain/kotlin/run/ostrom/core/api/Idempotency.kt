// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import kotlin.uuid.Uuid

/**
 * The idempotency key of a creating call, shared by every route that takes one (a workflow run's start now, a cloud
 * session's creation next): the same key returns what the first call created instead of creating it twice, so pressing
 * Start again after a lost answer is safe.
 *
 * The client sends one fresh [newKey] per press and reuses it for every retry of that press. The server scopes a key
 * to the organization, the caller and the route. A replay answers with the [REPLAYED_HEADER] set to `true` and, for a
 * workflow run, the status `200` instead of `201`.
 */
public object Idempotency {
    /** The request header that carries the key. */
    public const val KEY_HEADER: String = "Idempotency-Key"

    /** The response header, `true`, of an answer that replays an earlier call. */
    public const val REPLAYED_HEADER: String = "Idempotency-Replayed"

    /** The error key (`422`) of a key reused for another request, on the routes that compare requests. */
    public const val CONFLICT: String = "idempotency_conflict"

    /** The longest key. */
    public const val MAX_LENGTH: Int = 128

    /** A key is 1 to [MAX_LENGTH] characters of `A-Za-z0-9._:-`. */
    public val PATTERN: Regex = Regex("[A-Za-z0-9._:-]{1,$MAX_LENGTH}")

    /** @return Whether [key] is one the server accepts; a malformed key is refused with 400. */
    public fun isValid(key: String): Boolean = PATTERN.matches(key)

    /** @return A new random key, valid. */
    public fun newKey(): String = Uuid.random().toString()
}
