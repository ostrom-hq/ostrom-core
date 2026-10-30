package dev.ostrom.core.receipts

import dev.ostrom.core.api.models.Receipt
import dev.ostrom.core.api.models.ReceiptDraft
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Signs receipts with the instance key (format v1, `mvp/server/src/receipts.ts` of the prototype). */
public interface ReceiptSigner {

    /** The public key that verifies what this signer signs. */
    public val publicKey: ReceiptPublicKey

    /**
     * Signs a receipt body given as raw JSON: every field is signed, known or not.
     *
     * @param body The receipt without its `signature` field.
     *
     * @return The body with its `signature` field added.
     */
    public fun sign(body: JsonObject): JsonObject

    /**
     * Signs a typed receipt draft.
     *
     * @param draft The receipt before signing.
     *
     * @return The signed receipt.
     */
    public fun sign(draft: ReceiptDraft): Receipt
}

/**
 * The Ed25519 signer.
 *
 * @param key The instance's signing key.
 */
public class ReceiptSignerImpl(private val key: ReceiptSigningKey) : ReceiptSigner {

    override val publicKey: ReceiptPublicKey get() = key.publicKey

    override fun sign(body: JsonObject): JsonObject {
        require(SIGNATURE !in body) { "the body to sign already has a signature" }
        val bytes = CanonicalJson.bytes(body)
        val field = SignatureField(SignatureField.ED25519, key.publicKey.kid, Digests.sha256Hex(bytes), key.sign(bytes))
        return JsonObject(body + (SIGNATURE to JsonPrimitive(field.toString())))
    }

    override fun sign(draft: ReceiptDraft): Receipt {
        val signed = sign(ReceiptJson.encodeToJsonElement(ReceiptDraft.serializer(), draft) as JsonObject)
        return ReceiptJson.decodeFromJsonElement(Receipt.serializer(), signed)
    }

    internal companion object {
        const val SIGNATURE = "signature"
    }
}
