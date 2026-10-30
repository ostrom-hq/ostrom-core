package dev.ostrom.core.receipts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceiptKeysTest {

    private val key = Vectors.key

    @Test
    fun theSeedGivesThePublicKeyAndTheKidOfTheVectors() {
        val signing = Vectors.signingKey
        assertEquals(key.text("kid"), signing.publicKey.kid)
        assertEquals(key.text("publicKeyRawHex"), signing.publicKey.toRaw().toHex())
        assertEquals(key.text("publicKeySpkiDerHex"), signing.publicKey.spkiDer.toHex())
    }

    @Test
    fun pemKeysReadAndWriteAsThePrototype() {
        val signing = ReceiptSigningKey.fromPkcs8Pem(key.text("privateKeyPkcs8Pem"))
        assertEquals(key.text("kid"), signing.publicKey.kid)
        assertEquals(key.text("publicKeySpkiPem").trim(), signing.publicKey.toSpkiPem().trim())
        assertEquals(key.text("kid"), ReceiptPublicKey.fromSpkiPem(key.text("publicKeySpkiPem")).kid)
        assertEquals(key.text("kid"), ReceiptSigningKey.fromPkcs8Pem(signing.toPkcs8Pem()).publicKey.kid)
    }

    @Test
    fun aGeneratedKeySignsAndVerifies() {
        val generated = ReceiptSigningKey.generate()
        val bytes = "ostrom".encodeToByteArray()
        val signature = generated.sign(bytes)
        assertTrue(generated.publicKey.verify(bytes, signature))
        assertFalse(generated.publicKey.verify("other".encodeToByteArray(), signature))
        assertFalse(Vectors.signingKey.publicKey.verify(bytes, signature))
    }
}
