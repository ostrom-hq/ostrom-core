package run.ostrom.core.receipts

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.io.encoding.Base64

/**
 * Session package envelopes (`ostrom.package/1`): `{format, version, repo, digest, contentDigest, alg, kid, signature,
 * payload, pinned, revoked}`. `payload` is the canonical text of the compiled package; `digest` is the SHA-256 of its
 * UTF-8 bytes and `signature` the Ed25519 signature of those same bytes, plain base64, with no `ed25519;…` prefix
 * (unlike receipts). A signed package is served as is and verified on the bytes received, never re-canonicalized.
 */
public object PackageEnvelopes {

    /** The envelope format name. */
    public const val FORMAT: String = "ostrom.package/1"

    /** Envelope fields copied out of the signed payload. */
    private val COPIED_FIELDS = listOf("version", "repo", "contentDigest")

    /**
     * The digest and the signature of a package payload.
     *
     * @property digest SHA-256 hex of the payload bytes.
     * @property signature Base64 Ed25519 signature of the payload bytes.
     * @property kid Key id of the signing key.
     */
    public data class Signature(val digest: String, val signature: String, val kid: String)

    /**
     * Signs a package payload.
     *
     * @param payload The canonical text of the compiled package.
     * @param key The instance key.
     *
     * @return Its digest, signature and kid, to write into the envelope.
     */
    public fun sign(payload: String, key: ReceiptSigningKey): Signature {
        val bytes = payload.encodeToByteArray()
        return Signature(Digests.sha256Hex(bytes), Base64.encode(key.sign(bytes)), key.publicKey.kid)
    }

    /**
     * Verifies an envelope: format, digest of the payload bytes, trusted kid, signature; then refuses a revoked
     * envelope, and one whose `version`, `repo` or `contentDigest` differs from the signed payload's.
     *
     * Only `payload` is signed. The envelope's copies of `repo`, `version` and `contentDigest` are checked against it,
     * but read them from [PackageVerification.Verified.payload] (the decoded `SessionPackage`). `revoked: true` is
     * refused; its absence proves nothing: ask the server you trust for the revocation state.
     *
     * @param envelope The envelope JSON object.
     * @param trustedKeys The keys to trust.
     *
     * @return The verified payload text, or the reason it does not verify.
     */
    public fun verify(envelope: JsonObject, trustedKeys: List<ReceiptPublicKey>): PackageVerification {
        fun text(name: String) = (envelope[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val payload = text("payload")?.let { it to it.encodeToByteArray() }
        val key = trustedKeys.firstOrNull { it.kid == text("kid") }
        val signature = text("signature")?.let(::decodeBase64OrNull)
        return when {
            text("format") != FORMAT -> failed("not an $FORMAT envelope")
            text("alg") != SignatureField.ED25519 -> failed("unknown algorithm")
            payload == null -> failed("missing payload")
            Digests.sha256Hex(payload.second) != text("digest") -> failed("digest mismatch (package was modified)")
            key == null -> failed("unknown key id")
            signature == null || !key.verify(payload.second, signature) -> failed("bad signature")
            envelope["revoked"] == JsonPrimitive(true) -> failed("package revoked")
            else -> matchSignedPayload(envelope, payload.first, key.kid)
        }
    }

    /** Refuses a payload that is not a JSON object, and an envelope whose copied fields differ from it. */
    private fun matchSignedPayload(envelope: JsonObject, payload: String, kid: String): PackageVerification {
        val signed = try {
            CanonicalJson.parse(payload) as? JsonObject
        } catch (expected: SerializationException) {
            null
        }
        val copied = signed?.let { payloadObject ->
            COPIED_FIELDS.firstOrNull { it in envelope && envelope[it] != payloadObject[it] }
        }
        return when {
            signed == null -> failed("payload is not a package")
            copied != null -> failed("envelope $copied does not match the signed payload")
            else -> PackageVerification.Verified(payload, kid)
        }
    }

    private fun failed(reason: String) = PackageVerification.Failed(reason)
}

/** The outcome of a package envelope verification. */
public sealed interface PackageVerification {

    /**
     * The payload is intact and signed by a trusted key.
     *
     * @property payload The exact payload text: decode it, never re-canonicalize it.
     * @property kid Key that signed it.
     */
    public data class Verified(val payload: String, val kid: String) : PackageVerification

    /**
     * The envelope does not verify.
     *
     * @property reason Why.
     */
    public data class Failed(val reason: String) : PackageVerification
}
