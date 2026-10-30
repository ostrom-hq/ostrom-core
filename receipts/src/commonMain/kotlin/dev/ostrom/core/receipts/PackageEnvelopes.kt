package dev.ostrom.core.receipts

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
     * Verifies an envelope: format, digest of the payload bytes, trusted kid, signature.
     *
     * Only `payload` is signed. The envelope's `repo`, `version`, `contentDigest`, `pinned` and `revoked` are not: read
     * the repository, version and content digest from [PackageVerification.Verified.payload] (the decoded
     * `SessionPackage`), and the pin and revocation state from the server you trust, never from the envelope alone.
     *
     * @param envelope The envelope JSON object.
     * @param trustedKeys The keys to trust.
     *
     * @return The verified payload text, or the reason it does not verify.
     */
    public fun verify(envelope: JsonObject, trustedKeys: List<ReceiptPublicKey>): PackageVerification {
        fun text(name: String) = (envelope[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun failed(reason: String) = PackageVerification.Failed(reason)
        if (text("format") != FORMAT) return failed("not an $FORMAT envelope")
        if (text("alg") != SignatureField.ED25519) return failed("unknown algorithm")
        val payload = text("payload") ?: return failed("missing payload")
        val bytes = payload.encodeToByteArray()
        if (Digests.sha256Hex(bytes) != text("digest")) return failed("digest mismatch (package was modified)")
        val key = trustedKeys.firstOrNull { it.kid == text("kid") } ?: return failed("unknown key id")
        val signature = text("signature")?.let(::decodeBase64OrNull) ?: return failed("bad signature")
        return if (key.verify(bytes, signature)) PackageVerification.Verified(payload, key.kid) else failed("bad signature")
    }
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
