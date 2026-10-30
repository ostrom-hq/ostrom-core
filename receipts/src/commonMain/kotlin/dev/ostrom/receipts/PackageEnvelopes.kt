package dev.ostrom.receipts

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
     * @param envelope The envelope JSON object.
     * @param trustedKeys The keys to trust.
     *
     * @return Null when the envelope verifies, else the reason.
     */
    public fun verify(envelope: JsonObject, trustedKeys: List<ReceiptPublicKey>): String? {
        fun text(name: String) = (envelope[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (text("format") != FORMAT) return "not an $FORMAT envelope"
        if (text("alg") != SignatureField.ED25519) return "unknown algorithm"
        val payload = text("payload") ?: return "missing payload"
        val bytes = payload.encodeToByteArray()
        if (Digests.sha256Hex(bytes) != text("digest")) return "digest mismatch (package was modified)"
        val key = trustedKeys.firstOrNull { it.kid == text("kid") } ?: return "unknown key id"
        val signature = text("signature")?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return "bad signature"
        return if (key.verify(bytes, signature)) null else "bad signature"
    }
}
