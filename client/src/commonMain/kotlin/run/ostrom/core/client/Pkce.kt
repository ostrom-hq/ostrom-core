// SPDX-License-Identifier: Apache-2.0
// The PKCE pair of teamcode's OAuthSignIn (https://github.com/nathanfallet/teamcode, Apache-2.0,
// client/src/commonMain/kotlin/me/nathanfallet/teamcode/client/OAuthSignIn.kt), as ostrom-app's sign-in uses it.
package run.ostrom.core.client

import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/**
 * A PKCE pair (RFC 7636) for one sign-in: [verifier] stays on the device until the code exchange
 * ([AuthClient.exchangeMobileCode]); only [challenge] goes into the start URL ([AuthClient.githubStartUrl]).
 * [toString] hides both.
 *
 * @property verifier 32 random bytes as unpadded base64url (43 characters).
 * @property challenge The S256 challenge of [verifier]: unpadded base64url of its SHA-256.
 */
public class Pkce private constructor(
    public val verifier: String,
    public val challenge: String,
) {
    override fun toString(): String = "Pkce([REDACTED])"

    /** Builds the pair and encodes the bytes the way PKCE and OAuth `state` need. */
    public companion object {
        /** The challenge method of [challenge], sent as `code_challenge_method`. */
        public const val METHOD: String = "S256"

        private const val VERIFIER_BYTES = 32

        /** A fresh pair; [randomBytes] must be a cryptographically secure source (the platform's). */
        public fun create(randomBytes: (Int) -> ByteArray): Pkce {
            val bytes = randomBytes(VERIFIER_BYTES)
            require(bytes.size == VERIFIER_BYTES) { "Expected $VERIFIER_BYTES random bytes." }
            val verifier = base64Url(bytes)
            return Pkce(verifier, challenge(verifier))
        }

        /** The S256 challenge of [verifier] (RFC 7636 section 4.2). */
        public fun challenge(verifier: String): String = base64Url(verifier.encodeUtf8().sha256().toByteArray())

        /** [bytes] as base64url without padding (RFC 4648 section 5): a PKCE verifier, an OAuth `state`. */
        public fun base64Url(bytes: ByteArray): String = bytes.toByteString().base64Url().trimEnd('=')
    }
}
