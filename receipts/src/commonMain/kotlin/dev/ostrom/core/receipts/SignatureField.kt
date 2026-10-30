package dev.ostrom.core.receipts

import kotlin.io.encoding.Base64

/**
 * The `signature` field of a receipt: `ed25519;kid=<kid>;sha256=<hex of the signed bytes>;sig=<base64 with padding>`.
 *
 * @property algorithm Algorithm name, `ed25519` for every receipt signed so far.
 * @property kid Key id of the signing key.
 * @property sha256 SHA-256 hex of the signed bytes.
 * @property signature The raw signature.
 */
public class SignatureField(
    public val algorithm: String,
    public val kid: String?,
    public val sha256: String?,
    public val signature: ByteArray?,
) {

    /** Writes the field as the prototype does. */
    override fun toString(): String =
        "$algorithm;kid=$kid;sha256=$sha256;sig=${signature?.let { Base64.encode(it) }}"

    /** Parser of the field. */
    public companion object {

        /** The only algorithm of format v1. */
        public const val ED25519: String = "ed25519"

        /**
         * Parses a signature field. Unknown parts are ignored, missing ones are null; a `sig` that is not base64 is null.
         *
         * @param field The field text.
         *
         * @return The parsed field.
         */
        public fun parse(field: String): SignatureField {
            val parts = field.split(';')
            val values = parts.drop(1).associate { part ->
                val i = part.indexOf('=')
                if (i < 0) part to "" else part.substring(0, i) to part.substring(i + 1)
            }
            val signature = values["sig"]?.let(::decodeBase64OrNull)
            return SignatureField(parts.first(), values["kid"], values["sha256"], signature)
        }
    }
}

/** Decodes standard base64 with padding; null when [text] is not base64. */
internal fun decodeBase64OrNull(text: String): ByteArray? = try {
    Base64.decode(text)
} catch (e: IllegalArgumentException) {
    null
}
