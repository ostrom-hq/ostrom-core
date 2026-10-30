package dev.ostrom.receipts

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA

private val edDsa: EdDSA get() = CryptographyProvider.Default.get(EdDSA)

/**
 * The Ed25519 private key of an Ostrom instance: signs receipts and session packages.
 *
 * @property publicKey The matching public key, with its key id.
 */
public class ReceiptSigningKey private constructor(
    private val privateKey: EdDSA.PrivateKey,
    public val publicKey: ReceiptPublicKey,
) {

    /** Returns the private key as a PKCS#8 PEM, the form the prototype stores in `receipt-key.json`. */
    public fun toPkcs8Pem(): String = privateKey.encodeToByteArrayBlocking(EdDSA.PrivateKey.Format.PEM).decodeToString()

    /**
     * Signs [bytes] with pure Ed25519 (RFC 8032, no pre-hash). Ed25519 is deterministic: the same key and bytes always
     * give the same signature.
     *
     * @param bytes The bytes to sign.
     *
     * @return The 64-byte signature.
     */
    public fun sign(bytes: ByteArray): ByteArray = privateKey.signatureGenerator().generateSignatureBlocking(bytes)

    /** Builders of signing keys. */
    public companion object {

        /**
         * Reads a PKCS#8 PEM private key (`-----BEGIN PRIVATE KEY-----`).
         *
         * @param pem The PEM text.
         *
         * @return The signing key.
         */
        public fun fromPkcs8Pem(pem: String): ReceiptSigningKey = of(
            edDsa.privateKeyDecoder(EdDSA.Curve.Ed25519)
                .decodeFromByteArrayBlocking(EdDSA.PrivateKey.Format.PEM, pem.encodeToByteArray())
        )

        /**
         * Builds the key from its 32-byte RFC 8032 seed.
         *
         * @param seed The seed.
         *
         * @return The signing key.
         */
        public fun fromSeed(seed: ByteArray): ReceiptSigningKey {
            require(seed.size == 32) { "an Ed25519 seed is 32 bytes" }
            return of(edDsa.privateKeyDecoder(EdDSA.Curve.Ed25519).decodeFromByteArrayBlocking(EdDSA.PrivateKey.Format.RAW, seed))
        }

        /** Generates a new random key, as an instance does on its first start. */
        public fun generate(): ReceiptSigningKey =
            of(edDsa.keyPairGenerator(EdDSA.Curve.Ed25519).generateKeyBlocking().privateKey)

        private fun of(privateKey: EdDSA.PrivateKey): ReceiptSigningKey =
            ReceiptSigningKey(privateKey, ReceiptPublicKey.of(privateKey.getPublicKeyBlocking()))
    }
}

/**
 * An Ed25519 public key that verifies receipts and session packages.
 *
 * @property kid Key id: the first 16 hex characters of the SHA-256 of the SPKI DER.
 * @property spkiDer The SubjectPublicKeyInfo DER bytes.
 */
public class ReceiptPublicKey private constructor(
    private val publicKey: EdDSA.PublicKey,
    public val kid: String,
    public val spkiDer: ByteArray,
) {

    /** Returns the SPKI PEM (`-----BEGIN PUBLIC KEY-----`), what `GET /api/receipts/pubkey` serves. */
    public fun toSpkiPem(): String = publicKey.encodeToByteArrayBlocking(EdDSA.PublicKey.Format.PEM).decodeToString()

    /** Returns the 32 raw bytes of the key (RFC 8032). */
    public fun toRaw(): ByteArray = publicKey.encodeToByteArrayBlocking(EdDSA.PublicKey.Format.RAW)

    /**
     * Verifies a pure Ed25519 [signature] over [bytes].
     *
     * @param bytes The signed bytes.
     * @param signature The 64-byte signature.
     *
     * @return True when the signature is valid for this key.
     */
    public fun verify(bytes: ByteArray, signature: ByteArray): Boolean =
        signature.size == 64 && publicKey.signatureVerifier().tryVerifySignatureBlocking(bytes, signature)

    /** Readers of public keys. */
    public companion object {

        /**
         * Reads an SPKI PEM public key.
         *
         * @param pem The PEM text.
         *
         * @return The public key and its kid.
         */
        public fun fromSpkiPem(pem: String): ReceiptPublicKey = of(
            edDsa.publicKeyDecoder(EdDSA.Curve.Ed25519)
                .decodeFromByteArrayBlocking(EdDSA.PublicKey.Format.PEM, pem.encodeToByteArray())
        )

        /**
         * Reads the 32 raw bytes of a public key.
         *
         * @param raw The raw key.
         *
         * @return The public key and its kid.
         */
        public fun fromRaw(raw: ByteArray): ReceiptPublicKey = of(
            edDsa.publicKeyDecoder(EdDSA.Curve.Ed25519).decodeFromByteArrayBlocking(EdDSA.PublicKey.Format.RAW, raw)
        )

        internal fun of(publicKey: EdDSA.PublicKey): ReceiptPublicKey {
            val der = publicKey.encodeToByteArrayBlocking(EdDSA.PublicKey.Format.DER)
            return ReceiptPublicKey(publicKey, Digests.sha256Hex(der).substring(0, 16), der)
        }
    }
}
