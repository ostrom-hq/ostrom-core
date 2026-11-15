// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

/**
 * The effective member of a cloud session write (`runners.cloud.onbehalf.v1`, TeamCode's `TeamCode-On-Behalf-Of`):
 * an owner or an admin signed in, or an administrator's `cloud:admin` service key, creates, pauses, resumes or deletes
 * another member's session by naming them in [HEADER], by member id or by login. The header never grants a right of its
 * own; the server answers [FORBIDDEN] for any other caller naming someone else, [INVALID_MEMBER] for a target who is
 * not an active member, and [INVALID] for a malformed or repeated header. A server without the capability ignores the
 * header, so a client checks the capability before sending it.
 */
public object OnBehalfOf {
    /** The request header naming the effective member. */
    public const val HEADER: String = "Ostrom-On-Behalf-Of"

    /** The error key (`400`) of several values or a malformed one. */
    public const val INVALID: String = "invalid_on_behalf"

    /** The error key (`403`) of a caller who may not act for another member. */
    public const val FORBIDDEN: String = "on_behalf_forbidden"

    /** The error key (`422`) of a target who is not an active member of the organization. */
    public const val INVALID_MEMBER: String = "invalid_effective_member"

    /** The error key (`422`) of a creation on someone's behalf from a profile that requires GitHub. */
    public const val GITHUB_OWNER_CONSENT: String = "github_owner_consent"

    /** The longest reference. */
    public const val MAX_LENGTH: Int = 64

    /** A reference is 1 to [MAX_LENGTH] characters of `A-Za-z0-9._-`: a member id or a login. */
    public val PATTERN: Regex = Regex("[A-Za-z0-9._-]{1,$MAX_LENGTH}")

    /** @return Whether [reference] is one the server reads; a malformed one is refused with 400 [INVALID]. */
    public fun isValid(reference: String): Boolean = PATTERN.matches(reference)
}
