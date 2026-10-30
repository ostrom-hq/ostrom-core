package dev.ostrom.core.receipts

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.json.JsonElement

/** SHA-256 digests, in lowercase hexadecimal, as every digest of the format is written. */
public object Digests {

    /**
     * Returns the SHA-256 of [bytes].
     *
     * @param bytes The bytes to hash.
     *
     * @return 64 lowercase hex characters.
     */
    public fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHex()

    /**
     * Returns the SHA-256 of [bytes].
     *
     * @param bytes The bytes to hash.
     *
     * @return The 32-byte digest.
     */
    public fun sha256(bytes: ByteArray): ByteArray =
        CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(bytes)

    /**
     * Returns `digestOf(value)` of the prototype: the SHA-256 of the UTF-8 canonical form of [value]. Every digest
     * cited inside a receipt (`subject.digest`, `rules[].digest`, `shownDigest`) and every gate request key is one.
     *
     * @param value The JSON value.
     *
     * @return 64 lowercase hex characters.
     */
    public fun digestOf(value: JsonElement): String = sha256Hex(CanonicalJson.bytes(value))
}

private const val HEX = "0123456789abcdef"
private const val BYTE_MASK = 0xFF
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0F

internal fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val v = byte.toInt() and BYTE_MASK
        append(HEX[v ushr NIBBLE_BITS]).append(HEX[v and NIBBLE_MASK])
    }
}
