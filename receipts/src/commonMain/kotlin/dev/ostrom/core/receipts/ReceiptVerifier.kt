package dev.ostrom.core.receipts

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Verifies receipts offline, against the public keys the caller trusts. */
public interface ReceiptVerifier {

    /**
     * Verifies a receipt as received, unknown fields included: remove `signature`, recompute the canonical form,
     * compare its digest, find the key by kid, check the Ed25519 signature. There is deliberately no overload taking
     * the typed `Receipt`: decoding drops the fields the model does not know, and a genuine receipt from a newer server
     * would then read as modified.
     *
     * @param receipt The receipt JSON object.
     *
     * @return The outcome, with the recomputed digest.
     */
    public fun verify(receipt: JsonObject): ReceiptVerification
}

/** The outcome of a receipt verification. */
public sealed interface ReceiptVerification {

    /** SHA-256 hex of the canonical form of the body, recomputed; null when the receipt has no body to hash. */
    public val digest: String?

    /** True when the receipt is verified. */
    public val ok: Boolean

    /**
     * The receipt is intact and signed by a trusted key.
     *
     * @property digest Digest of the signed bytes.
     * @property kid Key that signed it.
     */
    public data class Verified(override val digest: String, val kid: String) : ReceiptVerification {
        override val ok: Boolean get() = true
    }

    /**
     * The receipt does not verify.
     *
     * @property reason Why.
     * @property digest Digest of the body as received.
     */
    public data class Failed(val reason: ReceiptVerificationFailure, override val digest: String?) : ReceiptVerification {
        override val ok: Boolean get() = false
    }
}

/**
 * Why a receipt does not verify; [message] is the prototype's reason text.
 *
 * @property message Reason shown to people.
 */
public enum class ReceiptVerificationFailure(public val message: String) {
    /** The body cannot be canonicalized (nested deeper than [CanonicalJson.MAX_DEPTH]). */
    MALFORMED("malformed receipt"),

    /** No `signature` string field. */
    MISSING_SIGNATURE("missing signature"),

    /** The signature is not `ed25519;…`. */
    UNKNOWN_ALGORITHM("unknown algorithm"),

    /** No trusted key has this kid. */
    UNKNOWN_KEY_ID("unknown key id"),

    /** The body was modified after signing. */
    DIGEST_MISMATCH("digest mismatch (receipt was modified)"),

    /** The Ed25519 signature does not match. */
    BAD_SIGNATURE("bad signature"),
}

/**
 * The verifier over a set of trusted keys (the instance key, keys pinned on a workstation, a key given on the
 * command line). A key carried by the document being verified is never trusted by this class: the caller chooses.
 *
 * @param trustedKeys The keys to trust.
 */
public class ReceiptVerifierImpl(trustedKeys: List<ReceiptPublicKey>) : ReceiptVerifier {

    private val keys = trustedKeys.associateBy { it.kid }

    override fun verify(receipt: JsonObject): ReceiptVerification {
        val body = JsonObject(receipt - ReceiptSignerImpl.SIGNATURE)
        val bytes = try {
            CanonicalJson.bytes(body)
        } catch (e: CanonicalJsonException) {
            return ReceiptVerification.Failed(ReceiptVerificationFailure.MALFORMED, null)
        }
        val digest = Digests.sha256Hex(bytes)
        val raw = (receipt[ReceiptSignerImpl.SIGNATURE] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: return ReceiptVerification.Failed(ReceiptVerificationFailure.MISSING_SIGNATURE, digest)
        // same order of checks as the prototype's verifyReceipt
        val field = SignatureField.parse(raw)
        if (field.algorithm != SignatureField.ED25519) return failed(ReceiptVerificationFailure.UNKNOWN_ALGORITHM, digest)
        val key = keys[field.kid] ?: return failed(ReceiptVerificationFailure.UNKNOWN_KEY_ID, digest)
        if (field.sha256 != digest) return failed(ReceiptVerificationFailure.DIGEST_MISMATCH, digest)
        val signature = field.signature ?: return failed(ReceiptVerificationFailure.BAD_SIGNATURE, digest)
        if (!key.verify(bytes, signature)) return failed(ReceiptVerificationFailure.BAD_SIGNATURE, digest)
        return ReceiptVerification.Verified(digest, key.kid)
    }

    private fun failed(reason: ReceiptVerificationFailure, digest: String) = ReceiptVerification.Failed(reason, digest)
}
