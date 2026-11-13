// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (https://github.com/nathanfallet/teamcode, Apache-2.0): TeamCodeException of
// client/src/commonMain/kotlin/me/nathanfallet/teamcode/client/TeamCodeClient.kt.
package run.ostrom.core.client

/**
 * A refused or failed Ostrom call; safe to print and to log.
 *
 * A non-2xx answer gives its HTTP [status]; an event stream that closes, or carries an event this client cannot
 * read, gives status `0`. Network failures are never an [OstromException]: they propagate as the HTTP engine
 * reports them (see [networkFailure]).
 *
 * @property status The HTTP status, or `0` for a failure of an event stream after it opened.
 * @property type The contract's `error.type` (`step_up_required`, `org_required`…) when the body was an `ApiError`,
 *   or [OstromClient.MALFORMED_EVENT] for an unreadable event; null otherwise (a proxy's page, a closed stream).
 * @property retryAfterSeconds The `Retry-After` delay in seconds, when the answer carried one as a number.
 * @property requestBound True for a `step_up_required` of a phone's yes whose unlock is not bound to this very
 *   approval: unlock again with its `requestId`, then send the yes again.
 */
public class OstromException(
    public val status: Int,
    public val type: String?,
    message: String,
    public val retryAfterSeconds: Long? = null,
    public val requestBound: Boolean = false,
) : Exception(message)
